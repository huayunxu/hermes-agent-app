package com.hermes.agent.auth

import android.content.Context
import com.hermes.agent.data.HermesSession

class HermesSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "hermes-session",
        Context.MODE_PRIVATE
    )

    fun load(): HermesSession? {
        val lanUrl = preferences.getString(KEY_LAN_URL, null)?.trim().orEmpty()
        val wanUrl = preferences.getString(KEY_WAN_URL, null)?.trim().orEmpty()
        val selectedUrl = preferences.getString(KEY_SELECTED_URL, null)?.trim().orEmpty()
        val token = preferences.getString(KEY_ACCESS_TOKEN, null)?.trim().orEmpty()
        return if ((lanUrl.isBlank() && wanUrl.isBlank()) || token.isBlank()) {
            null
        } else {
            HermesSession(lanUrl = lanUrl, wanUrl = wanUrl, selectedUrl = selectedUrl, accessToken = token)
        }
    }

    fun save(session: HermesSession) {
        preferences.edit()
            .putString(KEY_LAN_URL, session.lanUrl.trim())
            .putString(KEY_WAN_URL, session.wanUrl.trim())
            .putString(KEY_SELECTED_URL, session.selectedUrl.trim())
            .putString(KEY_ACCESS_TOKEN, session.accessToken.trim())
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val KEY_LAN_URL = "lan-url"
        const val KEY_WAN_URL = "wan-url"
        const val KEY_SELECTED_URL = "selected-url"
        const val KEY_ACCESS_TOKEN = "access-token"
    }
}
