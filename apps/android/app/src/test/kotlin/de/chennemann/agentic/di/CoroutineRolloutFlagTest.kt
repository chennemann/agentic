package de.chennemann.agentic.di

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoroutineRolloutFlagTest {
    @Test
    fun usesMigratedExecutionByDefault() {
        val previous = System.getProperty(LegacyExecutionProperty)
        System.clearProperty(LegacyExecutionProperty)

        try {
            assertTrue(DefaultCoroutineRolloutFlag().useMigratedExecution)
        } finally {
            if (previous == null) {
                System.clearProperty(LegacyExecutionProperty)
            } else {
                System.setProperty(LegacyExecutionProperty, previous)
            }
        }
    }

    @Test
    fun canEnableLegacyExecutionWithProperty() {
        val previous = System.getProperty(LegacyExecutionProperty)
        System.setProperty(LegacyExecutionProperty, "true")

        try {
            assertFalse(DefaultCoroutineRolloutFlag().useMigratedExecution)
        } finally {
            if (previous == null) {
                System.clearProperty(LegacyExecutionProperty)
            } else {
                System.setProperty(LegacyExecutionProperty, previous)
            }
        }
    }
}
