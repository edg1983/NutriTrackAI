package com.example.nutritrack

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class AiMealEstimate(
    val mealName: String,
    val estimatedQuantity: String,
    val ingredients: List<String>,
    val calories: Int,
    val proteinGrams: Float,
    val carbsGrams: Float,
    val fatGrams: Float,
    val fiberGrams: Float,
    val healthTip: String,
    val components: List<MealComponent> = emptyList()
)

class GeminiNutritionService {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Ordered from newest alias to legacy fallback so any API key tier succeeds
    private val candidateModels = listOf(
        "gemini-flash-latest",
        "gemini-3.8-flash",
        "gemini-3.5-flash",
        "gemini-2.5-flash"
    )

    suspend fun analyzeTextMeal(
        apiKey: String,
        mealDescription: String,
        quantityInput: String
    ): Result<AiMealEstimate> = withContext(Dispatchers.IO) {
        val prompt = """
            Analyze the following meal and quantity and estimate its nutritional content accurately.
            Meal Description: "$mealDescription"
            Quantity / Portion: "$quantityInput"
            
            Respond ONLY with a valid JSON object matching this exact schema:
            {
              "mealName": "Standardized concise meal title",
              "estimatedQuantity": "Normalized quantity in grams and/or portions (e.g., '300g (1 bowl)')",
              "ingredients": ["ingredient 1 with approx weight", "ingredient 2 with approx weight"],
              "calories": 450,
              "proteinGrams": 28.5,
              "carbsGrams": 42.0,
              "fatGrams": 16.0,
              "fiberGrams": 6.5,
              "healthTip": "One concise sentence on how to make this specific meal healthier or better balanced.",
              "components": [
                {
                  "name": "Component name (e.g. Grilled Chicken)",
                  "quantityDescription": "Quantity (e.g. 200g)",
                  "calories": 330,
                  "proteinGrams": 40.0,
                  "carbsGrams": 0.0,
                  "fatGrams": 8.0,
                  "fiberGrams": 0.0
                }
              ]
            }
        """.trimIndent()

        callGeminiAndParse<AiMealEstimate>(apiKey = apiKey, prompt = prompt, bitmap = null)
    }

    suspend fun analyzePhotoMeal(
        apiKey: String,
        bitmap: Bitmap,
        optionalHint: String
    ): Result<AiMealEstimate> = withContext(Dispatchers.IO) {
        val prompt = """
            Look at this meal photo. Identify the dish, estimate the visible portion size in grams and portions, break down the plate into individual detected components (e.g., 'Grilled Chicken', 'Stewed Carrots', 'Steamed Rice'), list ingredients, and calculate macronutrients for each component and the overall meal.
            ${if (optionalHint.isNotBlank()) "User note: $optionalHint" else ""}
            
            Respond ONLY with a valid JSON object matching this exact schema:
            {
              "mealName": "Detected meal name",
              "estimatedQuantity": "Estimated total weight in grams and portions (e.g., '380g (1 plate)')",
              "ingredients": ["ingredient 1 (approx grams)", "ingredient 2 (approx grams)"],
              "calories": 520,
              "proteinGrams": 32.0,
              "carbsGrams": 48.0,
              "fatGrams": 21.0,
              "fiberGrams": 5.0,
              "healthTip": "One concise sentence on the nutritional balance of this plate and how to improve it.",
              "components": [
                {
                  "name": "Component name (e.g. Grilled Chicken)",
                  "quantityDescription": "Portion (e.g. 200g)",
                  "calories": 330,
                  "proteinGrams": 40.0,
                  "carbsGrams": 0.0,
                  "fatGrams": 8.0,
                  "fiberGrams": 0.0
                },
                {
                  "name": "Component name (e.g. Stewed Carrots)",
                  "quantityDescription": "Portion (e.g. 120g)",
                  "calories": 90,
                  "proteinGrams": 1.2,
                  "carbsGrams": 14.0,
                  "fatGrams": 3.0,
                  "fiberGrams": 3.5
                }
              ]
            }
        """.trimIndent()

        callGeminiAndParse<AiMealEstimate>(apiKey = apiKey, prompt = prompt, bitmap = bitmap)
    }

