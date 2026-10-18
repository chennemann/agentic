package de.chennemann.agentic.domain.orchestration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Mirrors T3's markdownLinks.test.ts table so Agentic recognizes the same file links as the other clients. */
class WorkspaceFileLinksTest {
    @Test
    fun `recognizes file links`() {
        listOf(
            "/Users/julius/project/AGENTS.md" to "/Users/julius/project/AGENTS.md",
            "/home/me/notes.md" to "/home/me/notes.md",
            "/usr/local/bin/tool" to "/usr/local/bin/tool",
            "/workspace/Makefile" to "/workspace/Makefile",
            "/tmp/favicons/" to "/tmp/favicons/",
            "C:\\Users\\mike\\project\\src\\main.ts" to "C:\\Users\\mike\\project\\src\\main.ts",
            "C:%5Crepo%5Cimage.png" to "C:\\repo\\image.png",
            "\\\\server\\share\\image.png" to "\\\\server\\share\\image.png",
            "/D:/Programme/t3code/OpenInPicker.tsx" to "D:/Programme/t3code/OpenInPicker.tsx",
            "</D:/Programme/t3code/ChatMarkdown.tsx:1>" to "D:/Programme/t3code/ChatMarkdown.tsx",
            "file:///Users/julius/project/file%2520name.md" to "/Users/julius/project/file%20name.md",
            "file://server/share/workspace-image.svg" to "\\\\server\\share\\workspace-image.svg",
            "file://localhost/home/me/notes.md" to "/home/me/notes.md",
            "file:///D:/Programme/t3code/OpenInPicker.tsx#L69" to "D:/Programme/t3code/OpenInPicker.tsx",
            "apps/mobile/src/index.ts:10" to "apps/mobile/src/index.ts",
            "docs/My%20Folder/checklist.xml" to "docs/My Folder/checklist.xml",
            "Updated%20cutover%20checklist.md" to "Updated cutover checklist.md",
            "./scripts/deploy" to "./scripts/deploy",
            "~/notes/today.md" to "~/notes/today.md",
            "AGENTS.md" to "AGENTS.md",
            "script.ts:10" to "script.ts",
            "/tmp/clip%23one.mp4#t=2" to "/tmp/clip#one.mp4",
        ).forEach { (href, path) -> assertEquals(path, parseMarkdownFileLink(href)?.path, href) }
    }

    @Test
    fun `rejects links that are not files`() {
        listOf(
            "", "#anchor", "//cdn.example.com/clip.mp4", "https://example.com/docs", "mailto:someone@example.com",
            "javascript:alert(1)", "/chat/settings", "/chat/settings#L3", "/app#L1", "readme", "TODO:12", "file://%",
        ).forEach { href -> assertNull(parseMarkdownFileLink(href), href) }
    }

    @Test
    fun `accepts conventional extensionless names with or without a position`() {
        assertEquals(FileLinkTarget("Makefile"), parseMarkdownFileLink("Makefile"))
        assertEquals(FileLinkTarget("Dockerfile", line = 8), parseMarkdownFileLink("Dockerfile:8"))
        assertEquals(FileLinkTarget("/srv/app/Makefile"), parseMarkdownFileLink("/srv/app/Makefile"))
    }

    @Test
    fun `reads positions from suffixes and line anchors`() {
        assertEquals(
            FileLinkTarget("/Users/julius/project/src/main.ts", line = 42, column = 7),
            parseMarkdownFileLink("/Users/julius/project/src/main.ts#L42C7"),
        )
        assertEquals(
            FileLinkTarget("\\\\server\\share\\src\\main.ts", line = 42, column = 7),
            parseMarkdownFileLink("file://server/share/src/main.ts#L42C7"),
        )
        assertEquals(FileLinkTarget("src/main.ts", line = 12, column = 5), splitFilePathPosition("src/main.ts:12:5"))
        assertEquals(FileLinkTarget("src/main.ts", line = 18, column = 2), splitFilePathPosition("src/main.ts", "#L18C2"))
        assertEquals(FileLinkTarget("src/main.ts", line = 3), splitFilePathPosition("src/main.ts:3", "#L18C2"))
        assertEquals(FileLinkTarget("src/main.ts"), splitFilePathPosition("src/main.ts:0"))
        assertEquals(FileLinkTarget("src/main.ts"), splitFilePathPosition("src/main.ts", "#section"))
    }

    @Test
    fun `relates paths to the workspace root`() {
        listOf(
            Triple("/repo/project", "/repo/project", "."),
            Triple("/repo/project/", "/repo/project/", "."),
            Triple("/", "/", "."),
            Triple("C:/USERS/mike/project", "c:/users/MIKE/project", "."),
            Triple("C:/", "c:/", "."),
            Triple("/repo/project/src/main.ts", "/repo/project", "src/main.ts"),
            Triple("/repo/project/src/main.ts", "/repo/project/", "src/main.ts"),
            Triple("C:\\Users\\mike\\t3code\\apps\\web\\a.ts", "C:/Users/mike/t3code", "apps/web/a.ts"),
            Triple("/C:/Users/mike/t3code/apps/web/a.ts", "C:/Users/mike/t3code", "apps/web/a.ts"),
            Triple("/Repo/Project/src/main.ts", "/repo/project", null),
            Triple("/tmp/case/project/probe.txt", "/tmp/case/Project", null),
            Triple("//tmp/case/project/probe.txt", "//tmp/case/Project", null),
            Triple("/tmp/case/Project/probe.txt", "/tmp/case/Project", "probe.txt"),
            Triple("C:/USERS/mike/t3code/main.ts", "c:/users/MIKE/t3code", "main.ts"),
            Triple("/C:/USERS/mike/t3code/main.ts", "/c:/users/MIKE/t3code", "main.ts"),
            Triple("\\\\server\\share\\PROJECT\\main.ts", "\\\\Server\\Share\\Project", "main.ts"),
            Triple("/tmp/repo/file.ts", "/", "tmp/repo/file.ts"),
            Triple("C:/Users/MIKE/main.ts", "c:/", "Users/MIKE/main.ts"),
            Triple("\\\\server\\SHARE\\file.ts", "\\\\Server\\Share\\", "file.ts"),
            Triple("/tmp/repo/file.ts ", "/tmp/repo", "file.ts "),
            Triple("/tmp/report.ts", "/repo/project", null),
            Triple("/repo/project-two/a.ts", "/repo/project", null),
            Triple("/repo/project/a.ts", null, null),
        ).forEach { (path, root, relative) -> assertEquals(relative, workspaceRelativeFilePath(path, root), "$path in $root") }
    }

    @Test
    fun `resolves linked paths to safe workspace paths`() {
        assertEquals("src/App.kt", workspacePath("/repo/src/App.kt", "/repo"))
        assertEquals("src/App.kt", workspacePath("./src/App.kt", "/repo"))
        assertEquals("src/App.kt", workspacePath("src\\App.kt", "C:\\repo"))
        assertEquals("apps/a.ts", workspacePath("C:\\Repo\\apps\\a.ts", "c:\\repo"))
        listOf("/etc/hosts", "~/notes.md", "../secret.txt", "/repo").forEach { path ->
            assertThrows(IllegalArgumentException::class.java, { workspacePath(path, "/repo") }, path)
        }
    }
}
