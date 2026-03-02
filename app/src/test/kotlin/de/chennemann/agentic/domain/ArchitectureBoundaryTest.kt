package de.chennemann.agentic.domain

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchitectureBoundaryTest {
    @Test
    fun domainDoesNotImportDataOrAndroid() {
        val root = Path.of("src", "main", "kotlin", "de", "chennemann", "agentic", "domain")
        Files.walk(root).use { paths ->
            val imports = paths
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .flatMap { file -> Files.readAllLines(file).stream() }
                .filter { it.startsWith("import ") }
                .collect(Collectors.toList())
            assertFalse(imports.any { it.contains("de.chennemann.agentic.data.") })
            assertFalse(imports.any { it.contains("de.chennemann.agentic.ui.") })
            assertFalse(imports.any { it.contains("de.chennemann.agentic.navigation.") })
            assertFalse(imports.any { it.contains("android.") })
        }
    }

    @Test
    fun featureCodeDoesNotUseDispatchersDirectly() {
        val root = Path.of("src", "main", "kotlin", "de", "chennemann", "agentic")
        val infra = Path.of("src", "main", "kotlin", "de", "chennemann", "agentic", "di", "DispatcherProvider.kt")
        Files.walk(root).use { paths ->
            val violations = paths
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { it != infra }
                .flatMap { file ->
                    Files.readAllLines(file)
                        .mapIndexedNotNull { index, line ->
                            if (line.contains("Dispatchers.")) {
                                "${file.toString().replace('\\', '/')}#L${index + 1}"
                            } else {
                                null
                            }
                        }
                        .stream()
                }
                .collect(Collectors.toList())
            assertTrue(
                violations.isEmpty(),
                "Direct Dispatchers usage must stay in dispatcher infrastructure: ${violations.joinToString()}"
            )
        }
    }
}
