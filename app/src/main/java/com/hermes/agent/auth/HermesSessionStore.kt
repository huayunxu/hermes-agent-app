package com.hermes.agent.auth

import android.content.Context
import com.hermes.agent.data.HermesSession

class HermesSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "hermes-session",
        Context.MODE_PRIVATE
    )

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
