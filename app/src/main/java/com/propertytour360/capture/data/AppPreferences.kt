package com.propertytour360.capture.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.propertytour360.capture.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "propertytour_settings")

class AppPreferences(private val context: Context) {
    private val backendUrlKey = stringPreferencesKey("backend_url")
    private val tokenKey = stringPreferencesKey("auth_token")

    val backendUrl: Flow<String> = context.dataStore.data.map { preferences ->
        normalizeBaseUrl(preferences[backendUrlKey] ?: BuildConfig.DEFAULT_BACKEND_URL)
    }

    val token: Flow<String?> = context.dataStore.data.map { it[tokenKey] }

    suspend fun setBackendUrl(value: String) {
        context.dataStore.edit { it[backendUrlKey] = normalizeBaseUrl(value) }
    }

    suspend fun setToken(value: String?) {
        context.dataStore.edit {
            if (value.isNullOrBlank()) it.remove(tokenKey) else it[tokenKey] = value
        }
    }

    companion object {
        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim()
            if (trimmed.isBlank()) return BuildConfig.DEFAULT_BACKEND_URL
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
            return if (withScheme.endsWith('/')) withScheme else "$withScheme/"
        }
    }
}
