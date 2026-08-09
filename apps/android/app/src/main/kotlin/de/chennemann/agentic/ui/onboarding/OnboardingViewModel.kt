package de.chennemann.agentic.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.data.auth.PairingException
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.EnvironmentRemover
import de.chennemann.agentic.domain.environment.EnvironmentService
import de.chennemann.agentic.domain.environment.PairingPreview
import de.chennemann.agentic.domain.environment.UnsupportedProtocolException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OnboardingViewModel(
    private val environments: EnvironmentRepository,
    private val service: EnvironmentService,
    private val remover: EnvironmentRemover,
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())
    private var pairingPreview: PairingPreview? = null

    val state = combine(environments.environments, local) { saved, local ->
        OnboardingUiState(
            endpointInput = local.endpointInput,
            inputKind = local.inputKind,
            savedEnvironments = saved.map {
                SavedEnvironmentUi(
                    id = it.id,
                    label = it.label,
                    endpointLabel = it.baseUrl,
                    active = it.active,
                )
            },
            environmentPreview = pairingPreview?.let {
                EnvironmentPreviewUi(
                    environmentId = it.descriptor.environmentId,
                    label = it.descriptor.label,
                    platform = "${it.descriptor.platform.os} ${it.descriptor.platform.arch}",
                    version = it.descriptor.serverVersion,
                    endpointLabel = it.target.baseUrl,
                )
            },
            cleartextConfirmation = local.cleartextConfirmation,
            removalConfirmation = saved.firstOrNull { it.id == local.removalEnvironmentId }?.let {
                SavedEnvironmentUi(it.id, it.label, it.baseUrl, it.active)
            },
            working = local.working,
            error = local.error,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        OnboardingUiState(),
    )

    fun onEvent(event: OnboardingUiEvent) {
        when (event) {
            OnboardingUiEvent.CloseRequested -> Unit
            is OnboardingUiEvent.EndpointChanged -> update {
                copy(endpointInput = event.value, error = null)
            }

            is OnboardingUiEvent.InputKindSelected -> update {
                copy(inputKind = event.kind, error = null)
            }

            OnboardingUiEvent.EndpointSubmitted -> inspect()
            OnboardingUiEvent.PairingConfirmed -> {
                val preview = pairingPreview ?: return
                if (preview.target.requiresCleartextConfirmation && !local.value.cleartextAccepted) {
                    update {
                        copy(
                            cleartextConfirmation = CleartextConfirmationUi(
                                endpointLabel = preview.target.baseUrl,
                                explanation = "HTTP is unencrypted. Continue only on a private network you trust.",
                            ),
                        )
                    }
                } else {
                    pair()
                }
            }

            OnboardingUiEvent.CleartextConfirmed -> {
                update { copy(cleartextConfirmation = null, cleartextAccepted = true) }
                pair()
            }

            OnboardingUiEvent.CleartextDeclined -> update {
                copy(cleartextConfirmation = null, cleartextAccepted = false)
            }

            is OnboardingUiEvent.SavedEnvironmentSelected -> launchWork {
                service.select(event.environmentId)
            }

            is OnboardingUiEvent.SavedEnvironmentRemovalRequested -> update {
                copy(removalEnvironmentId = event.environmentId)
            }
            OnboardingUiEvent.SavedEnvironmentRemovalConfirmed -> {
                val environmentId = local.value.removalEnvironmentId ?: return
                launchWork {
                    remover.remove(environmentId)
                    update { copy(removalEnvironmentId = null) }
                }
            }
            OnboardingUiEvent.SavedEnvironmentRemovalDismissed -> update {
                copy(removalEnvironmentId = null)
            }

            OnboardingUiEvent.ErrorDismissed -> update { copy(error = null) }
            OnboardingUiEvent.QrScanRequested -> Unit
            is OnboardingUiEvent.QrCodeScanned -> {
                update {
                    copy(
                        endpointInput = event.value,
                        inputKind = OnboardingInputKindUi.PAIRING_LINK,
                    )
                }
                inspect()
            }
        }
    }

    private fun inspect() {
        val input = local.value.endpointInput
        launchWork {
            pairingPreview = service.inspect(
                input,
                requireCredential = local.value.inputKind == OnboardingInputKindUi.PAIRING_LINK,
            )
        }
    }

    private fun pair() {
        val preview = pairingPreview ?: return
        launchWork {
            service.pair(preview)
            pairingPreview = null
        }
    }

    private fun launchWork(block: suspend () -> Unit) {
        viewModelScope.launch {
            update { copy(working = true, error = null) }
            runCatching { block() }
                .onFailure { cause -> update { copy(error = cause.toUiError()) } }
            update { copy(working = false) }
        }
    }

    private fun update(transform: LocalState.() -> LocalState) {
        local.value = local.value.transform()
    }

    private data class LocalState(
        val endpointInput: String = "",
        val inputKind: OnboardingInputKindUi = OnboardingInputKindUi.PAIRING_LINK,
        val working: Boolean = false,
        val error: OnboardingErrorUi? = null,
        val cleartextConfirmation: CleartextConfirmationUi? = null,
        val cleartextAccepted: Boolean = false,
        val removalEnvironmentId: String? = null,
    )
}

private fun Throwable.toUiError(): OnboardingErrorUi = when (this) {
    is PairingException.PublicCleartext -> OnboardingErrorUi.PublicCleartextRejected(message.orEmpty())
    is PairingException.EnvironmentMismatch,
    is PairingException.Invalid,
    is PairingException.MissingCredential,
    -> OnboardingErrorUi.InvalidPairing(message.orEmpty())

    is UnsupportedProtocolException -> OnboardingErrorUi.UnsupportedServer(message.orEmpty())
    else -> OnboardingErrorUi.ConnectionFailed(message ?: "The environment could not be reached.")
}
