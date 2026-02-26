package de.chennemann.opencode.mobile.di

interface CoroutineRolloutFlag {
    val useMigratedExecution: Boolean
}

class DefaultCoroutineRolloutFlag : CoroutineRolloutFlag {
    override val useMigratedExecution: Boolean = !legacyOverride()

    private fun legacyOverride(): Boolean {
        val value = System.getProperty(LegacyExecutionProperty)
            ?: System.getenv(LegacyExecutionEnv)
            ?: return false
        return value == "1" || value.equals("true", ignoreCase = true)
    }
}

const val LegacyExecutionProperty = "opencode.mobile.coroutines.legacy"
const val LegacyExecutionEnv = "OPENCODE_MOBILE_COROUTINES_LEGACY"
