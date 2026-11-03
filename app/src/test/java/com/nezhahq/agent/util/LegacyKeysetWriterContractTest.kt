package com.nezhahq.agent.util

import android.content.Context
import android.content.ContextWrapper
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class LegacyKeysetWriterContractTest {
    @Test fun realTinkWriterCanBeConstructedButNeitherKeysetWriteCanChangeItsSource() {
        val snapshot = ReadOnlyLegacyPreferences(ConfigurationValues.from(mapOf(KEYSET to "existing-keyset")))
        // The base context has no working framework methods. Tink's applicationContext and
        // getSharedPreferences calls must stay inside the actual production wrapper.
        val context = ReadOnlyLegacyPreferencesContext(ContextWrapper(null), PREFERENCES, snapshot)
        assertSame(context, context.applicationContext)
        // security-crypto exposes Tink only on the runtime classpath. Reflect the same resolved
        // production classes instead of adding a separately versioned test dependency.
        val writerClass = Class.forName("com.google.crypto.tink.integration.android.SharedPrefKeysetWriter")
        val writer = writerClass.getConstructor(Context::class.java, String::class.java, String::class.java)
            .newInstance(context, KEYSET, PREFERENCES)
        assertEquals("existing-keyset", snapshot.getString(KEYSET, null))
        listOf("Keyset", "EncryptedKeyset").forEach { type ->
            val keysetClass = Class.forName("com.google.crypto.tink.proto.$type")
            val keyset = keysetClass.getMethod("getDefaultInstance").invoke(null)
            val failure = assertThrows(InvocationTargetException::class.java) {
                writerClass.getMethod("write", keysetClass).invoke(writer, keyset)
            }
            assertTrue(failure.cause is UnsupportedOperationException)
        }
        assertEquals(mapOf(KEYSET to "existing-keyset"), snapshot.all)
        assertThrows(IllegalStateException::class.java) {
            context.getSharedPreferences("unrelated-preferences", Context.MODE_PRIVATE)
        }
    }

    private companion object {
        const val PREFERENCES = "legacy-keyset-contract"
        const val KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    }
}
