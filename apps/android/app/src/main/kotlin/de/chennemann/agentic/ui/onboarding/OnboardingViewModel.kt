package de.chennemann.agentic.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.chennemann.agentic.data.auth.ClientCertificateStore
import de.chennemann.agentic.data.auth.PairingException
import de.chennemann.agentic.data.auth.PairingUrlParser
import de.chennemann.agentic.domain.environment.EnvironmentRepository
import de.chennemann.agentic.domain.environment.EnvironmentRemover
import de.chennemann.agentic.domain.environment.EnvironmentService
import de.chennemann.agentic.domain.environment.PairingPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OnboardingViewModel(
    private val environments: EnvironmentRepository,
    private val service: EnvironmentService,
    private val remover: EnvironmentRemover,
    private val clientCertificates: ClientCertificateStore,
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())
    private var pairingPreview: PairingPreview? = null

    val state = combine(environments.environments, local) { saved, local ->
        OnboardingUiState(
            endpointInput = local.endpointInput,
            pairingCodeInput = local.pairingCodeInput,
            inputKind = local.inputKind,
            clientCertificate = local.clientCertificateServerUrl?.let {
                ClientCertificateUi(it, local.clientCertificateSelected)
            },
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
            is OnboardingUiEvent.EndpointChanged -> updateEndpoint(event.value)
            is OnboardingUiEvent.PairingCodeChanged -> {
                pairingPreview = null
                update { copy(pairingCodeInput = event.value, error = null) }
            }

            is OnboardingUiEvent.InputKindSelected -> {
                pairingPreview = null
                update { copy(inputKind = event.kind, error = null) }
            }

            OnboardingUiEvent.EndpointSubmitted -> inspect()
            is OnboardingUiEvent.ClientCertificateSelected -> {
                val serverUrl = local.value.clientCertificateServerUrl ?: return
                clientCertificates.setAlias(serverUrl, event.alias)
                pairingPreview = null
                update { copy(clientCertificateSelected = true, error = null) }
            }
            OnboardingUiEvent.ClientCertificateCleared -> {
                val serverUrl = local.value.clientCertificateServerUrl ?: return
                clientCertificates.setAlias(serverUrl, null)
                pairingPreview = null
                update { copy(clientCertificateSelected = false, error = null) }
            }
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
                updateEndpoint(event.value) { copy(inputKind = OnboardingInputKindUi.PAIRING_LINK) }
                inspect()
            }
        }
    }

    private fun inspect() {
        val input = local.value.endpointInput
        launchWork {
            pairingPreview = service.inspect(
                input,
                requireCredential = true,
                pairingCode = local.value.pairingCodeInput.takeIf {
                    local.value.inputKind == OnboardingInputKindUi.SERVER_URL
                },
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

    private fun updateEndpoint(
        value: String,
        transform: LocalState.() -> LocalState = { this },
    ) {
        pairingPreview = null
        val serverUrl = runCatching {
            PairingUrlParser.parse(value, requireCredential = false).baseUrl
        }.getOrNull()?.takeIf { it.startsWith("https://") }
        update {
            copy(
                endpointInput = value,
                clientCertificateServerUrl = serverUrl,
                clientCertificateSelected = serverUrl?.let(clientCertificates::aliasFor) != null,
                error = null,
            ).transform()
        }
    }

    private data class LocalState(
        val endpointInput: String = "",
        val pairingCodeInput: String = "",
        val inputKind: OnboardingInputKindUi = OnboardingInputKindUi.PAIRING_LINK,
        val clientCertificateServerUrl: String? = null,
        val clientCertificateSelected: Boolean = false,
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

    else -> OnboardingErrorUi.ConnectionFailed(message ?: "The environment could not be reached.")
}
