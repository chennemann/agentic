package de.chennemann.agentic.data

import android.content.Context
import de.chennemann.agentic.domain.connection.ClientInstallationId
import java.util.UUID

class AndroidClientInstallationId(context: Context) : ClientInstallationId {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    override fun value(): String = preferences.getString(InstallationIdKey, null)
        ?: UUID.randomUUID().toString().also { generated ->
            preferences.edit().putString(InstallationIdKey, generated).apply()
        }

    private companion object {
        const val PreferencesName = "client-installation"
        const val InstallationIdKey = "id"
    }
}
