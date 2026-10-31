package com.nezhahq.agent.ui

import com.nezhahq.agent.util.ConfigurationOperationGate
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardConfigImporterTest {
    @Test
    fun delayedPasteBlocksStartAndRejectsAnOverlappingPasteUntilItsValuesAreApplied() = runBlocking {
        val becameIdle = Channel<Unit>(Channel.UNLIMITED)
        val applied = Channel<String>(Channel.UNLIMITED)
        val operations = ConfigurationOperationGate { if (!it) becameIdle.trySend(Unit) }
        val parseB = CompletableDeferred<ParsedClipboardConfig>()
        val parseC = CompletableDeferred<ParsedClipboardConfig>()
        val requests = mutableListOf<String>()
        val failures = mutableListOf<Exception>()
        var displayedServer = "A"
        val importer = ClipboardConfigImporter(
            scope = this,
            operations = operations,
            parse = {
                requests += it
                when (it) {
                    "B" -> parseB.await()
                    "C" -> parseC.await()
                    else -> error("unexpected paste")
                }
            },
            apply = {
                displayedServer = requireNotNull(it.server)
                applied.trySend(displayedServer)
            },
            onFailure = { failures += it }
        )

        assertTrue(importer.submit("B"))
        assertTrue("busy must be set synchronously before submit returns", operations.isBusy)
        assertNull("service-start persistence must wait for parsed values", operations.tryBegin())
        assertFalse("a second paste must not race the accepted paste", importer.submit("C"))
        assertEquals(listOf("B"), requests)
        assertEquals("A", displayedServer)

        parseB.complete(ParsedClipboardConfig(server = "B"))
        assertEquals("B", withTimeout(TEST_TIMEOUT_MILLIS) { applied.receive() })
        withTimeout(TEST_TIMEOUT_MILLIS) { becameIdle.receive() }

        // Once the first operation ends, a newer paste can be accepted and must remain the final UI.
        assertTrue(importer.submit("C"))
        assertNull(operations.tryBegin())
        parseC.complete(ParsedClipboardConfig(server = "C"))
        assertEquals("C", withTimeout(TEST_TIMEOUT_MILLIS) { applied.receive() })
        withTimeout(TEST_TIMEOUT_MILLIS) { becameIdle.receive() }
        assertEquals(listOf("B", "C"), requests)
        assertTrue(failures.isEmpty())

        val startOperation = operations.tryBegin()
        assertNotNull(startOperation)
        try {
            assertEquals("start must use the last fully applied configuration", "C", displayedServer)
        } finally {
            startOperation?.close()
        }
    }

    @Test
    fun anExistingPersistenceOperationRejectsThePasteWithoutInvokingTheParser() = runBlocking {
        val operations = ConfigurationOperationGate { }
        val persistence = requireNotNull(operations.tryBegin())
        var parses = 0
        val importer = ClipboardConfigImporter(
            scope = this,
            operations = operations,
            parse = { parses++; ParsedClipboardConfig() },
            apply = { error("must not apply") },
            onFailure = { error("must not run") }
        )

        assertFalse(importer.submit("paste"))
        assertEquals(0, parses)
        assertTrue(operations.isBusy)
        persistence.close()
        assertFalse(operations.isBusy)
    }

    @Test
    fun aParseFailureReleasesTheGateWithoutApplyingPartialValues() = runBlocking {
        val operations = ConfigurationOperationGate { }
        var applications = 0
        val failure = IOException("parse failed")
        val failures = mutableListOf<Exception>()
        val importer = ClipboardConfigImporter(
            scope = this,
            operations = operations,
            parse = { throw failure },
            apply = { applications++ },
            onFailure = { failures += it }
        )

        assertTrue(importer.submit("bad paste"))

        assertFalse(operations.isBusy)
        assertEquals(0, applications)
        assertEquals(listOf(failure), failures)
    }

    @Test
    fun ownerCancellationReleasesTheGateAndDoesNotApplyAResult() = runBlocking {
        val operations = ConfigurationOperationGate { }
        val owner = Job()
        var applications = 0
        val importer = ClipboardConfigImporter(
            scope = CoroutineScope(coroutineContext + owner),
            operations = operations,
            parse = { awaitCancellation() },
            apply = { applications++ },
            onFailure = { error("cancellation is not a parse failure") }
        )
        try {
            assertTrue(importer.submit("pending paste"))
            assertTrue(operations.isBusy)

            withTimeout(TEST_TIMEOUT_MILLIS) { owner.cancelAndJoin() }

            assertFalse(operations.isBusy)
            assertEquals(0, applications)
        } finally {
            owner.cancelAndJoin()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
