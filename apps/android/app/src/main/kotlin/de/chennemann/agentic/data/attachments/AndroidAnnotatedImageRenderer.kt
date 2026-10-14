package de.chennemann.agentic.data.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Base64
import de.chennemann.agentic.domain.attachments.AnnotatedImageRenderer
import de.chennemann.agentic.domain.attachments.ImageAnnotation
import de.chennemann.agentic.domain.attachments.MaxImageAttachmentBytes
import de.chennemann.agentic.domain.attachments.annotationLabel
import de.chennemann.agentic.t3.contract.UploadChatAttachment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

const val AnnotationColor = 0xFFFF454F.toInt()

/** Burns marks into a send-only copy; the draft keeps its original pixels and editable marks. */
class AndroidAnnotatedImageRenderer(
    private val dispatcher: CoroutineDispatcher,
) : AnnotatedImageRenderer {
    override suspend fun render(
        image: UploadChatAttachment,
        annotations: List<ImageAnnotation>,
        numberOffset: Int,
    ): UploadChatAttachment = withContext(dispatcher) {
        val bytes = Base64.decode(image.dataUrl.substringAfter(','), Base64.DEFAULT)
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("'${image.name}' could not be decoded for annotation.")
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        if (bitmap !== source) source.recycle()
        try {
            drawAnnotations(Canvas(bitmap), bitmap.width.toFloat(), bitmap.height.toFloat(), annotations, numberOffset)
            val (encoded, mimeType) = encodeWithinLimit(bitmap)
            val baseName = image.name.substringBeforeLast('.', image.name)
            val extension = if (mimeType == "image/png") "png" else "jpg"
            UploadChatAttachment(
                id = image.id,
                name = "$baseName-annotated.$extension".take(255),
                mimeType = mimeType,
                sizeBytes = encoded.size.toLong(),
                dataUrl = "data:$mimeType;base64,${Base64.encodeToString(encoded, Base64.NO_WRAP)}",
            )
        } finally {
            bitmap.recycle()
        }
    }

    /** PNG first; fall back to JPEG and then downscaling when the copy exceeds the image limit. */
    private fun encodeWithinLimit(bitmap: Bitmap): Pair<ByteArray, String> {
        val png = bitmap.encode(Bitmap.CompressFormat.PNG, 100)
        if (png.size <= MaxImageAttachmentBytes) return png to "image/png"
        var current = bitmap
        repeat(6) {
            val jpeg = current.encode(Bitmap.CompressFormat.JPEG, 88)
            if (jpeg.size <= MaxImageAttachmentBytes) {
                if (current !== bitmap) current.recycle()
                return jpeg to "image/jpeg"
            }
            val scaled = Bitmap.createScaledBitmap(current, (current.width * 0.75f).toInt().coerceAtLeast(1), (current.height * 0.75f).toInt().coerceAtLeast(1), true)
            if (current !== bitmap) current.recycle()
            current = scaled
        }
        error("The annotated image exceeds the 10 MiB image limit.")
    }

    private fun Bitmap.encode(format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().use { stream ->
            compress(format, quality, stream)
            stream.toByteArray()
        }
}

/** Shared drawing so the burned copy matches the on-screen overlay. */
fun drawAnnotations(canvas: Canvas, width: Float, height: Float, annotations: List<ImageAnnotation>, numberOffset: Int) {
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AnnotationColor
        style = Paint.Style.STROKE
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AnnotationColor
        style = Paint.Style.FILL
    }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    annotations.forEachIndexed { index, mark ->
        val label = annotationLabel(mark, width, height)
        stroke.strokeWidth = maxOf(3f, label.radius / 5f)
        canvas.drawOval(
            RectF(mark.x * width, mark.y * height, (mark.x + mark.width) * width, (mark.y + mark.height) * height),
            stroke,
        )
        canvas.drawCircle(label.x, label.y, label.radius, fill)
        text.textSize = label.radius * 1.2f
        val baseline = label.y - (text.descent() + text.ascent()) / 2f
        canvas.drawText((numberOffset + index + 1).toString(), label.x, baseline, text)
    }
}
