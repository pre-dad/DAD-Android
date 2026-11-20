package org.dad.foundation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class KeystoreStoreTest {
    @Test fun credentialsRoundTripEncryptedOutsideBackupAndCanBeCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = KeystoreCredentialStore(context, "test-session")
        try {
            val value = Credentials("synthetic-access-secret", "synthetic-refresh-secret", 1234)
            store.save(value)
            assertEquals(value, store.load())
            val bytes = File(context.noBackupFilesDir, "test-session.enc").readBytes()
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("synthetic"))
            store.clear()
            assertNull(store.load())
        } finally { store.clear() }
    }

    @Test fun corruptedSessionFileLoadsAsSignedOutAndIsRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = KeystoreCredentialStore(context, "test-corrupt-session")
        val file = File(context.noBackupFilesDir, "test-corrupt-session.enc")
        try {
            store.save(Credentials("synthetic-access-secret", "synthetic-refresh-secret", 1234))
            file.writeBytes(ByteArray(64) { it.toByte() })
            assertNull(store.load())
            assertFalse(file.exists())
        } finally { store.clear() }
    }

    @Test fun anUnreadableSessionFileLoadsAsSignedOutWithoutCrashing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = KeystoreCredentialStore(context, "unreadable-session")
        val unreadable = File(context.noBackupFilesDir, "unreadable-session.enc")
        try {
            assertTrue(unreadable.mkdir())
            assertNull(store.load())
        } finally { unreadable.delete() }
    }

    @Test fun donorPartnerAndApplicationReceiptAreSeparateEncryptedEntries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val donor = KeystoreCredentialStore(context, "test-donor-session")
        val partner = KeystoreCredentialStore(context, "test-partner-session", refreshable = false)
        val receipt = KeystoreCredentialStore(context, "test-application-receipt", refreshable = false)
        try {
            donor.save(Credentials("synthetic-donor-access", "synthetic-donor-refresh", 1))
            partner.save(Credentials("synthetic-partner-access", "", 2))
            receipt.save(Credentials("synthetic-receipt", "", 3))
            for (name in listOf("test-donor-session", "test-partner-session", "test-application-receipt")) {
                assertTrue(name, File(context.noBackupFilesDir, "$name.enc").exists())
            }
            partner.clear()
            // Ending one entry leaves the others signed in, and none reads another's value.
            assertNull(partner.load())
            assertEquals("synthetic-donor-access", donor.load()?.access)
            assertEquals("synthetic-receipt", receipt.load()?.access)
            receipt.clear()
            assertEquals("synthetic-donor-access", donor.load()?.access)
        } finally { donor.clear(); partner.clear(); receipt.clear() }
    }
}
