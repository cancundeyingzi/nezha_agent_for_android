package com.nezhahq.agent.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.random.Random

/**
 * 播放微弱动态次声波 PCM 音频以防止应用在后台被系统休眠或杀死。
 * 包含频率漂移、振幅随机扰动与短暂随机静音，避免系统特征匹配杀后台。
 */
class KeepAliveAudioPlayer {
    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false

    fun start() {
        if (isPlaying) return
        isPlaying = true

        val sampleRate = 8000 // 低采样率，降低开销
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()

            Thread {
                val silentData = ShortArray(bufferSize)
                var phase = 0.0

                while (isPlaying) {
                    // 1. 模拟随机行为：以较小概率(约 5%)插入完全静音块
                    if (Random.nextFloat() < 0.05f) {
                        for (i in silentData.indices) silentData[i] = 0
                    } else {
                        // 2. 动态参数漂移
                        // 频率在 18.0Hz 到 22.0Hz 之间随机漂移
                        val frequency = 18.0 + Random.nextDouble() * 4.0
                        // 振幅在 5 到 15 之间随机波动 (极低音量)
                        val amplitude = 5 + Random.nextInt(11)
                        
                        for (i in silentData.indices) {
                            silentData[i] = (Math.sin(phase) * amplitude).toInt().toShort()
                            phase += 2.0 * Math.PI * frequency / sampleRate
                        }
                        // 防止 phase 无限增长导致精度丢失
                        if (phase > 2.0 * Math.PI) {
                            phase -= 2.0 * Math.PI
                        }
                    }

                    // write 具有阻塞特性，当缓冲区满时会阻塞，因此不会导致 CPU 占用过高
                    val result = audioTrack?.write(silentData, 0, silentData.size)
                    if (result != null && result < 0) {
                        Logger.e("KeepAliveAudioPlayer: 写入音频数据失败，错误码 $result")
                        break
                    }
                }
            }.start()
        } catch (e: Exception) {
            Logger.e("KeepAliveAudioPlayer: 启动失败", e)
            isPlaying = false
        }
    }

    fun stop() {
        isPlaying = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Logger.e("KeepAliveAudioPlayer: 停止异常", e)
        } finally {
            audioTrack = null
        }
    }
}
