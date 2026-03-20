package de.chennemann.agentic.api.infrastructure

import java.math.BigDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive

object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(JsonPrimitive(value))
            return
        }
        encoder.encodeString(value.toPlainString())
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        val value = if (decoder is JsonDecoder) {
            val primitive = decoder.decodeJsonElement() as? JsonPrimitive
                ?: throw SerializationException("Expected a JSON primitive for BigDecimal")
            primitive.content
        } else {
            decoder.decodeString()
        }

        return value.toBigDecimalOrNull()
            ?: throw SerializationException("Invalid BigDecimal value: $value")
    }
}
