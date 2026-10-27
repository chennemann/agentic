package de.chennemann.agentic.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.domain.orchestration.OrchestrationRepository
import de.chennemann.agentic.domain.settings.ScopedSetting
import de.chennemann.agentic.domain.settings.ServerSettingsService
import de.chennemann.agentic.domain.settings.ServerSettingsState
import de.chennemann.agentic.domain.settings.ThreadTitleInstructionsKey
import de.chennemann.agentic.domain.settings.scopedText
import de.chennemann.agentic.domain.settings.supportsProjectOverrides
import de.chennemann.agentic.domain.settings.supportsSetting
import de.chennemann.agentic.domain.settings.textSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

data class ProjectScopeUi(
    val id: String,
    val title: String
)

/** A text setting editable for the whole environment and, when a project is selected, for that project. */
data class ScopedTextSettingUi(
    val key: String,
    val title: String,
    val description: String,
    val placeholder: String,
    val value: ScopedSetting<String>
)

data class ServerSettingsUiState(
    val environmentLabel: String = "",
    val project: ProjectScopeUi? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val canWrite: Boolean = false,
    val projectOverridesSupported: Boolean = false,
    val message: String? = null,
    val threadTitleInstructions: ScopedTextSettingUi? = null
) {
    val editable: Boolean get() = canWrite && !loading && !saving
}

class ServerSettingsViewModel(
    private val service: ServerSettingsService,
    orchestration: OrchestrationRepository
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)

    val state: StateFlow<ServerSettingsUiState> = combine(
        service.repository.state,
        orchestration.shell,
        projectId
    ) { settings, shell, selectedProjectId ->
        val project = selectedProjectId?.let { id ->
            shell.value?.projects?.firstOrNull { it.id == id }?.let { ProjectScopeUi(it.id, it.title) }
        }
        mapServerSettings(settings, project)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ServerSettingsUiState())

    fun load(projectId: String?) {
        this.projectId.value = projectId
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { service.load() }
    }

    /** Environment-wide value of a server setting. */
    fun saveGlobal(key: String, value: JsonElement) {
        viewModelScope.launch { service.updateGlobal(key, value) }
    }

    /** The selected project's own value, or `null` to inherit the environment value again. */
    fun saveProject(key: String, value: JsonElement?) {
        val project = projectId.value ?: return
        viewModelScope.launch { service.updateProject(project, key, value) }
    }

    fun saveGlobalText(key: String, value: String) = saveGlobal(key, textSetting(value))

    fun saveProjectText(key: String, value: String?) = saveProject(key, value?.let(::textSetting))
}

internal fun mapServerSettings(state: ServerSettingsState, project: ProjectScopeUi?): ServerSettingsUiState {
    val snapshot = state.snapshot
    val settings = snapshot?.settings
    val projectOverrides = settings?.supportsProjectOverrides == true
    return ServerSettingsUiState(
        environmentLabel = snapshot?.environmentLabel.orEmpty(),
        project = project,
        loading = state.loading && snapshot == null,
        saving = state.saving,
        canWrite = snapshot?.canWrite == true,
        projectOverridesSupported = projectOverrides,
        message = state.error ?: when {
            snapshot == null -> null
            !snapshot.canWrite -> "This pairing can read but not change server settings. Pair again with settings access to edit them."
            else -> null
        },
        threadTitleInstructions = settings?.takeIf { it.supportsSetting(ThreadTitleInstructionsKey) }?.let {
            ScopedTextSettingUi(
                key = ThreadTitleInstructionsKey,
                title = "Thread title instructions",
                description = "Appended to the prompt for new and regenerated thread titles.",
                placeholder = "Keep titles to a few words. Lead with the main subject.",
                value = it.scopedText(ThreadTitleInstructionsKey, project?.id?.takeIf { projectOverrides })
            )
        }
    )
}
