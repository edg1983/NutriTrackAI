package com.example.nutritrack

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = StorageRepository(applicationContext)
        val geminiService = GeminiNutritionService()
        val openFoodFactsService = OpenFoodFactsService()

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1B6B50),
                    secondary = Color(0xFF2E8B57),
                    tertiary = Color(0xFF0288D1),
                    background = Color(0xFFF6F8F7),
                    surface = Color.White
                )
            ) {
                NutriTrackApp(
                    repository = repository,
                    geminiService = geminiService,
                    openFoodFactsService = openFoodFactsService
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutriTrackApp(
    repository: StorageRepository,
    geminiService: GeminiNutritionService,
    openFoodFactsService: OpenFoodFactsService
) {
    val context = LocalContext.current

    var appState by remember { mutableStateOf(repository.loadState()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showGoalSettingsDialog by remember { mutableStateOf(false) }

    // Draft Meal for "Review & Adjust" Modal
    var pendingReviewMeal by remember { mutableStateOf<AiMealEstimate?>(null) }

    fun updateState(transform: (AppStateData) -> AppStateData) {
        val updated = transform(appState)
        appState = updated
        repository.saveState(updated)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("NutriTrack AI", fontWeight = FontWeight.Bold)
                        Text(
                            "Smart Calorie, Macro & Hydration Coach",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showGoalSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Goals & API Key")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                    label = { Text("Today") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.AddAPhoto, contentDescription = null) },
                    label = { Text("Log Meal") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Insights, contentDescription = null) },
                    label = { Text("Weekly Report") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> TodayDashboardScreen(
                    appState = appState,
                    onAddWater = { deltaMl ->
                        val todayKey = LocalDate.now().toString()
                        val current = appState.waterLogs[todayKey] ?: 0
                        val next = (current + deltaMl).coerceAtLeast(0)
                        updateState {
                            it.copy(waterLogs = it.waterLogs + (todayKey to next))
                        }
                    },
                    onDeleteMeal = { mealId ->
                        updateState {
                            it.copy(meals = it.meals.filterNot { m -> m.id == mealId })
                        }
                    },
                    onOpenGoalDialog = { showGoalSettingsDialog = true }
                )

                1 -> LogMealScreen(
                    apiKey = appState.customApiKey,
                    previousMeals = appState.meals,
                    customBarcodes = appState.customBarcodes,
                    geminiService = geminiService,
                    openFoodFactsService = openFoodFactsService,
                    onAiEstimateReady = { estimate ->
                        pendingReviewMeal = estimate
                    },
                    onSaveCustomBarcode = { newCustomBarcode ->
                        updateState {
                            it.copy(customBarcodes = it.customBarcodes + newCustomBarcode)
                        }
                        Toast.makeText(context, "Saved '${newCustomBarcode.name}' to local barcode database!", Toast.LENGTH_SHORT).show()
                    },
                    onError = { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                )

                2 -> WeeklyReportScreen(appState = appState)
            }
        }
    }

    // Activity result launchers for JSON Backup & Restore
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                val backupJson = repository.exportBackupJson(appState)
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(backupJson.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Backup exported successfully!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val jsonText = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                } ?: ""
                val result = repository.importBackupJson(jsonText)
                result.onSuccess { restoredState ->
                    updateState { restoredState }
                    Toast.makeText(
                        context,
                        "Restored ${restoredState.meals.size} meals and ${restoredState.customBarcodes.size} custom barcodes!",
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { err ->
                    Toast.makeText(context, "Invalid backup JSON file: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Import failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    pendingReviewMeal?.let { draft ->
        ReviewAndAdjustDialog(
            initialEstimate = draft,
            apiKey = appState.customApiKey,
            geminiService = geminiService,
            onDismiss = { pendingReviewMeal = null },
            onConfirmSave = { finalEntry ->
                updateState { it.copy(meals = listOf(finalEntry) + it.meals) }
                pendingReviewMeal = null
                selectedTab = 0
                Toast.makeText(context, "Meal saved to Today's Log!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showGoalSettingsDialog) {
        GoalAndApiSettingsDialog(
            currentState = appState,
            onDismiss = { showGoalSettingsDialog = false },
            onSave = { newCalGoal, newWaterGoal, newApiKey ->
                updateState {
                    it.copy(
                        dailyCalorieGoal = newCalGoal,
                        dailyWaterGoalMl = newWaterGoal,
                        customApiKey = newApiKey
                    )
                }
                showGoalSettingsDialog = false
            },
            onExportBackup = {
                exportLauncher.launch("NutriTrack_Backup_${LocalDate.now()}.json")
            },
            onImportBackup = {
                importLauncher.launch(arrayOf("application/json", "*/*"))
            }
        )
    }
}

@Composable
fun TodayDashboardScreen(
    appState: AppStateData,
    onAddWater: (Int) -> Unit,
    onDeleteMeal: (String) -> Unit,
    onOpenGoalDialog: () -> Unit
) {
    val today = LocalDate.now()
    val summary = remember(appState) {
        NutritionAnalyzer.summarizeDay(today, appState.meals, appState.waterLogs)
    }
    val todayMeals = remember(appState) {
        appState.meals.filter { it.dateIso == today.toString() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Daily Calorie Budget",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${summary.calories} / ${appState.dailyCalorieGoal} kcal",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        TextButton(onClick = onOpenGoalDialog) {
                            Text("Edit Goal")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    val progress = (summary.calories.toFloat() / appState.dailyCalorieGoal.coerceAtLeast(1))
                        .coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        color = if (summary.calories > appState.dailyCalorieGoal) Color(0xFFD32F2F)
                        else MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MacroPill("Protein", "${summary.proteinG.toInt()}g", Color(0xFF1E88E5))
                        MacroPill("Carbs", "${summary.carbsG.toInt()}g", Color(0xFFFB8C00))
                        MacroPill("Fat", "${summary.fatG.toInt()}g", Color(0xFFE53935))
                        MacroPill("Fiber", "${summary.fiberG.toInt()}g", Color(0xFF43A047))
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.WaterDrop,
                                contentDescription = null,
                                tint = Color(0xFF0288D1)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Hydration Tracker",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            "${summary.waterMl} / ${appState.dailyWaterGoalMl} ml",
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0288D1)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = {
                            (summary.waterMl.toFloat() / appState.dailyWaterGoalMl.coerceAtLeast(1)).coerceIn(0f, 1f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = Color(0xFF0288D1)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onAddWater(250) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("+250ml Glass")
                        }
                        OutlinedButton(
                            onClick = { onAddWater(500) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("+500ml Bottle")
                        }
                        IconButton(onClick = { onAddWater(-250) }) {
                            Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Subtract 250ml")
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Today's Meals (${todayMeals.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (todayMeals.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        "No meals logged today yet. Tap 'Log Meal' below to enter a meal by text, snap a photo, or scan a barcode!",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(todayMeals, key = { it.id }) { meal ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(meal.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(
                                    "Quantity: ${meal.quantityDescription}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "${meal.calories} kcal",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 18.sp
                            )
                            IconButton(onClick = { onDeleteMeal(meal.id) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove meal")
                            }
                        }

                        if (meal.ingredients.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Ingredients: ${meal.ingredients.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "P: ${meal.proteinGrams.toInt()}g  •  C: ${meal.carbsGrams.toInt()}g  •  F: ${meal.fatGrams.toInt()}g  •  Fiber: ${meal.fiberGrams.toInt()}g",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )

                        if (meal.aiNotes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "💡 ${meal.aiNotes}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MacroPill(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Surface(
            color = color.copy(alpha = 0.15f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text(
                text = value,
                color = color,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun LogMealScreen(
    apiKey: String,
    previousMeals: List<MealEntry>,
    customBarcodes: List<CustomBarcodeItem>,
    geminiService: GeminiNutritionService,
    openFoodFactsService: OpenFoodFactsService,
    onAiEstimateReady: (AiMealEstimate) -> Unit,
    onSaveCustomBarcode: (CustomBarcodeItem) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mealText by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("1 portion (approx 300g)") }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var photoHint by remember { mutableStateOf("") }

    var consumedGramsInput by remember { mutableStateOf("100") }
    var manualBarcodeInput by remember { mutableStateOf("") }
    var lastScannedBarcode by remember { mutableStateOf<String?>(null) }

    var isAnalyzing by remember { mutableStateOf(false) }
    var pendingCustomBarcodeModal by remember { mutableStateOf<String?>(null) }

    val barcodeScanner = remember(context) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_QR_CODE
            )
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options)
    }

    fun fetchBarcodeNutrition(barcodeValue: String) {
        val cleanBarcode = barcodeValue.trim()
        if (cleanBarcode.isBlank()) {
            onError("Please enter or scan a valid barcode.")
            return
        }
        val grams = consumedGramsInput.toFloatOrNull()?.coerceIn(1f, 5000f) ?: 100f

        // 1. Search local custom barcode database first
        val localMatch = customBarcodes.find { it.barcode.equals(cleanBarcode, ignoreCase = true) }
        if (localMatch != null) {
            onAiEstimateReady(localMatch.toAiMealEstimate(grams))
            return
        }

        // 2. Search OpenFoodFacts
        isAnalyzing = true
        scope.launch {
            val result = openFoodFactsService.lookupBarcode(cleanBarcode, grams)
            isAnalyzing = false
            result.onSuccess { estimate ->
                onAiEstimateReady(estimate)
            }.onFailure {
                // Not found in OpenFoodFacts -> Prompt user to create a custom entry!
                pendingCustomBarcodeModal = cleanBarcode
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) capturedBitmap = bitmap
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) cameraLauncher.launch()
        else onError("Camera permission is required to capture meal photos.")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Section 0: Quick Log from History & Search
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            )
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Quick Log from History",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Quickly re-log recent meals or search past entries to adjust quantity and log:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                var searchQuery by remember { mutableStateOf("") }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    label = { Text("Search previous meals...") },
                    placeholder = { Text("e.g. Oats, Chicken Salad, Pasta...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                val filteredMeals = remember(previousMeals, searchQuery) {
                    val sorted = previousMeals.sortedByDescending { it.timestamp }
                    val distinct = mutableListOf<MealEntry>()
                    val seen = mutableSetOf<String>()
                    for (m in sorted) {
                        val key = m.name.trim().lowercase()
                        if (seen.add(key)) {
                            distinct.add(m)
                        }
                    }
                    if (searchQuery.isBlank()) {
                        distinct.take(10) // Top 10 most recent distinct meals
                    } else {
                        distinct.filter {
                            it.name.contains(searchQuery, ignoreCase = true) ||
                            it.ingredients.any { ing -> ing.contains(searchQuery, ignoreCase = true) }
                        }
                    }
                }

                if (previousMeals.isEmpty()) {
                    Text(
                        "No previous meals logged yet. Log a meal below to build your history!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else if (filteredMeals.isEmpty()) {
                    Text(
                        "No previous meals found matching '$searchQuery'.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    if (searchQuery.isBlank()) {
                        Text(
                            "Recent Meals (tap to re-log):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(filteredMeals) { meal ->
                                Card(
                                    onClick = {
                                        onAiEstimateReady(
                                            AiMealEstimate(
                                                mealName = meal.name,
                                                estimatedQuantity = meal.quantityDescription,
                                                ingredients = meal.ingredients.ifEmpty { listOf(meal.name) },
                                                calories = meal.calories,
                                                proteinGrams = meal.proteinGrams,
                                                carbsGrams = meal.carbsGrams,
                                                fatGrams = meal.fatGrams,
                                                fiberGrams = meal.fiberGrams,
                                                healthTip = "Re-logged from history (${meal.quantityDescription}). Adjust quantity if needed."
                                            )
                                        )
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            meal.name,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            "${meal.calories} kcal • ${meal.quantityDescription}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            "P: ${meal.proteinGrams.toInt()}g | C: ${meal.carbsGrams.toInt()}g | F: ${meal.fatGrams.toInt()}g",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            filteredMeals.take(5).forEach { meal ->
                                Card(
                                    onClick = {
                                        onAiEstimateReady(
                                            AiMealEstimate(
                                                mealName = meal.name,
                                                estimatedQuantity = meal.quantityDescription,
                                                ingredients = meal.ingredients.ifEmpty { listOf(meal.name) },
                                                calories = meal.calories,
                                                proteinGrams = meal.proteinGrams,
                                                carbsGrams = meal.carbsGrams,
                                                fatGrams = meal.fatGrams,
                                                fiberGrams = meal.fiberGrams,
                                                healthTip = "Re-logged from history (${meal.quantityDescription}). Adjust quantity if needed."
                                            )
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                meal.name,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                "${meal.calories} kcal • ${meal.quantityDescription}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = "Log Meal",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Method 1: Manual Text + Quantity Input with LLM
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.EditNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Method 1: Describe Meal & Quantity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = mealText,
                    onValueChange = { mealText = it },
                    label = { Text("Meal name or description") },
                    placeholder = { Text("e.g., Grilled salmon with quinoa and broccoli") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text("Quantity (in grams or portions)") },
                    placeholder = { Text("e.g., 350 grams or 1.5 portions") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        if (mealText.isBlank()) {
                            onError("Please enter a meal description first.")
                            return@Button
                        }
                        isAnalyzing = true
                        scope.launch {
                            val res = geminiService.analyzeTextMeal(apiKey, mealText, quantityText)
                            isAnalyzing = false
                            res.onSuccess { onAiEstimateReady(it) }
                                .onFailure { onError(it.localizedMessage ?: "AI analysis failed") }
                        }
                    },
                    enabled = !isAnalyzing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isAnalyzing) "Analyzing..." else "Analyze Meal with LLM")
                }
            }
        }

        // Method 2: Photo Capture + AI Vision Recognition
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Method 2: Snap Meal Photo (AI Vision)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                capturedBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Captured Meal",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                OutlinedTextField(
                    value = photoHint,
                    onValueChange = { photoHint = it },
                    label = { Text("Optional context (e.g., 'half portion, olive oil')") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) cameraLauncher.launch()
                            else permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (capturedBitmap == null) "Take Photo" else "Retake")
                    }

                    Button(
                        onClick = {
                            val bmp = capturedBitmap ?: return@Button
                            isAnalyzing = true
                            scope.launch {
                                val res = geminiService.analyzePhotoMeal(apiKey, bmp, photoHint)
                                isAnalyzing = false
                                res.onSuccess { onAiEstimateReady(it) }
                                    .onFailure { onError(it.localizedMessage ?: "Vision analysis failed") }
                            }
                        },
                        enabled = capturedBitmap != null && !isAnalyzing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isAnalyzing) "Scanning..." else "Estimate Nutrients")
                    }
                }
            }
        }

        // Method 3: ML Kit Barcode Scanner + OpenFoodFacts / Local DB Lookup
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Method 3: Scan Barcode (OpenFoodFacts & Local DB)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Scan any food barcode. Searches OpenFoodFacts and your local custom database, scaling 100g values to your portion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = consumedGramsInput,
                    onValueChange = { consumedGramsInput = it },
                    label = { Text("Consumed Portion Weight (grams)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        barcodeScanner.startScan()
                            .addOnSuccessListener { barcode ->
                                val rawValue = barcode.rawValue
                                if (!rawValue.isNullOrBlank()) {
                                    lastScannedBarcode = rawValue
                                    manualBarcodeInput = rawValue
                                    fetchBarcodeNutrition(rawValue)
                                } else {
                                    onError("Could not read barcode digits.")
                                }
                            }
                            .addOnFailureListener { e ->
                                onError("Barcode scan canceled or failed: ${e.localizedMessage}")
                            }
                    },
                    enabled = !isAnalyzing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isAnalyzing) "Looking up Product..." else "Scan Product Barcode")
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualBarcodeInput,
                        onValueChange = { manualBarcodeInput = it },
                        label = { Text("Or enter EAN/UPC barcode") },
                        placeholder = { Text("e.g., 8076800195057") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { fetchBarcodeNutrition(manualBarcodeInput) },
                        enabled = manualBarcodeInput.isNotBlank() && !isAnalyzing
                    ) {
                        Text("Lookup")
                    }
                }

                lastScannedBarcode?.let { code ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Last scanned code: $code",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    pendingCustomBarcodeModal?.let { unknownBarcode ->
        CreateCustomBarcodeDialog(
            barcode = unknownBarcode,
            defaultGrams = consumedGramsInput.toFloatOrNull()?.coerceIn(1f, 5000f) ?: 100f,
            onDismiss = { pendingCustomBarcodeModal = null },
            onSave = { newItem ->
                onSaveCustomBarcode(newItem)
                val grams = consumedGramsInput.toFloatOrNull()?.coerceIn(1f, 5000f) ?: 100f
                onAiEstimateReady(newItem.toAiMealEstimate(grams))
                pendingCustomBarcodeModal = null
            }
        )
    }
}

@Composable
fun ReviewAndAdjustDialog(
    initialEstimate: AiMealEstimate,
    apiKey: String,
    geminiService: GeminiNutritionService,
    onDismiss: () -> Unit,
    onConfirmSave: (MealEntry) -> Unit
) {
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initialEstimate.mealName) }
    var quantityText by remember { mutableStateOf(initialEstimate.estimatedQuantity) }
    var ingredientsText by remember { mutableStateOf(initialEstimate.ingredients.joinToString(", ")) }

    val baseCalories = remember(initialEstimate) { initialEstimate.calories }
    val baseProtein = remember(initialEstimate) { initialEstimate.proteinGrams }
    val baseCarbs = remember(initialEstimate) { initialEstimate.carbsGrams }
    val baseFat = remember(initialEstimate) { initialEstimate.fatGrams }
    val baseFiber = remember(initialEstimate) { initialEstimate.fiberGrams }

    var portionMultiplier by remember { mutableFloatStateOf(1.0f) }
    var componentsList by remember { mutableStateOf(initialEstimate.components) }

    var caloriesStr by remember { mutableStateOf(initialEstimate.calories.toString()) }
    var proteinStr by remember { mutableStateOf(initialEstimate.proteinGrams.toString()) }
    var carbsStr by remember { mutableStateOf(initialEstimate.carbsGrams.toString()) }
    var fatStr by remember { mutableStateOf(initialEstimate.fatGrams.toString()) }
    var fiberStr by remember { mutableStateOf(initialEstimate.fiberGrams.toString()) }

    var editingComponentIndex by remember { mutableStateOf<Int?>(null) }
    var componentAdjustPrompt by remember { mutableStateOf("") }
    var isAnalyzingComponent by remember { mutableStateOf(false) }

    fun applyScale(scale: Float) {
        portionMultiplier = scale.coerceIn(0.1f, 10.0f)

        if (componentsList.isNotEmpty()) {
            val scaledComponents = initialEstimate.components.map { comp ->
                comp.copy(
                    calories = kotlin.math.round(comp.calories * scale).toInt(),
                    proteinGrams = kotlin.math.round(comp.proteinGrams * scale * 10f) / 10f,
                    carbsGrams = kotlin.math.round(comp.carbsGrams * scale * 10f) / 10f,
                    fatGrams = kotlin.math.round(comp.fatGrams * scale * 10f) / 10f,
                    fiberGrams = kotlin.math.round(comp.fiberGrams * scale * 10f) / 10f
                )
            }
            componentsList = scaledComponents
        }

        val scaledCal = kotlin.math.round(baseCalories * scale).toInt()
        val scaledProt = kotlin.math.round(baseProtein * scale * 10f) / 10f
        val scaledCarbs = kotlin.math.round(baseCarbs * scale * 10f) / 10f
        val scaledFat = kotlin.math.round(baseFat * scale * 10f) / 10f
        val scaledFiber = kotlin.math.round(baseFiber * scale * 10f) / 10f

        caloriesStr = scaledCal.toString()
        proteinStr = scaledProt.toString()
        carbsStr = scaledCarbs.toString()
        fatStr = scaledFat.toString()
        fiberStr = scaledFiber.toString()
    }

    fun recalculateFromComponents() {
        if (componentsList.isNotEmpty()) {
            val totalCal = componentsList.sumOf { it.calories }
            val totalProt = kotlin.math.round(componentsList.sumOf { it.proteinGrams.toDouble() } * 10) / 10f
            val totalCarbs = kotlin.math.round(componentsList.sumOf { it.carbsGrams.toDouble() } * 10) / 10f
            val totalFat = kotlin.math.round(componentsList.sumOf { it.fatGrams.toDouble() } * 10) / 10f
            val totalFiber = kotlin.math.round(componentsList.sumOf { it.fiberGrams.toDouble() } * 10) / 10f

            caloriesStr = totalCal.toString()
            proteinStr = totalProt.toFloat().toString()
            carbsStr = totalCarbs.toFloat().toString()
            fatStr = totalFat.toFloat().toString()
            fiberStr = totalFiber.toFloat().toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review & Adjust AI Estimate", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Meal Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Adjust Portion Scale:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { scale ->
                                FilterChip(
                                    selected = kotlin.math.abs(portionMultiplier - scale) < 0.05f,
                                    onClick = {
                                        applyScale(scale)
                                        quantityText = if (scale == 1.0f) initialEstimate.estimatedQuantity else "${scale}x (${initialEstimate.estimatedQuantity})"
                                    },
                                    label = { Text("${scale}x", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = quantityText,
                            onValueChange = { quantityText = it },
                            label = { Text("Quantity Description") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }

                if (componentsList.isNotEmpty()) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Detected Components (${componentsList.size}):",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Tap to adjust with AI",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            componentsList.forEachIndexed { index, comp ->
                                Card(
                                    onClick = {
                                        editingComponentIndex = index
                                        componentAdjustPrompt = comp.quantityDescription
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(comp.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                            Text("${comp.quantityDescription} • ${comp.calories} kcal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text("P: ${comp.proteinGrams}g | C: ${comp.carbsGrams}g | F: ${comp.fatGrams}g", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                        IconButton(onClick = {
                                            editingComponentIndex = index
                                            componentAdjustPrompt = comp.quantityDescription
                                        }) {
                                            Icon(Icons.Default.AutoAwesome, contentDescription = "Adjust Component", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = ingredientsText,
                    onValueChange = { ingredientsText = it },
                    label = { Text("Detected Ingredients (comma-separated)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = caloriesStr,
                        onValueChange = { caloriesStr = it },
                        label = { Text("Calories (kcal)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = proteinStr,
                        onValueChange = { proteinStr = it },
                        label = { Text("Protein (g)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = carbsStr,
                        onValueChange = { carbsStr = it },
                        label = { Text("Carbs (g)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = fatStr,
                        onValueChange = { fatStr = it },
                        label = { Text("Fat (g)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = fiberStr,
                        onValueChange = { fiberStr = it },
                        label = { Text("Fiber (g)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val entry = MealEntry(
                        name = name.ifBlank { "Logged Meal" },
                        quantityDescription = quantityText.ifBlank { "1 portion" },
                        ingredients = ingredientsText.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        calories = caloriesStr.toIntOrNull() ?: 0,
                        proteinGrams = proteinStr.toFloatOrNull() ?: 0f,
                        carbsGrams = carbsStr.toFloatOrNull() ?: 0f,
                        fatGrams = fatStr.toFloatOrNull() ?: 0f,
                        fiberGrams = fiberStr.toFloatOrNull() ?: 0f,
                        aiNotes = initialEstimate.healthTip
                    )
                    onConfirmSave(entry)
                }
            ) {
                Text("Save Meal")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    editingComponentIndex?.let { index ->
        val targetComp = componentsList.getOrNull(index) ?: return@let
        AlertDialog(
            onDismissRequest = { editingComponentIndex = null },
            title = { Text("Adjust '${targetComp.name}' with AI", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Describe your adjustment for '${targetComp.name}' (e.g., '180g steamed carrots with butter'):",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = componentAdjustPrompt,
                        onValueChange = { componentAdjustPrompt = it },
                        label = { Text("Component description / adjustment") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (componentAdjustPrompt.isBlank()) return@Button
                        isAnalyzingComponent = true
                        scope.launch {
                            val res = geminiService.analyzeComponentText(
                                apiKey = apiKey,
                                componentName = targetComp.name,
                                descriptionInput = componentAdjustPrompt
                            )
                            isAnalyzingComponent = false
                            res.onSuccess { updatedComp ->
                                val updatedList = componentsList.toMutableList()
                                updatedList[index] = updatedComp
                                componentsList = updatedList
                                recalculateFromComponents()
                                editingComponentIndex = null
                            }
                        }
                    },
                    enabled = !isAnalyzingComponent && componentAdjustPrompt.isNotBlank()
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isAnalyzingComponent) "Re-analyzing..." else "AI Re-Analyze")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingComponentIndex = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun WeeklyReportScreen(appState: AppStateData) {
    val report = remember(appState) {
        NutritionAnalyzer.generateWeeklyReport(appState)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Weekly Healthy Score",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Calculated from calorie goal adherence, macro balance, dietary fiber, and daily water intake.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${report.healthyScore}/100",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp
                        )
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        "7-Day Calorie Trend (vs ${appState.dailyCalorieGoal} kcal Goal)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    val maxCal = maxOf(
                        appState.dailyCalorieGoal,
                        report.dailySummaries.maxOfOrNull { it.second.calories } ?: 1
                    ).coerceAtLeast(1)

                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    ) {
                        val barWidth = size.width / (report.dailySummaries.size * 2f)
                        val goalY = size.height * (1f - (appState.dailyCalorieGoal.toFloat() / maxCal))

                        drawLine(
                            color = Color(0xFFFB8C00),
                            start = Offset(0f, goalY),
                            end = Offset(size.width, goalY),
                            strokeWidth = 4f
                        )

                        report.dailySummaries.forEachIndexed { index, (_, summary) ->
                            val ratio = (summary.calories.toFloat() / maxCal).coerceIn(0f, 1f)
                            val barHeight = size.height * ratio
                            val x = index * (size.width / report.dailySummaries.size) + barWidth / 2f
                            val barColor = if (summary.calories > appState.dailyCalorieGoal) {
                                Color(0xFFE53935)
                            } else {
                                Color(0xFF1B6B50)
                            }
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(x, size.height - barHeight),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(10f, 10f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        report.dailySummaries.forEach { (date, sum) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${sum.calories}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        "Daily Averages (Active Days)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MacroPill("Avg Cal", "${report.avgCalories}", Color(0xFF1B6B50))
                        MacroPill("Protein", "${report.avgProteinG.toInt()}g", Color(0xFF1E88E5))
                        MacroPill("Carbs", "${report.avgCarbsG.toInt()}g", Color(0xFFFB8C00))
                        MacroPill("Fat", "${report.avgFatG.toInt()}g", Color(0xFFE53935))
                        MacroPill("Water", "${report.avgWaterMl}ml", Color(0xFF0288D1))
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        "Nutrient Balance & Diet Adjustments",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    report.insights.forEach { insight ->
                        Row(
                            modifier = Modifier.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.CheckCircleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(insight, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GoalAndApiSettingsDialog(
    currentState: AppStateData,
    onDismiss: () -> Unit,
    onSave: (Int, Int, String) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    var calGoal by remember { mutableStateOf(currentState.dailyCalorieGoal.toString()) }
    var waterGoal by remember { mutableStateOf(currentState.dailyWaterGoalMl.toString()) }
    var apiKey by remember { mutableStateOf(currentState.customApiKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings & Backup", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("App Goals", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = calGoal,
                    onValueChange = { calGoal = it },
                    label = { Text("Daily Max Calorie Goal (kcal)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = waterGoal,
                    onValueChange = { waterGoal = it },
                    label = { Text("Daily Water Goal (ml)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Gemini API Key (optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                Text("Data Backup & Restore", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(
                    "Currently saved: ${currentState.meals.size} meals, ${currentState.customBarcodes.size} custom barcodes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onExportBackup,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Backup")
                    }
                    OutlinedButton(
                        onClick = onImportBackup,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Restore")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        calGoal.toIntOrNull()?.coerceIn(800, 10000) ?: 2000,
                        waterGoal.toIntOrNull()?.coerceIn(500, 10000) ?: 2500,
                        apiKey.trim()
                    )
                }
            ) {
                Text("Save Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun CreateCustomBarcodeDialog(
    barcode: String,
    defaultGrams: Float,
    onDismiss: () -> Unit,
    onSave: (CustomBarcodeItem) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var caloriesStr by remember { mutableStateOf("") }
    var proteinStr by remember { mutableStateOf("") }
    var carbsStr by remember { mutableStateOf("") }
    var fatStr by remember { mutableStateOf("") }
    var fiberStr by remember { mutableStateOf("") }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Unknown Barcode: $barcode", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Product not found in OpenFoodFacts. Enter 100g values below (will automatically scale to your ${defaultGrams.toInt()}g portion):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Food Title / Name *") },
                    placeholder = { Text("e.g. Protein Bar") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = brand,
                    onValueChange = { brand = it },
                    label = { Text("Brand Name (Optional)") },
                    placeholder = { Text("e.g. FitBrand") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text(
                    "Nutritional Values per 100g:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = caloriesStr,
                        onValueChange = { caloriesStr = it },
                        label = { Text("Calories (kcal) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = proteinStr,
                        onValueChange = { proteinStr = it },
                        label = { Text("Protein (g) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = carbsStr,
                        onValueChange = { carbsStr = it },
                        label = { Text("Carbs (g) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = fatStr,
                        onValueChange = { fatStr = it },
                        label = { Text("Fat (g) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = fiberStr,
                    onValueChange = { fiberStr = it },
                    label = { Text("Fiber (g) (Optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                errorMessage?.let { err ->
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isBlank()) {
                        errorMessage = "Please enter a food title."
                        return@Button
                    }
                    val cal = caloriesStr.toIntOrNull()
                    val prot = proteinStr.toFloatOrNull()
                    val carbs = carbsStr.toFloatOrNull()
                    val fat = fatStr.toFloatOrNull()
                    val fiber = fiberStr.toFloatOrNull() ?: 0f

                    if (cal == null || cal < 0 || prot == null || prot < 0f || carbs == null || carbs < 0f || fat == null || fat < 0f) {
                        errorMessage = "Please enter valid numbers for 100g calories and main macros."
                        return@Button
                    }

                    val newItem = CustomBarcodeItem(
                        barcode = barcode,
                        name = title.trim(),
                        brand = brand.trim(),
                        caloriesPer100g = cal,
                        proteinPer100g = prot,
                        carbsPer100g = carbs,
                        fatPer100g = fat,
                        fiberPer100g = fiber
                    )
                    onSave(newItem)
                }
            ) {
                Text("Save & Log")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
