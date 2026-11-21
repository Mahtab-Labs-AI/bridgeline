package com.wifiproxy.app.wifi

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.wifiproxy.app.settings.KeyValueStore
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.util.Base64
import java.util.Random
import javax.crypto.AEADBadTagException

private const val KEY = "wifi_credentials"
private const val VERSION = "v1"

/** User-facing, and safe to show or log: it carries no secret. */
const val CREDENTIALS_UNREADABLE_MESSAGE = "Couldn't read the saved Wi-Fi settings. Try again."

/** The stored credentials exist but can't be read right now (a transient Keystore error). Never
 *  to be treated as "no credentials": that would delete the saved devices and change the SSID. */
class CredentialsUnavailableException : Exception(CREDENTIALS_UNREADABLE_MESSAGE)

/** What [CredentialStore.read] found. */
sealed interface CredentialsRead {
    /** Nothing stored (or a blob that could never open again, now wiped). */
    data object Unset : CredentialsRead
    data class Present(val credentials: StoredCredentials) : CredentialsRead
    /** A blob exists but couldn't be opened this time; it is kept. */
    data object Unreadable : CredentialsRead
}

/**
 * SSID code, suffix and password, sealed as one blob (design spec §3 CredentialStore).
 *
 * A blob that can *never* open again reads as [CredentialsRead.Unset] and is removed, so the user
 * is simply asked for a password again: a failed GCM tag (wrong or replaced key), a key the
 * Keystore has permanently invalidated or can't recover, or a malformed blob. Any other
 * Keystore/crypto error may be transient (the Keystore daemon busy or restarting): [read] then
 * returns [CredentialsRead.Unreadable] *without* wiping, and reports only the exception's class
 * name to [onTransientError]. Callers must handle Unreadable explicitly, never as Unset.
 */
class CredentialStore(
    private val store: KeyValueStore,
    private val box: SecretBox,
    private val random: Random = SecureRandom(),
    private val onTransientError: (className: String) -> Unit = {},
) {
    fun read(): CredentialsRead {
        val encoded = store.getString(KEY) ?: return CredentialsRead.Unset
        val fields = try {
            String(box.open(Base64.getDecoder().decode(encoded)), Charsets.UTF_8).split('\n')
        } catch (e: Exception) {
            when {
                isPermanent(e) -> null
                e is GeneralSecurityException || e is ProviderException -> {
                    onTransientError(e::class.simpleName ?: "unknown")
                    return CredentialsRead.Unreadable
                }
                else -> throw e
            }
        }
        if (fields == null || fields.size != 4 || fields[0] != VERSION) {
            clear()
            return CredentialsRead.Unset
        }
        return CredentialsRead.Present(StoredCredentials(ssidCode = fields[1], suffix = fields[2], password = fields[3]))
    }

    /** The credentials when [read] finds them; null when unset *or* unreadable - use [read] where
     *  the difference matters. */
    fun get(): StoredCredentials? = (read() as? CredentialsRead.Present)?.credentials

    /** Whether a credentials blob is stored, without decrypting it (so no Keystore call): true
     *  for an unreadable blob too. */
    fun isSet(): Boolean = store.getString(KEY) != null

    /** Errors after which this blob can never be opened: wiping it is the only way forward.
     *  [IllegalArgumentException] is a malformed blob (bad Base64, or too short to hold an IV). */
    private fun isPermanent(e: Exception): Boolean =
        e is AEADBadTagException ||
            e is KeyPermanentlyInvalidatedException ||
            e is UnrecoverableKeyException ||
            e is IllegalArgumentException

    /** Keeps the stored SSID code, or picks a new one when none is stored. A caller that has just
     *  read [read] can pass its [StoredCredentials.ssidCode] as [ssidCode] to skip a second
     *  decrypt (a Keystore round trip); null still means "pick a new code". The default throws
     *  [CredentialsUnavailableException] rather than replace an unreadable blob's code. */
    fun set(suffix: String, password: String, ssidCode: String? = storedCodeForSet()): StoredCredentials {
        WifiCredentialRules.suffixError(suffix)?.let { throw IllegalArgumentException(it) }
        WifiCredentialRules.passwordError(password)?.let { throw IllegalArgumentException(it) }
        val code = ssidCode ?: WifiCredentialRules.newSsidCode(random)
        val credentials = StoredCredentials(code, suffix, password)
        val plain = listOf(VERSION, code, suffix, password).joinToString("\n").toByteArray(Charsets.UTF_8)
        store.putString(KEY, Base64.getEncoder().encodeToString(box.seal(plain)))
        return credentials
    }

    private fun storedCodeForSet(): String? = when (val r = read()) {
        CredentialsRead.Unset -> null
        is CredentialsRead.Present -> r.credentials.ssidCode
        CredentialsRead.Unreadable -> throw CredentialsUnavailableException()
    }

    fun clear() = store.putString(KEY, null)
}
