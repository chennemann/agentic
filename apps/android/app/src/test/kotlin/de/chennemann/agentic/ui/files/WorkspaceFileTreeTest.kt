package de.chennemann.agentic.ui.files

import de.chennemann.agentic.domain.orchestration.WorkspaceFileEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkspaceFileTreeTest {
    private val tree = FileTree.from(
        listOf(
            dir("app"),
            file("app/build.gradle.kts"),
            dir("app/build"),
            file("app/build/outputs/app.apk"),
            file("app/src/main/kotlin/de/chennemann/agentic/App.kt"),
            file("app/src/main/kotlin/de/chennemann/agentic/build/BuildInfo.kt"),
            file("app/src/main/res/values/strings.xml"),
            file("app/src/test/kotlin/de/chennemann/agentic/AppTest.kt"),
            file("app/src/debug/AndroidManifest.xml"),
            file(".github/workflows/ci.yml"),
            file("README.md"),
        ),
    )

    @Test
    fun `production code is expanded with compact packages while tests, debug and build output stay out of the way`() {
        assertEquals(
            listOf(
                row(".github/workflows", ".github/workflows", 0, FileTreeCategory.Auxiliary),
                row("app", "app", 0),
                row("app/src", "src", 1),
                row("app/src/debug", "debug", 2, FileTreeCategory.Auxiliary),
                row("app/src/main", "main", 2),
                row("app/src/main/kotlin", "kotlin", 3),
                row("app/src/main/kotlin/de/chennemann/agentic", "de.chennemann.agentic", 4),
                row("app/src/main/kotlin/de/chennemann/agentic/build", "build", 5),
                file("app/src/main/kotlin/de/chennemann/agentic/build/BuildInfo.kt", "BuildInfo.kt", 6),
                file("app/src/main/kotlin/de/chennemann/agentic/App.kt", "App.kt", 5),
                row("app/src/main/res/values", "res/values", 3),
                file("app/src/main/res/values/strings.xml", "strings.xml", 4),
                row("app/src/test", "test", 2, FileTreeCategory.Test),
                file("app/build.gradle.kts", "build.gradle.kts", 1),
                file("README.md", "README.md", 0),
            ),
            tree.rows(),
        )
    }

    @Test
    fun `test directories expand on demand and keep their source root separate from packages`() {
        val expansion = FileTreeExpansion()
            .toggle("app/src/test")
            .toggle("app/src/test/kotlin")
            .toggle("app/src/test/kotlin/de/chennemann/agentic")

        assertEquals(
            listOf(
                row("app/src/test", "test", 2, FileTreeCategory.Test, expanded = true),
                row("app/src/test/kotlin", "kotlin", 3, FileTreeCategory.Test, expanded = true),
                row("app/src/test/kotlin/de/chennemann/agentic", "de.chennemann.agentic", 4, FileTreeCategory.Test, expanded = true),
                file("app/src/test/kotlin/de/chennemann/agentic/AppTest.kt", "AppTest.kt", 5, FileTreeCategory.Test),
            ),
            tree.rows(expansion).filter { it.key.startsWith("app/src/test") },
        )
    }

    @Test
    fun `build output is only listed on request`() {
        assertEquals(emptyList<FileTreeRow>(), tree.rows().filter { it.key.startsWith("app/build/") })
        assertEquals(
            listOf(
                row("app/build/outputs", "build/outputs", 1, FileTreeCategory.BuildOutput, expanded = true)
                    .copy(outputRoot = true),
                file("app/build/outputs/app.apk", "app.apk", 2, FileTreeCategory.BuildOutput),
            ),
            tree.rows(FileTreeExpansion().toggle("app/build/outputs"), showBuildOutput = true)
                .filter { it.key.startsWith("app/build/") },
        )
    }

    @Test
    fun `collapse all hides production children until a directory is toggled`() {
        val collapsed = FileTreeExpansion(productionExpanded = false)

        assertEquals(listOf(".github/workflows", "app", "README.md"), tree.rows(collapsed).map { it.key })
        assertEquals(
            listOf(".github/workflows", "app", "app/src", "app/build.gradle.kts", "README.md"),
            tree.rows(collapsed.toggle("app")).map { it.key },
        )
    }

    @Test
    fun `search shows matches with their ancestors expanded and excludes hidden build output`() {
        assertEquals(
            listOf(
                row("app/src/main", "app/src/main", 0),
                row("app/src/main/kotlin", "kotlin", 1),
                row("app/src/main/kotlin/de/chennemann/agentic", "de.chennemann.agentic", 2),
                file("app/src/main/kotlin/de/chennemann/agentic/App.kt", "App.kt", 3),
            ),
            tree.rows(query = "app.kt"),
        )
        assertEquals(emptyList<FileTreeRow>(), tree.rows(query = "app.apk"))
        assertEquals(
            listOf("app", "app/build/outputs", "app/build/outputs/app.apk"),
            tree.rows(showBuildOutput = true, query = "app.apk").map { it.key },
        )
    }

    @Test
    fun `categorizes colocated test files and tool output directories`() {
        assertEquals(FileTreeCategory.Test, categorize(listOf("src", "chat", "ChatView.test.ts"), isDirectory = false))
        assertEquals(FileTreeCategory.Test, categorize(listOf("pkg", "server_test.go"), isDirectory = false))
        assertEquals(FileTreeCategory.Test, categorize(listOf("shared", "src", "commonTest"), isDirectory = true))
        assertEquals(FileTreeCategory.Production, categorize(listOf("src", "chat", "ChatView.ts"), isDirectory = false))
        assertEquals(FileTreeCategory.Production, categorize(listOf("build.gradle.kts"), isDirectory = false))
        assertEquals(FileTreeCategory.BuildOutput, categorize(listOf("web", "node_modules", "react"), isDirectory = true))
        assertEquals(FileTreeCategory.BuildOutput, categorize(listOf(".gradle"), isDirectory = true))
    }
}

private fun dir(path: String) = WorkspaceFileEntry(path, isDirectory = true)

private fun file(path: String) = WorkspaceFileEntry(path, isDirectory = false)

private fun row(
    key: String,
    label: String,
    depth: Int,
    category: FileTreeCategory = FileTreeCategory.Production,
    expanded: Boolean = category == FileTreeCategory.Production,
) = FileTreeRow(key, label, depth, isDirectory = true, expanded = expanded, category = category)

private fun file(
    key: String,
    label: String,
    depth: Int,
    category: FileTreeCategory = FileTreeCategory.Production,
) = FileTreeRow(key, label, depth, isDirectory = false, expanded = false, category = category)
