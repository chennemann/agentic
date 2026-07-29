package de.chennemann.agentic.ui.onboarding

data class OnboardingUiState(
    val endpointInput: String = "",
    val inputKind: OnboardingInputKindUi = OnboardingInputKindUi.PAIRING_LINK,
    val savedEnvironments: List<SavedEnvironmentUi> = emptyList(),
    val environmentPreview: EnvironmentPreviewUi? = null,
    val cleartextConfirmation: CleartextConfirmationUi? = null,
    val working: Boolean = false,
    val error: OnboardingErrorUi? = null,
)

enum class OnboardingInputKindUi(
    val label: String,
) {
    PAIRING_LINK("Pairing link"),
    SERVER_URL("Server URL"),
}

data class SavedEnvironmentUi(
    val id: String,
    val label: String,
    val endpointLabel: String,
    val active: Boolean = false,
)

data class EnvironmentPreviewUi(
    val environmentId: String,
    val label: String,
    val platform: String,
    val version: String,
    val endpointLabel: String,
)

data class CleartextConfirmationUi(
    val endpointLabel: String,
    val explanation: String,
)

sealed interface OnboardingErrorUi {
    val message: String

    data class InvalidPairing(
        override val message: String,
    ) : OnboardingErrorUi

    data class ExpiredPairing(
        override val message: String,
    ) : OnboardingErrorUi

    data class UnsupportedServer(
        override val message: String,
    ) : OnboardingErrorUi

    data class PublicCleartextRejected(
        override val message: String,
    ) : OnboardingErrorUi

    data class ConnectionFailed(
        override val message: String,
    ) : OnboardingErrorUi
}

sealed interface OnboardingUiEvent {
    data object CloseRequested : OnboardingUiEvent

    data class EndpointChanged(
        val value: String,
    ) : OnboardingUiEvent

    data class InputKindSelected(
        val kind: OnboardingInputKindUi,
    ) : OnboardingUiEvent

    data object EndpointSubmitted : OnboardingUiEvent

    data object QrScanRequested : OnboardingUiEvent

    data class QrCodeScanned(
        val value: String,
    ) : OnboardingUiEvent

    data object PairingConfirmed : OnboardingUiEvent

    data object CleartextConfirmed : OnboardingUiEvent

    data object CleartextDeclined : OnboardingUiEvent

    data class SavedEnvironmentSelected(
        val environmentId: String,
    ) : OnboardingUiEvent

    data class SavedEnvironmentRemovalRequested(
        val environmentId: String,
    ) : OnboardingUiEvent

    data object ErrorDismissed : OnboardingUiEvent
}
