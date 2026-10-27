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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.chennemann.agentic.icons.Check
import de.chennemann.agentic.icons.ChevronDown
import de.chennemann.agentic.icons.ChevronUp
import de.chennemann.agentic.icons.Brain
import de.chennemann.agentic.icons.Icons
import de.chennemann.agentic.icons.Terminal
import de.chennemann.agentic.streamingmarkdown.StreamingMarkdownText
import de.chennemann.agentic.ui.chat.ActivityStatusUi
import de.chennemann.agentic.ui.chat.ApprovalDecisionUi
import de.chennemann.agentic.ui.chat.ChatActivityUi
import de.chennemann.agentic.ui.chat.ChatUiEvent
import de.chennemann.agentic.ui.chat.PendingApprovalUi
import de.chennemann.agentic.ui.chat.PendingUserInputUi
import de.chennemann.agentic.ui.chat.UserInputOptionUi
import de.chennemann.agentic.ui.chat.UserInputQuestionUi
import de.chennemann.agentic.ui.chat.previewOption
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
            onDismiss = { onEvent(ChatUiEvent.UserInputDismissed(activity.request.requestId)) },
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

        is ChatActivityUi.ProposedPlan -> ProposedPlanCard(activity, onEvent, modifier)

        is ChatActivityUi.Compaction -> CompactionDivider(activity, modifier)

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
private fun ProposedPlanCard(
    plan: ChatActivityUi.ProposedPlan,
    onEvent: (ChatUiEvent) -> Unit,
    modifier: Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(plan.summary, style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                StreamingMarkdownText(
                    content = plan.planMarkdown,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            plan.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (plan.canContinue || plan.continuing || plan.errorMessage != null) {
                Button(
                    onClick = { onEvent(ChatUiEvent.ProposedPlanContinueRequested(plan.id)) },
                    enabled = plan.canContinue && !plan.continuing,
                ) {
                    if (plan.continuing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (plan.errorMessage == null) "Continue with plan" else "Retry plan")
                    }
                }
            }
        }
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
    val activityLabel = if (activities.firstOrNull()?.isAgent == true) "agent" else "tool call"
    val active = activities.filter {
        it.status in setOf(ActivityStatusUi.RUNNING, ActivityStatusUi.PENDING, ActivityStatusUi.FAILED)
    }
    val history = activities.filterNot { it in active }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        active.forEach {
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
                        "Show fewer ${activityLabel}s"
                    } else if (active.isEmpty()) {
                        "Show ${history.size} $activityLabel${if (history.size == 1) "" else "s"}"
                    } else {
                        "+${history.size} previous $activityLabel${if (history.size == 1) "" else "s"}"
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
                imageVector = if (activity.isAgent) Icons.Brain else Icons.Terminal,
                contentDescription = if (activity.isAgent) "Agent" else "Tool call",
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

                ActivityStatusUi.STOPPED -> Text("Stopped", style = MaterialTheme.typography.labelSmall)
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
            request.reason?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            request.fullAccessExplanation?.let { explanation ->
                FullAccessExplanation(explanation)
            }
            request.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            request.decisions.forEach { decision ->
                val emphasized = decision == ApprovalDecisionUi.ACCEPT ||
                    decision == ApprovalDecisionUi.ACCEPT_FOR_SESSION
                if (emphasized) {
                    Button(
                        onClick = { onDecision(decision) },
                        enabled = !request.responseInFlight,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(request.optionLabels[decision] ?: decision.label)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onDecision(decision) },
                        enabled = !request.responseInFlight,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(request.optionLabels[decision] ?: decision.label)
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
private fun FullAccessExplanation(explanation: String) {
    var open by remember(explanation) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = if (open) "Hide explanation" else "Show explanation") { open = !open },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Why am I being asked this?",
            style = MaterialTheme.typography.labelMedium.copy(textDecoration = TextDecoration.Underline),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically()) {
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun CompactionDivider(
    compaction: ChatActivityUi.Compaction,
    modifier: Modifier,
) {
    val color = if (compaction.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        if (compaction.active) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
        }
        Text(
            text = compaction.summary,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun UserInputCard(
    request: PendingUserInputUi,
    onTextChanged: (questionId: String, value: String) -> Unit,
    onOptionToggled: (questionId: String, optionId: String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
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
            if (request.canDismiss) {
                OutlinedButton(onClick = onDismiss, enabled = !request.responseInFlight) { Text("Dismiss question") }
            }
            request.questions.forEach { question ->
                key(request.requestId, question.id) {
                    UserInputQuestion(
                        question = question,
                        enabled = !request.responseInFlight,
                        onTextChanged = { onTextChanged(question.id, it) },
                        onOptionToggled = { onOptionToggled(question.id, it) },
                    )
                }
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
    // Timeline mappings can arrive after the next frame, or echo an older answer.
    // Seed the editor once; keep text, selection, and composition synchronous with the IME.
    var answer by remember {
        mutableStateOf(TextFieldValue(question.textAnswer))
    }
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
        if (question.allowsCustomAnswer) {
            OutlinedTextField(
                value = answer,
                onValueChange = {
                    val textChanged = answer.text != it.text
                    answer = it
                    if (textChanged) onTextChanged(it.text)
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text(if (question.options.isEmpty()) "Your answer" else "Or write your own answer") },
            )
        }
        if (question.options.isNotEmpty()) {
            question.options.forEach { option ->
                FilterChip(
                    selected = option.id in question.selectedOptionIds,
                    onClick = {
                        // Choosing an option clears the custom answer in the ViewModel too.
                        answer = TextFieldValue()
                        onOptionToggled(option.id)
                    },
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
            question.previewOption()?.let { option ->
                OptionPreview(option)
            }
        }
    }
}

@Composable
private fun OptionPreview(option: UserInputOptionUi) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Preview · ${option.label}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                StreamingMarkdownText(
                    content = option.preview.orEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    streaming = false,
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
        ActivityStatusUi.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
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
    is ChatActivityUi.ProposedPlan -> planMarkdown
    is ChatActivityUi.Approval, is ChatActivityUi.UserInput, is ChatActivityUi.Compaction -> null
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
