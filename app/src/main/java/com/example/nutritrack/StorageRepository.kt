package com.example.nutritrack

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class StorageRepository(context: Context) {
    private val prefs = context.getSharedPreferences("nutritrack_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun loadState(): AppStateData {
        val raw = prefs.getString("app_state_json", null) ?: return AppStateData()
        return try {
            json.decodeFromString<AppStateData>(raw)
        } catch (_: Exception) {
            AppStateData()
        }
    }

    fun saveState(state: AppStateData) {
        val serialized = json.encodeToString(state)
        prefs.edit().putString("app_state_json", serialized).apply()
    }

    fun exportBackupJson(state: AppStateData = loadState()): String {
        return json.encodeToString(state)
    }

    fun importBackupJson(jsonContent: String): Result<AppStateData> {
        return try {
            val restoredState = json.decodeFromString<AppStateData>(jsonContent)
            saveState(restoredState)
            Result.success(restoredState)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
