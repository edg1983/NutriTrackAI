# NutriTrack AI - Android Nutrition, Photo & Barcode Tracker

A complete, production-ready native Android application written in **Kotlin** and **Jetpack Compose (Material 3)**.

## Features Included
1. **Manual Meal & Portion LLM Analyzer:** Describe any dish and portion size (in grams or portions) to get an instant calorie, macronutrient, fiber, and ingredient breakdown via **Gemini 2.5 Flash**.
2. **AI Vision Meal Photo Recognition:** Snap a photo of your plate with your camera; the app identifies the dish, estimates the portion weight, lists ingredients, and computes nutrients.
3. **ML Kit Barcode Scanner + OpenFoodFacts API v2:** Scan any packaged food barcode (EAN-13, EAN-8, UPC-A, UPC-E) using Google Play Services Code Scanner and automatically scale the 100g reference nutrients to your consumed weight.
4. **Interactive Review & Adjust Modal:** Review and tweak any AI- or barcode-detected value before saving to your daily log.
5. **Daily Water Intake Tracker:** Log `+250ml` glasses or `+500ml` bottles against your daily hydration target.
6. **7-Day Weekly Report & Healthy Score (0–100):** Visual 7-day calorie trend bar chart, active-day macro averages, and personalized nutrient balance recommendations.

## Quick Start in Android Studio
1. Extract `NutriTrackAI.zip`.
2. Open **Android Studio** and select **Open**, then choose the extracted `NutriTrackAI` folder.
3. Wait for Android Studio to sync Gradle and download dependencies.
4. *(Optional)* Copy `local.properties.example` to `local.properties` and add `GEMINI_API_KEY=your_key_here`, **OR** simply set your Gemini API key directly inside the app's **Settings (gear icon)** dialog at runtime.
5. Connect your Android phone (with USB Debugging enabled) and click **Run ('app')**.
