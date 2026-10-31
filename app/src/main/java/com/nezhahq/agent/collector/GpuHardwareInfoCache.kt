package com.nezhahq.agent.collector

/** 只缓存成功的名称和厂商，并合并多个采集器的并发首次查询。 */
internal class GpuHardwareInfoCache {
    private var cachedInfo: Pair<String, String>? = null

    fun getOrQuery(query: () -> Pair<String, String>?): Pair<String, String>? =
        synchronized(this) {
            cachedInfo ?: query()?.also { cachedInfo = it }
        }
}
