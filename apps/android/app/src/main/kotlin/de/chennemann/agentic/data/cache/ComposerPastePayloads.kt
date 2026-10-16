package de.chennemann.agentic.data.cache

import de.chennemann.agentic.domain.composer.ComposerDocument
import de.chennemann.agentic.domain.composer.decodeComposerDocument
import java.io.File
import java.security.MessageDigest

/** Keep attachment bytes out of SQLite cursor windows; only relative content hashes are cached. */
class ComposerPastePayloads(private val directory: File) {
    private val storedNames = java.util.WeakHashMap<String, String>()

    fun store(document: ComposerDocument): ComposerDocument = document.copy(
        pastes = document.pastes.map { file ->
            if (file.text.isEmpty()) return@map file
            check(directory.isDirectory || directory.mkdirs()) { "Draft attachment storage is unavailable." }
            val existing = storedNames[file.text]
            if (existing != null &&
                File(directory, existing).isFile
            ) {
                return@map file.copy(text = "", payloadFile = existing)
            }
            val bytes = file.text.toByteArray(Charsets.UTF_8)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val name = "$hash.txt"
            val target = File(directory, name)
            if (!target.exists()) {
                val temporary = File.createTempFile("paste-", ".tmp", directory)
                try {
                    temporary.writeBytes(bytes)
                    check(temporary.renameTo(target) || target.exists()) { "The pasted text could not be saved." }
                } finally {
                    temporary.delete()
                }
            }
            storedNames[file.text] = name
            file.copy(text = "", payloadFile = name)
        }
    )

    fun restore(document: ComposerDocument): ComposerDocument = document.copy(
        pastes = document.pastes.map { file ->
            val name = file.payloadFile
            if (name == null || !PayloadName.matches(name)) {
                file
            } else {
                file.copy(text = runCatching { File(directory, name).readText(Charsets.UTF_8) }.getOrDefault(""))
            }
        }
    )

    fun prune(persistedDrafts: List<String>) {
        val retained = persistedDrafts.flatMap {
            decodeComposerDocument(it).pastes
        }.mapNotNull { it.payloadFile }.toSet()
        directory.listFiles()?.filter { PayloadName.matches(it.name) && it.name !in retained }?.forEach { it.delete() }
    }

    private companion object {
        val PayloadName = Regex("[a-f0-9]{64}\\.txt")
    }
}
