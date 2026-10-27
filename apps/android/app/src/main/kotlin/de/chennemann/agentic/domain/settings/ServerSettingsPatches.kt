package de.chennemann.agentic.domain.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

const val ThreadTitleInstructionsKey = "threadTitleInstructions"
private const val ProjectOverridesKey = "projectSettingsOverrides"

/** A setting with its environment value and, for a selected project, the project's own value when it sets one. */
data class ScopedSetting<T>(
    val global: T,
    val projectOverride: T?
) {
    val effective: T get() = projectOverride ?: global
}

/** Absent on servers that predate the setting, which would silently drop writes to it. */
fun JsonObject.supportsSetting(key: String): Boolean = containsKey(key)

/** Servers without per-project overrides do not report the map at all. */
val JsonObject.supportsProjectOverrides: Boolean get() = this[ProjectOverridesKey] is JsonObject

fun JsonObject.projectOverrides(projectId: String): JsonObject? =
    (this[ProjectOverridesKey] as? JsonObject)?.get(projectId) as? JsonObject

fun JsonObject.scopedText(key: String, projectId: String?): ScopedSetting<String> = ScopedSetting(
    global = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty(),
    projectOverride = projectId?.let { projectOverrides(it) }?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull }
)

fun globalSettingPatch(key: String, value: JsonElement): JsonObject = buildJsonObject {
    put("patch", buildJsonObject { put(key, value) })
}

/**
 * The server replaces a project's whole override set, so the patch carries every override the project already has.
 * A null [value] removes the key so the project inherits again; an emptied set is removed entirely.
 */
fun projectOverridePatch(settings: JsonObject, projectId: String, key: String, value: JsonElement?): JsonObject {
    val current = settings.projectOverrides(projectId).orEmpty()
    val next = if (value == null) current - key else current + (key to value)
    return buildJsonObject {
        put(
            "patch",
            buildJsonObject {
                put(
                    ProjectOverridesKey,
                    buildJsonObject { put(projectId, if (next.isEmpty()) JsonNull else JsonObject(next)) }
                )
            }
        )
    }
}

fun textSetting(value: String): JsonElement = JsonPrimitive(value.trim())
