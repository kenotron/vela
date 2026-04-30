package com.vela.app.ui.approval

    import androidx.lifecycle.ViewModel
    import dagger.hilt.android.lifecycle.HiltViewModel
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.asStateFlow
    import javax.inject.Inject

    @HiltViewModel
    class ApprovalSheetViewModel @Inject constructor() : ViewModel() {

        data class ApprovalRequest(
            val sessionId:   String,
            val question:    String,
            val contextText: String? = null,
        )

        private val _request = MutableStateFlow<ApprovalRequest?>(null)
        val request: StateFlow<ApprovalRequest?> = _request.asStateFlow()

        /** Show the sheet with a new approval request. Replaces any pending request. */
        fun present(req: ApprovalRequest) {
            _request.value = req
        }

        /** Confirm the requested action and dismiss the sheet. */
        fun approve() {
            // TODO: signal the session identified by request.sessionId
            _request.value = null
        }

        /** Reject the requested action and dismiss the sheet. */
        fun deny() {
            // TODO: signal denial to the session
            _request.value = null
        }
    }
    