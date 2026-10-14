package de.chennemann.agentic.domain.attachments

import de.chennemann.agentic.t3.contract.T3Json
import de.chennemann.agentic.t3.contract.UploadChatAttachment
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * An ellipse drawn on an image with a comment. Bounds are fractions of the original image
 * (origin top-left), independent of display size. Mirrors T3 `ImageAnnotation`.
 */
@Serializable
data class ImageAnnotation(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val comment: String = "",
)

data class AnnotationPoint(val x: Float, val y: Float)

/** Where a mark's numbered label sits, in image pixels. */
data class AnnotationLabel(val radius: Float, val x: Float, val y: Float)

private fun Float.clamp(min: Float = 0f, max: Float = 1f) = coerceIn(min, max)

const val MinAnnotationSize = 0.005f
const val MaxImageAnnotations = 100

/** Normalized rectangle spanned by two drag points, kept inside the image. */
fun annotationRect(start: AnnotationPoint, end: AnnotationPoint): AnnotationBounds {
    val x = minOf(start.x, end.x).clamp(0f, 1f - MinAnnotationSize)
    val y = minOf(start.y, end.y).clamp(0f, 1f - MinAnnotationSize)
    return AnnotationBounds(
        x = x,
        y = y,
        width = maxOf(MinAnnotationSize, maxOf(start.x, end.x).clamp() - x),
        height = maxOf(MinAnnotationSize, maxOf(start.y, end.y).clamp() - y),
    )
}

data class AnnotationBounds(val x: Float, val y: Float, val width: Float, val height: Float)

fun ImageAnnotation.withBounds(bounds: AnnotationBounds) =
    copy(x = bounds.x, y = bounds.y, width = bounds.width, height = bounds.height)

/** A tap places a default-sized ellipse centred on the touch point. */
fun tapAnnotationBounds(point: AnnotationPoint): AnnotationBounds = annotationRect(
    AnnotationPoint((point.x - 0.1f).clamp(), (point.y - 0.04f).clamp()),
    AnnotationPoint((point.x + 0.1f).clamp(), (point.y + 0.04f).clamp()),
)

fun ImageAnnotation.moved(delta: AnnotationPoint) = copy(
    x = (x + delta.x).clamp(0f, 1f - width),
    y = (y + delta.y).clamp(0f, 1f - height),
)

/** Drags the bottom-right corner; the top-left corner stays anchored. */
fun ImageAnnotation.resized(corner: AnnotationPoint) =
    withBounds(annotationRect(AnnotationPoint(x, y), corner))

fun annotationLabel(mark: ImageAnnotation, imageWidth: Float, imageHeight: Float): AnnotationLabel {
    val shortSide = minOf(imageWidth, imageHeight)
    val radius = minOf(maxOf(14f, shortSide * 0.03f), shortSide / 2f)
    return AnnotationLabel(
        radius = radius,
        x = ((mark.x + mark.width) * imageWidth).coerceIn(radius, imageWidth - radius),
        // Leave the resize corner exposed beneath the numbered indicator.
        y = (mark.y * imageHeight - radius * 1.6f).coerceIn(radius, imageHeight - radius),
    )
}

fun newImageAnnotation(bounds: AnnotationBounds) = ImageAnnotation(
    id = UUID.randomUUID().toString(),
    x = bounds.x,
    y = bounds.y,
    width = bounds.width,
    height = bounds.height,
)

/** Adds saved comments to the outgoing text, grouped by image name, without changing the draft. */
fun appendImageAnnotationComments(prompt: String, images: List<DraftImageAttachment>): String {
    val blocks = images.mapNotNull { image ->
        val comments = image.annotations.mapIndexedNotNull { index, mark ->
            mark.comment.trim().takeIf { it.isNotEmpty() }?.let { "${index + 1}. $it" }
        }
        comments.takeIf { it.isNotEmpty() }?.let { "annotations for ${image.upload.name}:\n${it.joinToString("\n")}" }
    }
    return (listOf(prompt) + blocks).filter { it.isNotEmpty() }.joinToString("\n\n")
}