    suspend fun analyzeComponentText(
        apiKey: String,
        componentName: String,
        descriptionInput: String
    ): Result<MealComponent> = withContext(Dispatchers.IO) {
        val prompt = """
            Analyze the following individual food item/component and its updated description to estimate its nutritional values accurately.
            Component Item: "$componentName"
            User Adjustment / Description: "$descriptionInput"
            
            Respond ONLY with a valid JSON object matching this exact schema:
            {
              "name": "Concise component title",
              "quantityDescription": "Quantity description in grams or portions (e.g., '180g')",
              "calories": 250,
              "proteinGrams": 22.0,
              "carbsGrams": 15.0,
              "fatGrams": 8.0,
              "fiberGrams": 3.0
            }
        """.trimIndent()

        callGeminiAndParse<MealComponent>(apiKey = apiKey, prompt = prompt, bitmap = null)
    }

    private inline fun <reified T> callGeminiAndParse(
        apiKey: String,
        prompt: String,
        bitmap: Bitmap?
    ): Result<T> {
        val resolvedKey = apiKey
            .ifBlank { BuildConfig.GEMINI_API_KEY }
            .replace("\\s+".toRegex(), "")
            .trim('"')

        if (resolvedKey.isEmpty()) {
            return Result.failure(
                IllegalStateException("Missing Gemini API Key. Tap the settings icon in the top bar to set your Gemini API key.")
            )
        }

        var lastError: Throwable = IllegalStateException("No models attempted.")

        for (modelName in candidateModels) {
            val attempt = executeSingleModelCallRaw(resolvedKey, modelName, prompt, bitmap)
            if (attempt.isSuccess) {
                val rawJson = attempt.getOrThrow()
                return try {
                    val parsed = json.decodeFromString<T>(rawJson)
                    Result.success(parsed)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            val ex = attempt.exceptionOrNull()
            if (ex != null) {
                lastError = ex
                if (ex.message?.contains("(404)") == true) {
                    continue
                } else {
                    return Result.failure(ex)
                }
            }
        }

        return Result.failure(lastError)
    }

    private fun executeSingleModelCallRaw(
        cleanKey: String,
        modelName: String,
        prompt: String,
        bitmap: Bitmap?
    ): Result<String> {
        return try {
            val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent")
            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", cleanKey)
                connectTimeout = 25000
                readTimeout = 25000
                doOutput = true
            }

            val partsArray = buildJsonArray {
                add(buildJsonObject { put("text", prompt) })
                if (bitmap != null) {
                    val base64Image = encodeBitmapToBase64(bitmap)
                    add(buildJsonObject {
                        put("inlineData", buildJsonObject {
                            put("mimeType", "image/jpeg")
                            put("data", base64Image)
                        })
                    })
                }
            }

            val requestPayload = buildJsonObject {
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", partsArray)
                    })
                })
                put("generationConfig", buildJsonObject {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.2)
                })
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestPayload.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            val rawResponse = if (responseCode in 200..299) {
                connection.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "HTTP $responseCode with empty error body"
            }

            if (responseCode !in 200..299) {
                return Result.failure(
                    RuntimeException("Gemini API Error ($responseCode) on $modelName:$rawResponse")
                )
            }

            val rootObj = json.parseToJsonElement(rawResponse).jsonObject
            val candidateParts = rootObj["candidates"]
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")
                ?.jsonObject?.get("parts")
                ?.jsonArray
                ?: throw IllegalStateException("Empty response from Gemini model ($modelName).")

            val candidateText = candidateParts
                .mapNotNull { it.jsonObject }
                .firstOrNull { it["thought"]?.jsonPrimitive?.booleanOrNull != true && it["text"] != null }
                ?.get("text")?.jsonPrimitive?.content
                ?: candidateParts.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: throw IllegalStateException("No text part found in Gemini response.")

            val cleanedJson = candidateText
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            Result.success(cleanedJson)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val maxDimension = 1024
        val ratio = minOf(
            maxDimension.toFloat() / bitmap.width,
            maxDimension.toFloat() / bitmap.height,
            1.0f
        )
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }
}
