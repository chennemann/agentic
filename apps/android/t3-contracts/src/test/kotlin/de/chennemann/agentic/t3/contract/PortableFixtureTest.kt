package de.chennemann.agentic.t3.contract

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortableFixtureTest {
    @Test
    fun `canonical client configuration decodes and pins protocol`() {
        val config = PortableJson.decodeFromString<EnvironmentClientConfig>(
            fixture("client-config.json")
        )

        assertEquals(T3_PORTABLE_PROTOCOL_VERSION, config.protocolVersion)
        assertEquals(T3_PORTABLE_PROTOCOL_VERSION, config.environment.capabilities.portableClientProtocol)
        assertEquals("provider-golden", config.providers.single().instanceId)
    }

    @Test
    fun `every canonical command decodes`() {
        val commands = PortableJson.decodeFromString(
            ListSerializer(ClientOrchestrationCommand.serializer()),
            fixture("commands.json")
        )

        assertEquals(10, commands.size)
        assertInstanceOf(ClientOrchestrationCommand.StartTurn::class.java, commands[0])
        assertInstanceOf(ClientOrchestrationCommand.RespondToApproval::class.java, commands[8])
        assertInstanceOf(ClientOrchestrationCommand.RespondToUserInput::class.java, commands[9])
    }

    @Test
    fun `every canonical shell stream variant decodes`() {
        val items = PortableJson.decodeFromString(
            ListSerializer(OrchestrationShellStreamItem.serializer()),
            fixture("shell-stream-items.json")
        )

        assertEquals(6, items.size)
        assertEquals(6, items.map { it::class }.distinct().size)
        assertInstanceOf(OrchestrationShellStreamItem.Synchronized::class.java, items.last())
    }

    @Test
    fun `canonical model option selections decode as ordered string and boolean entries`() {
        val selection = PortableJson.decodeFromString<ModelSelection>(
            """
            {
              "instanceId": "provider-golden",
              "model": "model-golden",
              "options": [
                {"id": "effort", "value": "high"},
                {"id": "fastMode", "value": true}
              ]
            }
            """.trimIndent()
        )

        assertEquals("effort", selection.options?.get(0)?.id)
        assertEquals("high", selection.options?.get(0)?.value?.content)
        assertEquals("fastMode", selection.options?.get(1)?.id)
        assertEquals(true, selection.options?.get(1)?.value?.boolean)
    }

    @Test
    fun `every canonical thread stream variant decodes and unknown activity stays intact`() {
        val items = PortableJson.decodeFromString(
            ListSerializer(OrchestrationThreadStreamItem.serializer()),
            fixture("thread-stream-items.json")
        )
        val snapshot = (items.first() as OrchestrationThreadStreamItem.Snapshot).snapshot
        val unknown = snapshot.thread.activities.single { it.kind == "future.additive-activity" }

        assertEquals(18, items.size)
        assertEquals(3, items.map { it::class }.distinct().size)
        assertEquals(1, unknown.payload["futureField"]?.jsonPrimitive?.content?.toInt())
        assertTrue(unknown.payload["nested"] is JsonObject)
    }

    @Test
    fun `vendored artifact manifest matches pinned provenance`() {
        val manifest = PortableJson.parseToJsonElement(
            resource("/t3-portable/v1/artifact-manifest.json")
        ).jsonObject
        val version = PortableJson.parseToJsonElement(
            resource("/t3-portable/v1/protocol-version.json")
        ).jsonObject

        assertEquals(T3_ARTIFACT_CHECKSUM, manifest.getValue("checksum").jsonPrimitive.content)
        assertEquals(T3_PORTABLE_PROTOCOL_NAME, version.getValue("protocol").jsonPrimitive.content)
        assertEquals(T3_PORTABLE_PROTOCOL_VERSION, version.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(13, manifest.getValue("files").jsonArray.size)
    }

    private fun fixture(name: String): String = resource("/t3-portable/v1/fixtures/$name")

    private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)) {
        "Missing vendored resource $path"
    }.readText()
}
