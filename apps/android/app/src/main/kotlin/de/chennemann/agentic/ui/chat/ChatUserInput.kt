package de.chennemann.agentic.ui.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal fun JsonObject.inputQuestions(
    textAnswer: (String) -> String = { "" },
    selectedOptions: (String) -> Set<String> = { emptySet() },
): List<UserInputQuestionUi> = (get("questions") as? JsonArray).orEmpty().mapNotNull { element ->
    val question = element as? JsonObject ?: return@mapNotNull null
    fun text(key: String) = (question[key] as? JsonPrimitive)?.contentOrNull
    val id = text("id") ?: return@mapNotNull null
    UserInputQuestionUi(
        id = id,
        label = text("question") ?: id,
        description = text("header"),
        required = true,
        allowsMultiple = (question["multiSelect"] as? JsonPrimitive)?.booleanOrNull == true,
        allowsCustomAnswer = (question["allowCustomAnswer"] as? JsonPrimitive)?.booleanOrNull != false,
        options = (question["options"] as? JsonArray).orEmpty().mapNotNull { option ->
            val fields = option as? JsonObject ?: return@mapNotNull null
            val label = (fields["label"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            UserInputOptionUi(
                id = (fields["value"] as? JsonPrimitive)?.contentOrNull ?: label,
                label = label,
                description = (fields["description"] as? JsonPrimitive)?.contentOrNull,
            )
        },
        textAnswer = textAnswer(id),
        selectedOptionIds = selectedOptions(id),
    )
}

internal fun UserInputQuestionUi.resolvedAnswer(): JsonElement? {
    if (allowsCustomAnswer && textAnswer.isNotBlank()) return JsonPrimitive(textAnswer.trim())
    val selected = options.filter { it.id in selectedOptionIds }.map { it.id }.distinct()
    if (selected.isEmpty()) return null
    return if (allowsMultiple) JsonArray(selected.map(::JsonPrimitive)) else JsonPrimitive(selected.first())
}

internal fun List<UserInputQuestionUi>.resolvedAnswers(): JsonObject? {
    if (isEmpty()) return null
    val answers = linkedMapOf<String, JsonElement>()
    for (question in this) answers[question.id] = question.resolvedAnswer() ?: return null
    return JsonObject(answers)
}
