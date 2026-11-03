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

/** Immutable source; Tink may obtain an Editor handle, but every mutation/commit is rejected. */
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

    // AndroidKeysetManager eagerly constructs its writer even when it only reads an existing
    // keyset. Its constructor calls edit(); rejecting the handle would reject valid migrations.
    override fun edit(): SharedPreferences.Editor = ReadOnlyEditor

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private fun value(key: String?): Any? = if (key == null) null else values[key]

    private object ReadOnlyEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = reject()
        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor = reject()
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = reject()
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = reject()
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = reject()
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = reject()
        override fun remove(key: String?): SharedPreferences.Editor = reject()
        override fun clear(): SharedPreferences.Editor = reject()
        override fun commit(): Boolean = reject()
        override fun apply(): Unit = reject()

        private fun reject(): Nothing = throw UnsupportedOperationException("Legacy preferences snapshot is read-only")
    }
}
