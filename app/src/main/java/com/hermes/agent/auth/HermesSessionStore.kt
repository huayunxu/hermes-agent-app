package com.hermes.agent.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hermes.agent.data.HermesSession

class HermesSessionStore(context: Context) {
    private val appContext = context.applicationContext

    private val preferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            "hermes-session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun load(): HermesSession? {
        val baseUrl = preferences.getString(KEY_BASE_URL, null)?.trim().orEmpty()
        val token = preferences.getString(KEY_ACCESS_TOKEN, null)?.trim().orEmpty()
        return if (baseUrl.isBlank() || token.isBlank()) {
            null
        } else {
            HermesSession(baseUrl = baseUrl, accessToken = token)
        }
    }

    fun save(session: HermesSession) {
        preferences.edit()
            .putString(KEY_BASE_URL, session.baseUrl.trim())
            .putString(KEY_ACCESS_TOKEN, session.accessToken.trim())
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base-url"
        const val KEY_ACCESS_TOKEN = "access-token"
    }
}

