package de.chennemann.agentic.t3.contract

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class V2RpcRequest(val method: String, val payload: JsonObject)

/** Translate durable user intents to the current server command contract. */
object V2Commands {
    fun requests(command: ClientOrchestrationCommand, message: JsonObject? = null): List<V2RpcRequest> {
        val encoded = T3CommandJson.encodeToJsonElement(ClientOrchestrationCommand.serializer(), command).jsonObject
        val payload = encoded.toMutableMap()
        var method = "orchestration.dispatchCommand"
        when (command) {
            is ClientOrchestrationCommand.CreateProject, is ClientOrchestrationCommand.DeleteProject ->
                method =
                    "projects.mutate"
            is ClientOrchestrationCommand.UpdateProjectMetadata -> {
                method = "projects.mutate"
                payload["type"] = JsonPrimitive("project.update")
            }
            is ClientOrchestrationCommand.UpdateThreadMetadata -> {
                payload["type"] = JsonPrimitive("thread.metadata.update")
            }
            is ClientOrchestrationCommand.InterruptTurn -> {
                payload.remove("turnId")
                if (command.turnId == null) {
                    // Stops whatever the thread still runs, including delegated tasks, and holds its queue.
                    payload["type"] = JsonPrimitive("thread.stop")
                } else {
                    payload["type"] = JsonPrimitive("run.interrupt")
                    payload["runId"] = JsonPrimitive(command.turnId)
                    payload["holdQueue"] = JsonPrimitive(true)
                }
            }
            is ClientOrchestrationCommand.RespondToApproval, is ClientOrchestrationCommand.RespondToUserInput ->
                payload["type"] = JsonPrimitive("runtime-request.respond")
            is ClientOrchestrationCommand.StartTurn -> {
                val prepared = requireNotNull(message)
                val bootstrap = command.bootstrap
                if (bootstrap != null) {
                    return listOf(
                        V2RpcRequest(
                            "orchestration.launchThread",
                            buildJsonObject {
                                put("commandId", command.commandId)
                                put("creationSource", "mobile")
                                put("threadId", command.threadId)
                                put("projectId", bootstrap.createThread.projectId)
                                put("title", bootstrap.createThread.title)
                                put("generateTitle", true)
                                put("modelSelection", encoded.getValue("modelSelection"))
                                put("runtimeMode", command.runtimeMode)
                                put("interactionMode", command.interactionMode)
                                put(
                                    "workspaceStrategy",
                                    buildJsonObject {
                                        when {
                                            bootstrap.prepareWorktree != null -> {
                                                put("type", "worktree")
                                                put("baseRef", bootstrap.prepareWorktree.baseBranch)
                                                bootstrap.prepareWorktree.startFromOrigin?.let {
                                                    put("startFromOrigin", it)
                                                }
                                            }
                                            bootstrap.createThread.worktreePath != null -> {
                                                put("type", "existing_worktree")
                                                put("worktreePath", bootstrap.createThread.worktreePath)
                                                bootstrap.createThread.branch?.let { put("branch", it) }
                                            }
                                            else -> {
                                                put("type", "root")
                                                bootstrap.createThread.branch?.let { put("branch", it) }
                                            }
                                        }
                                    }
                                )
                                put("initialMessage", prepared)
                            }
                        )
                    )
                }
                val modes = listOf(
                    "runtimeMode" to "thread.runtime-mode.set",
                    "interactionMode" to "thread.interaction-mode.set"
                )
                    .map { (field, type) ->
                        V2RpcRequest(
                            method,
                            buildJsonObject {
                                put("type", type)
                                put("commandId", "${command.commandId}:$field")
                                put("threadId", command.threadId)
                                put(field, encoded.getValue(field))
                            }
                        )
                    }
                return modes + V2RpcRequest(
                    method,
                    buildJsonObject {
                        put("type", "message.dispatch")
                        put("createdBy", "user")
                        put("creationSource", "mobile")
                        put("commandId", command.commandId)
                        put("threadId", command.threadId)
                        prepared.forEach { (key, value) -> put(key, value) }
                        put("modelSelection", encoded.getValue("modelSelection"))
                        command.sourceProposedPlan?.let {
                            put("sourcePlanRef", T3Json.encodeToJsonElement(SourceProposedPlan.serializer(), it))
                        }
                        if (command.queue) {
                            put("dispatchMode", buildJsonObject { put("type", "queue_after_active") })
                        } else {
                            // Steering travels as "auto" so a run that ended meanwhile starts a new one.
                            put("deliveryIntent", "auto")
                            put("dispatchMode", buildJsonObject { put("type", "start_immediately") })
                        }
                    }
                )
            }
            else -> Unit
        }
        return listOf(V2RpcRequest(method, JsonObject(payload)))
    }

    fun message(
        command: ClientOrchestrationCommand.StartTurn,
        attachments: JsonArray,
        attachmentIds: Map<String, String>
    ): JsonObject = buildJsonObject {
        put("messageId", command.message.messageId)
        put("text", command.message.text)
        put("attachments", attachments)
        command.message.context?.let { context ->
            put(
                "context",
                JsonObject(
                    context.mapValues { (key, value) ->
                        if (key != "records" || value !is JsonArray) {
                            value
                        } else {
                            JsonArray(
                                value.map { record ->
                                    val obj = record.jsonObject
                                    val id = (obj["attachmentId"] as? JsonPrimitive)?.content
                                    attachmentIds[id]?.let { JsonObject(obj + ("attachmentId" to JsonPrimitive(it))) }
                                        ?: obj
                                }
                            )
                        }
                    }
                )
            )
        }
    }
}
