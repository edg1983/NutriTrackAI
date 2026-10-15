package com.example.nutritrack

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

@Serializable
data class MealEntry(
    val id: String = UUID.randomUUID().toString(),
    val dateIso: String = LocalDate.now().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val name: String,
    val quantityDescription: String,
    val ingredients: List<String>,
    val calories: Int,
    val proteinGrams: Float,
    val carbsGrams: Float,
    val fatGrams: Float,
    val fiberGrams: Float,
    val aiNotes: String = ""
)

@Serializable
data class DailyWaterLog(
    val dateIso: String,
    val milliliters: Int
)

@Serializable
data class CustomBarcodeItem(
    val barcode: String,
    val name: String,
    val caloriesPer100g: Int,
    val proteinPer100g: Float,
    val carbsPer100g: Float,
    val fatPer100g: Float,
    val fiberPer100g: Float = 0f,
    val brand: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toAiMealEstimate(consumedGrams: Float = 100f): AiMealEstimate {
        val scaleFactor = (consumedGrams / 100f).coerceAtLeast(0.01f)
        val fullName = if (brand.isNotBlank()) "$name ($brand)" else name
        return AiMealEstimate(
            mealName = fullName,
            estimatedQuantity = "${consumedGrams.toInt()}g (Local Barcode $barcode)",
            ingredients = listOf("Custom Barcode Entry ($barcode)"),
            calories = kotlin.math.round((caloriesPer100g * scaleFactor)).toInt(),
            proteinGrams = kotlin.math.round((proteinPer100g * scaleFactor * 10f)) / 10f,
            carbsGrams = kotlin.math.round((carbsPer100g * scaleFactor * 10f)) / 10f,
            fatGrams = kotlin.math.round((fatPer100g * scaleFactor * 10f)) / 10f,
            fiberGrams = kotlin.math.round((fiberPer100g * scaleFactor * 10f)) / 10f,
            healthTip = "Loaded from local custom barcode database ($barcode)."
        )
    }
}

@Serializable
data class AppStateData(
    val dailyCalorieGoal: Int = 2000,
    val dailyWaterGoalMl: Int = 2500,
    val meals: List<MealEntry> = emptyList(),
    val waterLogs: Map<String, Int> = emptyMap(),
    val customApiKey: String = "",
    val customBarcodes: List<CustomBarcodeItem> = emptyList()
)

data class NutrientSummary(
    val calories: Int,
    val proteinG: Float,
    val carbsG: Float,
    val fatG: Float,
    val fiberG: Float,
    val waterMl: Int
)

data class WeeklyReport(
    val dailySummaries: List<Pair<LocalDate, NutrientSummary>>,
    val avgCalories: Int,
    val avgProteinG: Float,
    val avgCarbsG: Float,
    val avgFatG: Float,
    val avgFiberG: Float,
    val avgWaterMl: Int,
    val healthyScore: Int,
    val insights: List<String>
)

object NutritionAnalyzer {

    fun summarizeDay(
        date: LocalDate,
        meals: List<MealEntry>,
        waterLogs: Map<String, Int>
    ): NutrientSummary {
        val key = date.toString()
        val dayMeals = meals.filter { it.dateIso == key }
        return NutrientSummary(
            calories = dayMeals.sumOf { it.calories },
            proteinG = dayMeals.map { it.proteinGrams }.sum(),
            carbsG = dayMeals.map { it.carbsGrams }.sum(),
            fatG = dayMeals.map { it.fatGrams }.sum(),
            fiberG = dayMeals.map { it.fiberGrams }.sum(),
            waterMl = waterLogs[key] ?: 0
        )
    }

