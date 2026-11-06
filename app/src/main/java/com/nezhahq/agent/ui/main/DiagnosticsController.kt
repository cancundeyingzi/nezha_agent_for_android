package com.nezhahq.agent.ui.main

import com.nezhahq.agent.util.LocalDiagnosticState
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.SafeErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class DiagnosticsController(
    private val scope: CoroutineScope,
    localState: StateFlow<LocalDiagnosticState>,
    private val setLocalEnabled: (Boolean) -> Unit,
    private val sample: suspend () -> String
) {
    private val sampling = MutableStateFlow(DiagnosticsState())
    val state = combine(sampling, localState) { current, local -> current.copy(local = local) }
        .stateIn(scope, SharingStarted.Eagerly, DiagnosticsState(local = localState.value))

    fun setLocalDiagnostics(enabled: Boolean) = setLocalEnabled(enabled)

    fun runInstantTest() {
        if (sampling.value.sampling) return
        sampling.value = DiagnosticsState(sampling = true, result = "⏳ 正在采样网速中...")
        scope.launch {
            try {
                sampling.value = sampling.value.copy(result = sample())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Logger.e("Diagnostics: 即时采集失败", failure)
                sampling.value = sampling.value.copy(result = SafeErrorText.display("❌ 采集失败", failure))
            } finally {
                sampling.value = sampling.value.copy(sampling = false)
            }
        }
    }

    fun dismissTestResult() { sampling.value = sampling.value.copy(result = null) }
}
