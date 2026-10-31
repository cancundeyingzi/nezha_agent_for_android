package com.nezhahq.agent.service.keepalive

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.nezhahq.agent.util.Logger
import java.io.IOException
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * File-level so the tone generator and the AudioTrack it feeds cannot disagree.
 *
 * The two live in separate classes here, and while this sat inside [AudioKeepAlive]'s private
 * companion the factory could not reach it and open-coded the same 8000 twice. A mismatch fails
 * quietly: it only detunes the inaudible tone the keep-alive depends on.
 */
private const val SAMPLE_RATE = 8_000

internal interface AudioOutput {
    val bufferSizeSamples: Int
    fun start()
    fun write(samples: ShortArray): Int
    fun interrupt()
    fun release()
}

internal fun interface AudioOutputFactory {
    fun create(): AudioOutput
}

internal fun interface AudioCleanupWaiter {
    suspend fun await(jobs: List<Job>)
}

internal class AudioKeepAlive(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val outputFactory: AudioOutputFactory = AndroidAudioOutputFactory(),
    private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val cleanupWaiter: AudioCleanupWaiter = TimeoutAudioCleanupWaiter()
) : KeepAliveResource {
    private val lifecycleMutex = Mutex()
    private val stateLock = Any()
    private var writerJob: Job? = null
    private var ownedOutput: OwnedOutput? = null
    private var desiredEnabled = false
    private var restartRequested = false

    override suspend fun setEnabled(enabled: Boolean) = lifecycleMutex.withLock {
        synchronized(stateLock) { desiredEnabled = enabled }
        if (enabled) requestStart() else requestStop()
    }

    override suspend fun close() = lifecycleMutex.withLock {
        synchronized(stateLock) {
            desiredEnabled = false
            restartRequested = false
        }
        requestStop()
    }

    private fun requestStart() {
        val state = synchronized(stateLock) {
            val writer = writerJob
            val output = ownedOutput
            when {
                writer?.isActive == true && output?.cleanup == null -> StartState.RUNNING
                writer != null || output != null -> {
                    restartRequested = true
                    StartState.CLEANING_UP
                }
                else -> StartState.IDLE
            }
        }
        when (state) {
            StartState.RUNNING -> Unit
            StartState.CLEANING_UP -> {
                synchronized(stateLock) {
                    ownedOutput?.takeIf { it.cleanup == null }?.output
                }?.let(::ensureCleanup)
            }
            StartState.IDLE -> launchWriter()
        }
    }

    private fun launchWriter() {
        val launched = scope.launch(dispatcher) { runWriter() }
        synchronized(stateLock) {
            restartRequested = false
            writerJob = launched
        }
        launched.invokeOnCompletion {
            synchronized(stateLock) {
                if (writerJob === launched) writerJob = null
                if (writerJob == null && ownedOutput?.releaseSucceeded == true) {
                    ownedOutput = null
                }
            }
            schedulePendingRestart()
        }
    }

    private suspend fun requestStop() {
        val writer = synchronized(stateLock) {
            restartRequested = false
            writerJob
        }
        writer?.cancel()
        val cleanup = synchronized(stateLock) { ownedOutput?.output }?.let(::ensureCleanup)
        cleanupWaiter.await(
            listOfNotNull(writer, cleanup?.interruptJob, cleanup?.releaseJob)
        )
    }

    private suspend fun runWriter() {
        var output: AudioOutput? = null
        try {
            output = outputFactory.create()
            synchronized(stateLock) { ownedOutput = OwnedOutput(output) }
            if (!kotlin.coroutines.coroutineContext.isActive) return
            output.start()
            writeAudio(output)
        } catch (cancellation: CancellationException) {
            // Stopping the writer is routine; reporting it as a session failure buried the real
            // ones. Cleanup still runs in the finally below.
            throw cancellation
        } catch (e: Exception) {
            Logger.e("$TAG: 音频保活会话失败", e)
        } finally {
            output?.let { ensureCleanup(it) }
        }
    }

    private fun ensureCleanup(output: AudioOutput): CleanupAttempt {
        val cleanup = synchronized(stateLock) {
            val owned = ownedOutput?.takeIf { it.output === output }
            owned?.cleanup?.let { return it }
            createCleanup(output).also { created ->
                if (owned != null) owned.cleanup = created
            }
        }
        cleanupScope.launch {
            val released = cleanup.releaseJob.await()
            synchronized(stateLock) {
                ownedOutput?.takeIf { it.output === output }?.let { owned ->
                    if (released) {
                        owned.releaseSucceeded = true
                        if (writerJob == null) ownedOutput = null
                    } else if (owned.cleanup === cleanup) {
                        owned.cleanup = null
                    }
                }
            }
            if (released) {
                schedulePendingRestart()
            }
        }
        return cleanup
    }

    private fun createCleanup(output: AudioOutput): CleanupAttempt {
        val interruptJob = cleanupScope.launch {
            runCatching { output.interrupt() }
                .onFailure { Logger.e("$TAG: 中断音频写入异常", it) }
        }
        val releaseJob = cleanupScope.async {
            // A release that has begun must finish, and this says so rather than relying on
            // whichever implementation is behind the interface. KeepAliveController cancels this
            // scope the moment its bounded teardown returns, which can land inside a slow release.
            // `AudioTrack.release` is a blocking native call that would complete regardless — but
            // `AudioOutput` is an interface, and nothing obliges the next thing behind it to be
            // equally uninterruptible.
            //
            // Widening the cleanup budget was the alternative, and it would have done nothing: the
            // 250ms audio waiter is already longer than the controller's per-resource share of the
            // teardown deadline, which cuts it short first. Only raising that deadline would help,
            // and every runtime reload and every service stop waits it out.
            withContext(NonCancellable) {
                runCatching {
                    output.release()
                    true
                }.onFailure {
                    Logger.e("$TAG: 强制释放音频异常", it)
                }.getOrDefault(false)
            }
        }
        return CleanupAttempt(interruptJob, releaseJob)
    }

    private fun schedulePendingRestart() {
        val shouldSchedule = synchronized(stateLock) {
            desiredEnabled && restartRequested && writerJob == null && ownedOutput == null
        }
        if (!shouldSchedule) return
        scope.launch {
            lifecycleMutex.withLock {
                val shouldRestart = synchronized(stateLock) {
                    desiredEnabled && restartRequested
                }
                if (shouldRestart) requestStart()
            }
        }
    }

    private suspend fun writeAudio(output: AudioOutput) {
        val samples = ShortArray(output.bufferSizeSamples)
        var phase = 0.0
        while (kotlin.coroutines.coroutineContext.isActive) {
            phase = fillSamples(samples, phase)
            val result = output.write(samples)
            if (result < 0) throw IOException("AudioTrack.write failed with code $result")
        }
    }

    private fun fillSamples(samples: ShortArray, initialPhase: Double): Double {
        if (Random.nextFloat() < 0.05f) {
            samples.fill(0)
            return initialPhase
        }

        val frequency = 18.0 + Random.nextDouble() * 4.0
        val amplitude = 5 + Random.nextInt(11)
        var phase = initialPhase
        samples.indices.forEach { index ->
            samples[index] = (sin(phase) * amplitude).toInt().toShort()
            phase += 2.0 * PI * frequency / SAMPLE_RATE
        }
        return phase % (2.0 * PI)
    }

    private class OwnedOutput(
        val output: AudioOutput,
        var cleanup: CleanupAttempt? = null,
        var releaseSucceeded: Boolean = false
    )

    private data class CleanupAttempt(
        val interruptJob: Job,
        val releaseJob: Deferred<Boolean>
    )

    private enum class StartState {
        IDLE,
        RUNNING,
        CLEANING_UP
    }

    private companion object {
        const val TAG = "AudioKeepAlive"
    }
}

private class TimeoutAudioCleanupWaiter(
    private val timeoutMillis: Long = 250L
) : AudioCleanupWaiter {
    override suspend fun await(jobs: List<Job>) {
        withTimeoutOrNull(timeoutMillis) { jobs.joinAll() }
    }
}

private class AndroidAudioOutputFactory : AudioOutputFactory {
    override fun create(): AudioOutput {
        val bufferSizeBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(bufferSizeBytes > 0) { "Invalid AudioTrack buffer size: $bufferSizeBytes" }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSizeBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        return AndroidAudioOutput(track, (bufferSizeBytes / Short.SIZE_BYTES).coerceAtLeast(1))
    }
}

private class AndroidAudioOutput(
    private val track: AudioTrack,
    override val bufferSizeSamples: Int
) : AudioOutput {
    override fun start() = track.play()

    override fun write(samples: ShortArray): Int =
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)

    override fun interrupt() = track.stop()

    override fun release() = track.release()
}