    /**
     * Generates a 0-100 Healthy Score and actionable dietary adjustments based on:
     * 1. Calorie adherence to daily target (35 pts)
     * 2. Macronutrient balance: ~20-30% protein, ~45-55% carbs, ~20-30% fat (35 pts)
     * 3. Dietary fiber adequacy >= 25g/day (15 pts)
     * 4. Hydration consistency against water goal (15 pts)
     */
    fun generateWeeklyReport(state: AppStateData): WeeklyReport {
        val today = LocalDate.now()
        val last7Days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val dailyList = last7Days.map { date ->
            date to summarizeDay(date, state.meals, state.waterLogs)
        }

        val activeDays = dailyList.filter { it.second.calories > 0 || it.second.waterMl > 0 }
        val divisor = if (activeDays.isEmpty()) 1 else activeDays.size

        val avgCal = activeDays.sumOf { it.second.calories } / divisor
        val avgProt = activeDays.map { it.second.proteinG }.sum() / divisor
        val avgCarbs = activeDays.map { it.second.carbsG }.sum() / divisor
        val avgFat = activeDays.map { it.second.fatG }.sum() / divisor
        val avgFiber = activeDays.map { it.second.fiberG }.sum() / divisor
        val avgWater = activeDays.sumOf { it.second.waterMl } / divisor

        if (activeDays.isEmpty()) {
            return WeeklyReport(
                dailySummaries = dailyList,
                avgCalories = 0,
                avgProteinG = 0f,
                avgCarbsG = 0f,
                avgFatG = 0f,
                avgFiberG = 0f,
                avgWaterMl = 0,
                healthyScore = 0,
                insights = listOf("Log your first meal or glass of water to unlock personalized nutrition insights.")
            )
        }

        // 1. Calorie Score (up to 35 points)
        val calDiffRatio = kotlin.math.abs(avgCal - state.dailyCalorieGoal).toFloat() / state.dailyCalorieGoal.coerceAtLeast(1)
        val calScore = ((1f - calDiffRatio.coerceIn(0f, 1f)) * 35f).toInt()

        // 2. Macro Balance Score (up to 35 points)
        val totalMacroCals = (avgProt * 4f + avgCarbs * 4f + avgFat * 9f).coerceAtLeast(1f)
        val protPct = (avgProt * 4f / totalMacroCals) * 100f
        val fatPct = (avgFat * 9f / totalMacroCals) * 100f

        var macroScore = 35
        if (protPct < 18f) macroScore -= ((18f - protPct) * 1.2f).toInt()
        if (fatPct > 35f) macroScore -= ((fatPct - 35f) * 1.2f).toInt()
        macroScore = macroScore.coerceIn(5, 35)

        // 3. Fiber Score (up to 15 points, target 28g)
        val fiberScore = ((avgFiber / 28f).coerceIn(0f, 1f) * 15f).toInt()

        // 4. Water Score (up to 15 points)
        val waterScore = ((avgWater.toFloat() / state.dailyWaterGoalMl.coerceAtLeast(1)).coerceIn(0f, 1f) * 15f).toInt()

        val totalScore = (calScore + macroScore + fiberScore + waterScore).coerceIn(0, 100)

        // Actionable Insights Generation
        val insights = mutableListOf<String>()
        if (avgCal > state.dailyCalorieGoal * 1.1) {
            insights.add("Calorie Surplus: Your average intake ($avgCal kcal) is above your ${state.dailyCalorieGoal} kcal target. Try swapping calorie-dense sauces or fried sides for steamed greens.")
        } else if (avgCal < state.dailyCalorieGoal * 0.75) {
            insights.add("Low Energy Intake: You are averaging $avgCal kcal, well below your ${state.dailyCalorieGoal} kcal limit. Consider adding nutrient-dense snacks like Greek yogurt, nuts, or avocado.")
        } else {
            insights.add("On-Target Calories: You are staying within an optimal range of your ${state.dailyCalorieGoal} kcal daily goal.")
        }

        if (protPct < 20f) {
            insights.add("Boost Protein (${protPct.toInt()}% of calories): Increase lean protein sources (chicken breast, fish, tofu, lentils, or eggs) to support satiety and muscle maintenance.")
        } else {
            insights.add("Solid Protein Balance (${protPct.toInt()}% of calories): Your protein intake is well-proportioned.")
        }

        if (fatPct > 35f) {
            insights.add("High Dietary Fat (${fatPct.toInt()}% of calories): Reduce saturated fats and cooking oils; favor complex carbohydrates like quinoa, oats, and legumes.")
        }

        if (avgFiber < 22f) {
            insights.add("Low Fiber (${"%.1f".format(avgFiber)}g/day): Aim for 25–30g daily by adding berries, chia seeds, whole grains, or leafy vegetables to your meals.")
        }

        if (avgWater < state.dailyWaterGoalMl * 0.8) {
            insights.add("Hydration Gap: Averaging ${avgWater}ml vs your ${state.dailyWaterGoalMl}ml target. Drink a 250ml glass of water before each meal.")
        }

        return WeeklyReport(
            dailySummaries = dailyList,
            avgCalories = avgCal,
            avgProteinG = avgProt,
            avgCarbsG = avgCarbs,
            avgFatG = avgFat,
            avgFiberG = avgFiber,
            avgWaterMl = avgWater,
            healthyScore = totalScore,
            insights = insights
        )
    }
}
