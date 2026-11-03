package com.nezhahq.agent.util

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences

/**
 * Gives the legacy AndroidX/Tink decoder the original file name (its AAD), but no disk-backed
 * preferences object. Even its applicationContext lookup stays inside this narrow wrapper.
 */
internal class ReadOnlyLegacyPreferencesContext(
    context: Context,
    private val preferencesName: String,
    private val snapshot: SharedPreferences
) : ContextWrapper(context) {
    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        check(name == preferencesName) { "Unexpected legacy preferences name" }
        return snapshot
    }
}

/** No listener events or editor exist because this source is an immutable, already parsed copy. */
internal class ReadOnlyLegacyPreferences(values: ConfigurationValues) : SharedPreferences {
    private val values = values.entries

    override fun getAll(): Map<String, *> = values.toMap()
    override fun contains(key: String?): Boolean = key != null && values.containsKey(key)
    override fun getString(key: String?, defValue: String?): String? = value(key)?.let { it as String } ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        value(key)?.let { it as Set<String> } ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = value(key)?.let { it as Int } ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = value(key)?.let { it as Long } ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = value(key)?.let { it as Float } ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = value(key)?.let { it as Boolean } ?: defValue

    override fun edit(): SharedPreferences.Editor =
        throw UnsupportedOperationException("Legacy preferences snapshot is read-only")

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private fun value(key: String?): Any? = if (key == null) null else values[key]
}
