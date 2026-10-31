package com.nezhahq.agent.ui

import com.nezhahq.agent.util.ConfigurationOperationGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Holds the same configuration gate as service-start persistence until parsed values are applied. */
internal class ClipboardConfigImporter(
    private val scope: CoroutineScope,
    private val operations: ConfigurationOperationGate,
    private val parse: suspend (String) -> ParsedClipboardConfig = {
        withContext(Dispatchers.IO) { ClipboardConfigParser.parse(it) }
    },
    private val apply: (ParsedClipboardConfig) -> Unit,
    private val onFailure: (Exception) -> Unit
) {
    /** A second paste is rejected while an accepted paste or persistence operation is in progress. */
    fun submit(input: String): Boolean {
        val operation = operations.tryBegin() ?: return false
        // Enter try/finally even if the owning ViewModel scope was cancelled just before submit.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                currentCoroutineContext().ensureActive()
                val parsed = parse(input)
                currentCoroutineContext().ensureActive()
                apply(parsed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure(failure)
            } finally {
                operation.close()
            }
        }
        return true
    }
}
