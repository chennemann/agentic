package de.chennemann.agentic.domain.attachments

import de.chennemann.agentic.t3.contract.UploadChatAttachment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageAnnotationsTest {
    private val upload = UploadChatAttachment(name = "shot.png", mimeType = "image/png", sizeBytes = 3, dataUrl = "data:image/png;base64,AAA")
    private fun mark(id: String, comment: String) = ImageAnnotation(id, 0.1f, 0.2f, 0.3f, 0.4f, comment)

    @Test
    fun `drag rectangle is normalized and kept inside the image`() {
        val rect = annotationRect(AnnotationPoint(0.9f, 0.8f), AnnotationPoint(1.4f, 0.2f))
        assertEquals(AnnotationBounds(0.9f, 0.2f, 0.1f, 0.6f).x, rect.x, 1e-6f)
        assertEquals(0.2f, rect.y, 1e-6f)
        assertEquals(0.1f, rect.width, 1e-6f)
        assertEquals(0.6f, rect.height, 1e-6f)
    }

    @Test
    fun `moving clamps the mark to the image`() {
        val moved = mark("a", "").moved(AnnotationPoint(2f, -2f))
        assertEquals(0.7f, moved.x, 1e-6f)
        assertEquals(0f, moved.y, 1e-6f)
    }

    @Test
    fun `comments are appended by image name, skipping blank comments`() {
        val text = appendImageAnnotationComments(
            "Fix this",
            listOf(DraftImageAttachment("1", upload, listOf(mark("a", " Too wide "), mark("b", " "), mark("c", "Wrong color")))),
        )
        assertEquals("Fix this\n\nannotations for shot.png:\n1. Too wide\n3. Wrong color", text)
    }

    @Test
    fun `unannotated images are sent untouched without context`() = runBlocking {
        val prepared = prepareTurnMessage("hi", listOf(DraftImageAttachment("1", upload)), { _, _, _ -> error("not rendered") })
        assertEquals("hi", prepared.text)
        assertEquals("1", prepared.attachments.single().id)
        assertNull(prepared.context)
    }

    @Test
    fun `annotated images are rendered and bound to context records`() = runBlocking {
        var offsetSeen = -1
        val image = DraftImageAttachment("img-1", upload, listOf(mark("a", "old"), mark("b", "new")), burnedAnnotationCount = 1)
        val prepared = prepareTurnMessage("", listOf(image)) { source, marks, offset ->
            offsetSeen = offset
            assertEquals(listOf("b"), marks.map { it.id })
            source.copy(name = "shot-annotated.png", sizeBytes = 9)
        }
        assertEquals(1, offsetSeen)
        assertEquals("img-1", prepared.attachments.single().id)
        assertEquals("shot-annotated.png", prepared.attachments.single().name)
        val record = prepared.context!!["records"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("img-1"), record["attachmentId"])
        assertEquals(JsonPrimitive(2), record["burnedAnnotationCount"])
        assertTrue(prepared.text.startsWith("annotations for shot.png:"))
    }

    @Test
    fun `sent message context yields annotations by attachment`() = runBlocking {
        val image = DraftImageAttachment("img-1", upload, listOf(mark("a", "note")))
        val context = prepareTurnMessage("", listOf(image)) { source, _, _ -> source }.context
        val parsed = messageImageAnnotations(context)
        assertEquals(listOf("note"), parsed["img-1"]!!.annotations.map { it.comment })
        assertEquals(1, parsed["img-1"]!!.burnedAnnotationCount)
    }
}
