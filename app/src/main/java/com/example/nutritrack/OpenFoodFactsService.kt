package com.example.nutritrack

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

class OpenFoodFactsService {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Looks up a barcode (EAN-13, UPC-A, etc.) on OpenFoodFacts API v2 and scales
     * the 100g nutritional values to the user's consumed weight in grams.
     */
    suspend fun lookupBarcode(
        barcode: String,
        consumedGrams: Float = 100f
    ): Result<AiMealEstimate> = withContext(Dispatchers.IO) {
        val cleanBarcode = barcode.trim()
        if (cleanBarcode.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Barcode cannot be empty."))
        }

        try {
            val fields = "product_name,brands,quantity,serving_size,ingredients_text,nutriscore_grade,nutriments"
            val url = URL("https://world.openfoodfacts.org/api/v2/product/$cleanBarcode.json?fields=$fields")

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "NutriTrackAI/1.0 (Android; contact@example.com)")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                return@withContext Result.failure(
                    RuntimeException("Product not found on OpenFoodFacts (HTTP $code) for barcode $cleanBarcode")
                )
            }

            val rawResponse = connection.inputStream.bufferedReader().use { it.readText() }
            val root = json.parseToJsonElement(rawResponse).jsonObject

            val status = root["status"]?.jsonPrimitive?.intOrNull ?: 0
            if (status != 1) {
                return@withContext Result.failure(
                    RuntimeException("Barcode $cleanBarcode is not registered in OpenFoodFacts.")
                )
            }

            val product = root["product"]?.jsonObject
                ?: return@withContext Result.failure(RuntimeException("Missing product payload."))

            val rawName = product["product_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "Scanned Product ($cleanBarcode)"
            val brand = product["brands"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val fullTitle = if (brand != null && !rawName.contains(brand, ignoreCase = true)) {
                "$rawName ($brand)"
            } else {
                rawName
            }

            val servingSizeLabel = product["serving_size"]?.jsonPrimitive?.contentOrNull
                ?: product["quantity"]?.jsonPrimitive?.contentOrNull
                ?: "100g reference"

            val nutriscore = product["nutriscore_grade"]?.jsonPrimitive?.contentOrNull?.uppercase()

            val ingredientsRaw = product["ingredients_text"]?.jsonPrimitive?.contentOrNull ?: ""
            val ingredientsList = ingredientsRaw
                .split(",", ";")
                .map { it.replace("_", "").trim() }
                .filter { it.isNotEmpty() }
                .take(12)

            val nutriments = product["nutriments"]?.jsonObject
            val scaleFactor = (consumedGrams / 100f).coerceAtLeast(0.01f)

            val kcalPer100g = nutriments.extractFloat("energy-kcal_100g")
                ?: nutriments.extractFloat("energy-kcal")
                ?: ((nutriments.extractFloat("energy_100g") ?: 0f) / 4.184f)

            val proteinPer100g = nutriments.extractFloat("proteins_100g") ?: 0f
            val carbsPer100g = nutriments.extractFloat("carbohydrates_100g") ?: 0f
            val fatPer100g = nutriments.extractFloat("fat_100g") ?: 0f
            val fiberPer100g = nutriments.extractFloat("fiber_100g") ?: 0f

            val scaledCalories = (kcalPer100g * scaleFactor).roundToInt()
            val scaledProtein = round1Dec(proteinPer100g * scaleFactor)
            val scaledCarbs = round1Dec(carbsPer100g * scaleFactor)
            val scaledFat = round1Dec(fatPer100g * scaleFactor)
            val scaledFiber = round1Dec(fiberPer100g * scaleFactor)

            val tip = buildString {
                append("OpenFoodFacts verified ($cleanBarcode)")
                if (nutriscore != null && nutriscore in listOf("A", "B", "C", "D", "E")) {
                    append(" • Nutri-Score: $nutriscore")
                }
                append(" • Package serving info: $servingSizeLabel.")
            }

            Result.success(
                AiMealEstimate(
                    mealName = fullTitle,
                    estimatedQuantity = "${consumedGrams.toInt()}g (Scanned Barcode)",
                    ingredients = if (ingredientsList.isEmpty()) listOf("See package label ($cleanBarcode)") else ingredientsList,
                    calories = scaledCalories,
                    proteinGrams = scaledProtein,
                    carbsGrams = scaledCarbs,
                    fatGrams = scaledFat,
                    fiberGrams = scaledFiber,
                    healthTip = tip
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun JsonObject?.extractFloat(key: String): Float? {
        val primitive = this?.get(key)?.jsonPrimitive ?: return null
        return primitive.floatOrNull ?: primitive.contentOrNull?.toFloatOrNull()
    }

    private fun round1Dec(value: Float): Float = (value * 10f).roundToInt() / 10f
}
