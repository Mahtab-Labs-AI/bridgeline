package com.wifiproxy.app.wifi

import com.wifiproxy.app.settings.KeyValueStore
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.Random

private const val KEY = "wifi_credentials"
private const val VERSION = "v1"

/** SSID code, suffix and password, sealed as one blob (design spec §3 CredentialStore). A blob
 *  that no longer opens - e.g. the Keystore key was lost - reads as "not set" and is removed, so
 *  the user is simply asked for a password again. */
class CredentialStore(
    private val store: KeyValueStore,
    private val box: SecretBox,
    private val random: Random = SecureRandom(),
) {
    fun get(): StoredCredentials? {
        val encoded = store.getString(KEY) ?: return null
        val fields = try {
            String(box.open(Base64.getDecoder().decode(encoded)), Charsets.UTF_8).split('\n')
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (fields == null || fields.size != 4 || fields[0] != VERSION) {
            clear()
            return null
        }
        return StoredCredentials(ssidCode = fields[1], suffix = fields[2], password = fields[3])
    }

    /** Keeps the stored SSID code, or picks a new one when none is stored. A caller that has just
     *  read [get] can pass its [StoredCredentials.ssidCode] as [ssidCode] to skip a second decrypt
     *  (a Keystore round trip); null still means "pick a new code". */
    fun set(suffix: String, password: String, ssidCode: String? = get()?.ssidCode): StoredCredentials {
        WifiCredentialRules.suffixError(suffix)?.let { throw IllegalArgumentException(it) }
        WifiCredentialRules.passwordError(password)?.let { throw IllegalArgumentException(it) }
        val code = ssidCode ?: WifiCredentialRules.newSsidCode(random)
        val credentials = StoredCredentials(code, suffix, password)
        val plain = listOf(VERSION, code, suffix, password).joinToString("\n").toByteArray(Charsets.UTF_8)
        store.putString(KEY, Base64.getEncoder().encodeToString(box.seal(plain)))
        return credentials
    }

    fun clear() = store.putString(KEY, null)
}
