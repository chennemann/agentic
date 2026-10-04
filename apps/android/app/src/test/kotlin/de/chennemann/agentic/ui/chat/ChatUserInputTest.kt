package de.chennemann.agentic.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatUserInputTest {
    @Test
    fun `contract choices preserve exact values and one multi selection stays an array`() {
        val payload = Json.parseToJsonElement("""{"questions":[{"id":"q","header":"Choose","question":"Which?","multiSelect":true,"allowCustomAnswer":false,"options":[{"label":"A","value":" a ","description":"First"}]}]}""").jsonObject
        val question = payload.inputQuestions(selectedOptions = { setOf(" a ") }, textAnswer = { "ignored" }).single()
        assertTrue(question.allowsMultiple)
        assertFalse(question.allowsCustomAnswer)
        assertEquals(JsonArray(listOf(JsonPrimitive(" a "))), question.resolvedAnswer())
    }

    @Test
    fun `custom answer wins and unknown or unanswered choices block submission`() {
        val question = UserInputQuestionUi("q", "Question", options = listOf(UserInputOptionUi("a", "A")))
        assertNull(listOf(question).resolvedAnswers())
        assertNull(question.copy(selectedOptionIds = setOf("missing")).resolvedAnswer())
        assertEquals(JsonPrimitive("custom"), question.copy(textAnswer = " custom ", selectedOptionIds = setOf("a")).resolvedAnswer())
        assertEquals(JsonPrimitive("a"), question.copy(selectedOptionIds = setOf("a")).resolvedAnswer())
    }
}