/**
 * An image waiting in the composer. Editable marks live beside the original bytes; the leading
 * [burnedAnnotationCount] marks are already flattened into the pixels (annotating a sent image).
 */
data class DraftImageAttachment(
    val id: String,
    val upload: UploadChatAttachment,
    val annotations: List<ImageAnnotation> = emptyList(),
    val burnedAnnotationCount: Int = 0,
) {
    val hasUnburnedAnnotations: Boolean get() = annotations.size > burnedAnnotationCount
}

/** Produces a send-only copy with marks burned into the pixels, numbered from [numberOffset] + 1. */
fun interface AnnotatedImageRenderer {
    suspend fun render(
        image: UploadChatAttachment,
        annotations: List<ImageAnnotation>,
        numberOffset: Int,
    ): UploadChatAttachment
}

data class PreparedTurnMessage(
    val text: String,
    val attachments: List<UploadChatAttachment>,
    val context: JsonObject?,
)

/**
 * Turns composer images into wire attachments: annotated images are re-rendered with their marks,
 * comments are appended to the prompt, and context records bind the marks to each attachment so
 * the provider also receives their exact bounds.
 */
suspend fun prepareTurnMessage(
    prompt: String,
    images: List<DraftImageAttachment>,
    renderer: AnnotatedImageRenderer,
): PreparedTurnMessage {
    val attachments = images.map { image ->
        val upload = if (image.hasUnburnedAnnotations) {
            renderer.render(
                image.upload,
                image.annotations.drop(image.burnedAnnotationCount),
                image.burnedAnnotationCount,
            )
        } else {
            image.upload
        }
        upload.copy(id = image.id)
    }
    val records = images.zip(attachments).filter { (image, _) -> image.annotations.isNotEmpty() }
        .map { (image, upload) -> imageContextRecord(image, upload) }
    return PreparedTurnMessage(
        text = appendImageAnnotationComments(prompt, images),
        attachments = attachments,
        context = records.takeIf { it.isNotEmpty() }?.let {
            buildJsonObject {
                put("version", 1)
                put("records", JsonArray(it))
            }
        },
    )
}

private fun imageContextRecord(image: DraftImageAttachment, upload: UploadChatAttachment) = buildJsonObject {
    put("version", 1)
    put("contextId", "image-${image.id}".filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(128))
    put("label", upload.name.take(200))
    put("kind", "image")
    put("attachmentId", image.id)
    put("name", upload.name.take(255))
    put("mimeType", upload.mimeType)
    put("sizeBytes", upload.sizeBytes)
    put("annotations", T3Json.encodeToJsonElement(ListSerializer(ImageAnnotation.serializer()), image.annotations))
    // Every mark is flattened into the uploaded copy's pixels.
    put("burnedAnnotationCount", image.annotations.size)
}

/** Saved annotations for a sent image, keyed by attachment id, read from a message's context. */
data class SentImageAnnotations(val annotations: List<ImageAnnotation>, val burnedAnnotationCount: Int)

fun messageImageAnnotations(context: JsonObject?): Map<String, SentImageAnnotations> {
    val records = runCatching { context?.get("records")?.jsonArray }.getOrNull() ?: return emptyMap()
    return records.mapNotNull { element ->
        val record = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        if ((record["kind"] as? JsonPrimitive)?.contentOrNull != "image") return@mapNotNull null
        val attachmentId = (record["attachmentId"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val annotations = runCatching {
            record["annotations"]?.jsonArray?.map { T3Json.decodeFromJsonElement<ImageAnnotation>(it) }
        }.getOrNull().orEmpty()
        if (annotations.isEmpty()) return@mapNotNull null
        val burned = (record["burnedAnnotationCount"] as? JsonPrimitive)?.intOrNull ?: 0
        attachmentId to SentImageAnnotations(annotations, burned.coerceIn(0, annotations.size))
    }.toMap()
}
