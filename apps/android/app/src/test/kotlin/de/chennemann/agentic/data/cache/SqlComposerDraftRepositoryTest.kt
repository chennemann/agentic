package de.chennemann.agentic.data.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.chennemann.agentic.db.AgenticDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SqlComposerDraftRepositoryTest {
    @Test
    fun `large paste bytes live in private files while SQLite stores only small metadata`() = runTest {
        val directory = java.nio.file.Files.createTempDirectory("composer-payload-test").toFile()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            AgenticDb.Schema.create(driver)
            val db = AgenticDb(driver)
            val repository =
                SqlComposerDraftRepository(db, StandardTestDispatcher(testScheduler), ComposerPastePayloads(directory))
            val file = de.chennemann.agentic.domain.composer.pastedTextFile("x".repeat(3 * 1024 * 1024), emptyList())
            val doc = de.chennemann.agentic.domain.composer.ComposerDocument(
                de.chennemann.agentic.domain.composer.composerContextReference("file", file.id, file.name),
                listOf(de.chennemann.agentic.domain.composer.pastedTextRecord(file)),
                listOf(file)
            )
            repository.setDocument("env", "thread", doc)
            org.junit.jupiter.api.Assertions.assertTrue(
                db.agenticT3Queries.selectComposerDraftPayloads().executeAsOne().length < 1500
            )
            assertEquals(file.text, repository.observeDocument("env", "thread").first().pastes.single().text)
            assertEquals(1, directory.listFiles()!!.size)
            repository.setDraft("env", "thread", "")
            assertEquals(0, directory.listFiles()!!.size)
        } finally {
            driver.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `pasted text and context survive a draft reload without keeping deleted payloads`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            AgenticDb.Schema.create(driver)
            val db = AgenticDb(driver)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val file = de.chennemann.agentic.domain.composer.pastedTextFile("large text".repeat(5000), emptyList())
            val doc = de.chennemann.agentic.domain.composer.ComposerDocument(
                de.chennemann.agentic.domain.composer.composerContextReference("file", file.id, file.name),
                listOf(de.chennemann.agentic.domain.composer.pastedTextRecord(file)),
                listOf(file)
            )
            SqlComposerDraftRepository(db, dispatcher).setDocument("env", "thread", doc)
            val recreated = SqlComposerDraftRepository(db, dispatcher)
            assertEquals(doc, recreated.observeDocument("env", "thread").first())
            recreated.setDraft("env", "thread", doc.text + " continue")
            assertEquals(doc.copy(text = doc.text + " continue"), recreated.observeDocument("env", "thread").first())
            recreated.setDocument("env", "thread", doc.copy(text = "removed"))
            assertEquals(
                de.chennemann.agentic.domain.composer.ComposerDocument("removed"),
                recreated.observeDocument("env", "thread").first()
            )
            db.agenticT3Queries.upsertPreference("env", "composer-draft-v1:legacy", "\"legacy draft\"")
            assertEquals("legacy draft", recreated.observe("env", "legacy").first())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `drafts survive repository recreation and remain scoped by environment and thread`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgenticDb.Schema.create(driver)
        val database = AgenticDb(driver)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = SqlComposerDraftRepository(database, dispatcher)

        repository.setDraft("environment-a", "thread-1", "first draft")
        repository.setDraft("environment-a", "thread-2", "second draft")
        repository.setDraft("environment-b", "thread-1", "other environment")

        val recreated = SqlComposerDraftRepository(database, dispatcher)
        assertEquals("first draft", recreated.observe("environment-a", "thread-1").first())
        assertEquals("second draft", recreated.observe("environment-a", "thread-2").first())
        assertEquals("other environment", recreated.observe("environment-b", "thread-1").first())

        recreated.setDraft("environment-a", "thread-1", "")
        assertEquals("", recreated.observe("environment-a", "thread-1").first())
        driver.close()
    }
}
