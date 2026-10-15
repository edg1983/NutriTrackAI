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
                    geminiService = geminiService,
                    openFoodFactsService = openFoodFactsService,
                    onAiEstimateReady = { estimate ->
                        pendingReviewMeal = estimate
                    },
                    onError = { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                )

                2 -> WeeklyReportScreen(appState = appState)
            }
        }
    }

    pendingReviewMeal?.let { draft ->
        ReviewAndAdjustDialog(
            initialEstimate = draft,
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
    geminiService: GeminiNutritionService,
    openFoodFactsService: OpenFoodFactsService,
    onAiEstimateReady: (AiMealEstimate) -> Unit,
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
        val grams = consumedGramsInput.toFloatOrNull()?.coerceIn(1f, 5000f) ?: 100f
        isAnalyzing = true
        scope.launch {
            val result = openFoodFactsService.lookupBarcode(barcodeValue, grams)
            isAnalyzing = false
            result.onSuccess { estimate ->
                onAiEstimateReady(estimate)
            }.onFailure { err ->
                onError(err.localizedMessage ?: "Failed to look up barcode $barcodeValue")
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

        // Method 3: ML Kit Barcode Scanner + OpenFoodFacts Lookup
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Method 3: Scan Barcode (OpenFoodFacts)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Scan any packaged food EAN/UPC barcode with ML Kit to fetch verified nutrition per 100g and scale to your portion.",
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
}

@Composable
fun ReviewAndAdjustDialog(
    initialEstimate: AiMealEstimate,
    onDismiss: () -> Unit,
    onConfirmSave: (MealEntry) -> Unit
) {
    var name by remember { mutableStateOf(initialEstimate.mealName) }
    var quantity by remember { mutableStateOf(initialEstimate.estimatedQuantity) }
    var ingredientsText by remember { mutableStateOf(initialEstimate.ingredients.joinToString(", ")) }
    var calories by remember { mutableStateOf(initialEstimate.calories.toString()) }
    var protein by remember { mutableStateOf(initialEstimate.proteinGrams.toString()) }
    var carbs by remember { mutableStateOf(initialEstimate.carbsGrams.toString()) }
    var fat by remember { mutableStateOf(initialEstimate.fatGrams.toString()) }
    var fiber by remember { mutableStateOf(initialEstimate.fiberGrams.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review & Adjust AI Estimate", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Verify or tweak the detected values before logging:",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Meal Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it },
                    label = { Text("Quantity (Grams / Portions)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ingredientsText,
                    onValueChange = { ingredientsText = it },
                    label = { Text("Detected Ingredients (comma-separated)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = calories,
                        onValueChange = { calories = it },
                        label = { Text("Calories (kcal)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = protein,
                        onValueChange = { protein = it },
                        label = { Text("Protein (g)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = carbs,
                        onValueChange = { carbs = it },
                        label = { Text("Carbs (g)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = fat,
                        onValueChange = { fat = it },
                        label = { Text("Fat (g)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = fiber,
                        onValueChange = { fiber = it },
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
                        quantityDescription = quantity.ifBlank { "1 portion" },
                        ingredients = ingredientsText.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        calories = calories.toIntOrNull() ?: 0,
                        proteinGrams = protein.toFloatOrNull() ?: 0f,
                        carbsGrams = carbs.toFloatOrNull() ?: 0f,
                        fatGrams = fat.toFloatOrNull() ?: 0f,
                        fiberGrams = fiber.toFloatOrNull() ?: 0f,
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
    onSave: (Int, Int, String) -> Unit
) {
    var calGoal by remember { mutableStateOf(currentState.dailyCalorieGoal.toString()) }
    var waterGoal by remember { mutableStateOf(currentState.dailyWaterGoalMl.toString()) }
    var apiKey by remember { mutableStateOf(currentState.customApiKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Goals & Gemini API Settings", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    label = { Text("Gemini API Key (if not in local.properties)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
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
