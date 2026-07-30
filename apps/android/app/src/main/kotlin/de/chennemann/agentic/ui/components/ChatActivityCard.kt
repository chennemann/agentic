package de.chennemann.agentic.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.icons.Check
import de.chennemann.agentic.icons.ChevronDown
import de.chennemann.agentic.icons.ChevronUp
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Terminal
import de.chennemann.agentic.ui.chat.ActivityStatusUi
import de.chennemann.agentic.ui.chat.ApprovalDecisionUi
import de.chennemann.agentic.ui.chat.ChatActivityUi
import de.chennemann.agentic.ui.chat.ChatUiEvent
import de.chennemann.agentic.ui.chat.PendingApprovalUi
import de.chennemann.agentic.ui.chat.PendingUserInputUi
import de.chennemann.agentic.ui.chat.UserInputOptionUi
import de.chennemann.agentic.ui.chat.UserInputQuestionUi
import de.chennemann.agentic.ui.theme.MobileTheme

@Composable
fun ChatActivityCard(
    activity: ChatActivityUi,
    expanded: Boolean,
    onEvent: (ChatUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (activity) {
        is ChatActivityUi.Approval -> ApprovalCard(
            request = activity.request,
            onDecision = {
                onEvent(
                    ChatUiEvent.ApprovalDecisionSelected(
                        requestId = activity.request.requestId,
                        decision = it,
                    ),
                )
            },
            modifier = modifier,
        )

        is ChatActivityUi.UserInput -> UserInputCard(
            request = activity.request,
            onTextChanged = { questionId, value ->
                onEvent(
                    ChatUiEvent.UserInputTextChanged(
                        requestId = activity.request.requestId,
                        questionId = questionId,
                        value = value,
                    ),
                )
            },
            onOptionToggled = { questionId, optionId ->
                onEvent(
                    ChatUiEvent.UserInputOptionToggled(
                        requestId = activity.request.requestId,
                        questionId = questionId,
                        optionId = optionId,
                    ),
                )
            },
            onSubmit = {
                onEvent(ChatUiEvent.UserInputSubmitted(activity.request.requestId))
            },
            modifier = modifier,
        )

        is ChatActivityUi.Tool -> CompactToolActivityRow(
            activity = activity,
            expanded = expanded,
            onExpansionChange = {
                onEvent(ChatUiEvent.ActivityExpansionChanged(activity.id, it))
            },
            modifier = modifier,
        )

        else -> ExpandableActivityCard(
            activity = activity,
            expanded = expanded,
            onExpansionChange = {
                onEvent(ChatUiEvent.ActivityExpansionChanged(activity.id, it))
            },
            onRetry = {
                onEvent(ChatUiEvent.ActivityRetryRequested(activity.id))
            },
            modifier = modifier,
        )
    }
}

@Composable
fun ToolActivityGroup(
    groupId: String,
    activities: List<ChatActivityUi.Tool>,
    expanded: Boolean,
    activityExpanded: (String) -> Boolean,
    onEvent: (ChatUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = activities.lastOrNull {
        it.status == ActivityStatusUi.RUNNING || it.status == ActivityStatusUi.PENDING
    }
    val history = if (running == null) activities else activities.filterNot { it.id == running.id }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        running?.let {
            CompactToolActivityRow(
                activity = it,
                expanded = activityExpanded(it.id),
                onExpansionChange = { value ->
                    onEvent(ChatUiEvent.ActivityExpansionChanged(it.id, value))
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (history.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable {
                        onEvent(
                            ChatUiEvent.ActivityExpansionChanged(
                                activityId = groupId,
                                expanded = !expanded,
                            ),
                        )
                    }
                    .padding(horizontal = 2.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (expanded) Icons.ChevronUp else Icons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (expanded) {
                        "Show fewer tool calls"
                    } else if (running == null) {
                        "Show ${history.size} tool ${if (history.size == 1) "call" else "calls"}"
                    } else {
                        "+${history.size} previous tool ${if (history.size == 1) "call" else "calls"}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            history.forEach { activity ->
                CompactToolActivityRow(
                    activity = activity,
                    expanded = activityExpanded(activity.id),
                    onExpansionChange = { value ->
                        onEvent(ChatUiEvent.ActivityExpansionChanged(activity.id, value))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CompactToolActivityRow(
    activity: ChatActivityUi.Tool,
    expanded: Boolean,
    onExpansionChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val canExpand = !activity.detail.isNullOrBlank()
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(enabled = canExpand) { onExpansionChange(!expanded) }
                .padding(horizontal = 2.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Terminal,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = activity.summary,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                activity.subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            when (activity.status) {
                ActivityStatusUi.RUNNING,
                ActivityStatusUi.PENDING,
                -> CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.5.dp,
                )

                ActivityStatusUi.COMPLETED -> Icon(
                    imageVector = Icons.Check,
                    contentDescription = "Completed",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ActivityStatusUi.FAILED -> Text(
                    text = "×",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (canExpand) {
                Icon(
                    imageVector = if (expanded) Icons.ChevronUp else Icons.ChevronDown,
                    contentDescription = if (expanded) "Hide details" else "Show details",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(
            visible = expanded && canExpand,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically(),
        ) {
            SelectionContainer {
                Text(
                    text = activity.detail.orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 25.dp, end = 4.dp, bottom = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ExpandableActivityCard(
    activity: ChatActivityUi,
    expanded: Boolean,
    onExpansionChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    val detail = activity.detail()
    val error = activity is ChatActivityUi.Error
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = detail != null,
                        onClick = { onExpansionChange(!expanded) },
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = activity.summary,
                        color = if (error) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    activity.subtitle()?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (error) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                ActivityStatusDot(activity.status())
            }
            AnimatedVisibility(
                visible = expanded && detail != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically(),
            ) {
                detail?.let {
                    SelectionContainer {
                        Text(
                            text = it,
                            style = if (activity is ChatActivityUi.Unknown) {
                                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                        )
                    }
                }
            }
            if (activity is ChatActivityUi.Error && activity.canRetry) {
                OutlinedButton(onClick = onRetry) {
                    Text("Try again")
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    request: PendingApprovalUi,
    onDecision: (ApprovalDecisionUi) -> Unit,
    modifier: Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = request.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            request.description?.let {
                SelectionContainer {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            request.decisions.forEach { decision ->
                val emphasized = decision == ApprovalDecisionUi.ACCEPT ||
                    decision == ApprovalDecisionUi.ACCEPT_FOR_SESSION
                if (emphasized) {
                    Button(
                        onClick = { onDecision(decision) },
                        enabled = !request.responseInFlight,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(decision.label)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onDecision(decision) },
                        enabled = !request.responseInFlight,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(decision.label)
                    }
                }
            }
            if (request.responseInFlight) {
                Text(
                    text = "Sending response…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun UserInputCard(
    request: PendingUserInputUi,
    onTextChanged: (questionId: String, value: String) -> Unit,
    onOptionToggled: (questionId: String, optionId: String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = request.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                request.description?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            request.questions.forEach { question ->
                UserInputQuestion(
                    question = question,
                    enabled = !request.responseInFlight,
                    onTextChanged = { onTextChanged(question.id, it) },
                    onOptionToggled = { onOptionToggled(question.id, it) },
                )
            }
            Button(
                onClick = onSubmit,
                enabled = request.canSubmit && !request.responseInFlight,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (request.responseInFlight) "Sending…" else "Submit answers")
            }
        }
    }
}

@Composable
private fun UserInputQuestion(
    question: UserInputQuestionUi,
    enabled: Boolean,
    onTextChanged: (String) -> Unit,
    onOptionToggled: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (question.required) "${question.label} *" else question.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        question.description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        if (question.options.isEmpty()) {
            OutlinedTextField(
                value = question.textAnswer,
                onValueChange = onTextChanged,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        } else {
            question.options.forEach { option ->
                FilterChip(
                    selected = option.id in question.selectedOptionIds,
                    onClick = { onOptionToggled(option.id) },
                    enabled = enabled,
                    label = {
                        Column {
                            Text(option.label)
                            option.description?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                )
            }
            if (question.allowsMultiple) {
                Text(
                    text = "Select any that apply",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ActivityStatusDot(status: ActivityStatusUi?) {
    val color = when (status) {
        ActivityStatusUi.COMPLETED -> Color(0xFF00E676)
        ActivityStatusUi.RUNNING -> Color(0xFFFFEA00)
        ActivityStatusUi.FAILED -> Color(0xFFFF1744)
        ActivityStatusUi.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

private fun ChatActivityUi.detail(): String? = when (this) {
    is ChatActivityUi.Information -> detail
    is ChatActivityUi.Tool -> detail
    is ChatActivityUi.Error -> detail
    is ChatActivityUi.Unknown -> formattedDetail
    is ChatActivityUi.Approval, is ChatActivityUi.UserInput -> null
}

private fun ChatActivityUi.subtitle(): String? = when (this) {
    is ChatActivityUi.Tool -> subtitle
    is ChatActivityUi.Unknown -> typeLabel
    else -> null
}

private fun ChatActivityUi.status(): ActivityStatusUi? = when (this) {
    is ChatActivityUi.Tool -> status
    is ChatActivityUi.Error -> ActivityStatusUi.FAILED
    else -> null
}

@Preview(showBackground = true)
@Composable
private fun ExpandedUnknownActivityPreview() {
    MobileTheme {
        ChatActivityCard(
            activity = ChatActivityUi.Unknown(
                id = "unknown",
                summary = "Provider activity",
                typeLabel = "future.activity",
                formattedDetail = """
                    {
                      "summary": "A safe detail supplied by the server",
                      "state": "running"
                    }
                """.trimIndent(),
            ),
            expanded = true,
            onEvent = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ApprovalActivityPreview() {
    MobileTheme {
        ChatActivityCard(
            activity = ChatActivityUi.Approval(
                id = "approval",
                request = PendingApprovalUi(
                    requestId = "approval-1",
                    title = "Allow workspace change?",
                    description = "The agent wants to update two files in the selected project.",
                    decisions = listOf(
                        ApprovalDecisionUi.ACCEPT,
                        ApprovalDecisionUi.ACCEPT_FOR_SESSION,
                        ApprovalDecisionUi.DECLINE,
                    ),
                ),
            ),
            expanded = false,
            onEvent = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StructuredInputActivityPreview() {
    MobileTheme {
        ChatActivityCard(
            activity = ChatActivityUi.UserInput(
                id = "input",
                request = PendingUserInputUi(
                    requestId = "input-1",
                    title = "Choose a direction",
                    description = "The agent needs one decision to continue.",
                    questions = listOf(
                        UserInputQuestionUi(
                            id = "approach",
                            label = "Implementation approach",
                            required = true,
                            options = listOf(
                                UserInputOptionUi("minimal", "Minimal"),
                                UserInputOptionUi("complete", "Complete"),
                            ),
                            selectedOptionIds = setOf("minimal"),
                        ),
                    ),
                ),
            ),
            expanded = false,
            onEvent = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
