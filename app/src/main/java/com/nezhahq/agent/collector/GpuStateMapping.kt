package com.nezhahq.agent.collector

import proto.Nezha.State
import proto.Nezha.State_GPU
import java.util.concurrent.CancellationException

/** 同一份有序样本同时写入旧 gpu=17 和新 gpus=18；无可靠显存来源时，MiB 字段保留 0（未知）。 */
// Inline keeps both suspending device readers and synchronous simulator readers on one mapping path.
internal inline fun State.Builder.addGpuSample(readUsages: () -> List<Double>): State.Builder {
    val usages = try {
        readUsages()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return this
    }
    // 不逐项过滤，避免 GPU 名称与样本索引错位；真实的 0% 仍是有效读数。
    if (usages.any { !it.isFinite() }) return this

    addAllGpu(usages)
    usages.forEach { usage ->
        addGpus(State_GPU.newBuilder().setUtilization(usage).build())
    }
    return this
}
