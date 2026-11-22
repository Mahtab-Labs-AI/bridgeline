# Plan 2b: Wi-Fi Direct Sharing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Spec: `docs/superpowers/specs/2026-11-19-android-wifi-proxy-design.md` §2–§8 (§8a for how USB coexists). The README "Handoff notes for Plan 2" are binding contracts.

**Goal:** Up to 3 phones or laptops join the host's `DIRECT-xy-…` Wi-Fi Direct group with the user's password, fetch a per-device PAC URL, and browse through the host's cellular data. USB sharing keeps working alongside.

**Architecture:**
- A pure, JVM-tested `P2pGroupLink` state machine creates, verifies and recreates the group through a thin `P2pApi` seam. `AndroidP2pApi` is the only code that touches `WifiP2pManager`.
- The existing controller is renamed `SessionController`. It runs up to two `ProxyServer`s that share one `ClientRegistry`, one `Egress` and one filter:
  - the USB listener on `127.0.0.1:8081`, optional;
  - the Wi-Fi listener on the group-owner IP, which follows the link state.
- Slots and settings persist across sessions and process death. The credentials are encrypted with an Android Keystore key.

**Tech Stack:** Kotlin 2.2.20, coroutines 1.10.2, Jetpack Compose (BOM 2026.06.01), JUnit 5 + kotlinx-coroutines-test, AGP 8.13.2, Gradle 8.14.3.

## Global Constraints

- `minSdk 29`, `compileSdk`/`targetSdk 36`. Do not bump: newer Compose BOMs need compileSdk 37 and AGP 9.
- **Public APIs only. No hidden APIs. No root.**
- **Bound or refuse:** every upstream socket and DNS lookup goes through `CellularEgress`. Never fall back to the default network.
- `ProxyServer` binds **only** the group-owner IPv4 (Wi-Fi) or `127.0.0.1` (USB). Never `0.0.0.0` or `::`.
- `:proxy-core` is not changed in this plan.
- Revoke only through `ProxyServer.revoke`. `ClientRegistry.revoke` is `internal`.
- Call `ClientRegistry.clearBindings()` on group teardown or recreate, and on password change.
- Every `ProxyServer.stop()` runs off the main thread (it joins the loop thread for up to about 1 s).
- **No URLs, hostnames, tokens or passwords in logs or `toString()`.**
- Clients: 1–3, configurable; the pinned USB slot counts toward the limit.
- Wi-Fi password: 8–63 ASCII characters. No default ships.
- SSID is `DIRECT-<2 chars>-<suffix>`, at most 32 bytes in total.
- Every new dependency must be Android-compatible on API 29. This plan adds none.
- Build and test from PowerShell in one command:
  ```powershell
  Set-Location C:\source\android-wifi-proxy; . .\scripts\env.ps1; .\gradlew.bat :proxy-core:test :app:assembleDebug :app:testDebugUnitTest
  ```
  Before this plan: core 142 tests, app 90 tests, zero warnings. Keep zero warnings.
- **Commits:** commit by explicit paths only. Check `git diff --cached --name-only` before every commit. End every message with `Co-Authored-By: Claude Opus 5.5 (1M context)`.
- **Branch:** `feature/wifi-direct` (off `main` at `22d5610`).
- Never change the host PC's proxy settings in tests.

## Decisions this plan makes (deviations from the spec, recorded in Task 9)

1. **No `FAILED` phase.** `SessionPhase` keeps its five values. A link that needs the user (firmware ignored the SSID or password, Wi-Fi Direct unsupported, permission missing, 10 consecutive failures) shows as `DEGRADED` with that message as the reason, and stops retrying. USB keeps working. The user fixes the problem and restarts sharing.
2. **Wi-Fi Direct runs only when credentials are set.** A session with no password is USB-only. Start is refused only when there is neither USB sharing nor a password. The spec says the first session can't start without a password; that predates USB.
3. **`unbind(ip)` on client leave is not implemented.** `WifiP2pGroup.clientList` gives MAC addresses, not IPs. Rebinding by PAC fetch already moves a slot. Pinned slots and `clearBindings()` behave as specified.
4. **Devices are added and revoked only while sharing.** Adding needs the group-owner IP for the PAC URL, and revoking goes through a live `ProxyServer`.
5. **Changing the password deletes every Wi-Fi slot** (tokens included), matching the UI's "must be re-invited" dialog and spec risk #4 ("rotate it after sharing with someone untrusted").
6. **The SSID suffix allows `A–Z a–z 0–9 _ -`, 1–22 characters.** This keeps the SSID within 32 bytes and avoids OEM firmware rejecting unusual characters.

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `app/src/main/kotlin/com/wifiproxy/app/settings/KeyValueStore.kt` | Create | String key-value seam over `SharedPreferences` (map fake in tests) |
| `app/src/main/kotlin/com/wifiproxy/app/wifi/WifiCredentialRules.kt` | Create | Pure SSID suffix/password validation, SSID code generation, `StoredCredentials` |
| `app/src/main/kotlin/com/wifiproxy/app/wifi/SecretBox.kt` | Create | AES-GCM seal/open; Android Keystore key provider |
| `app/src/main/kotlin/com/wifiproxy/app/wifi/CredentialStore.kt` | Create | Encrypted persistence of SSID code + suffix + password |
| `app/src/main/kotlin/com/wifiproxy/app/wifi/SlotStore.kt` | Create | `PersistedSlot`, codec, persistence of Wi-Fi slot records + platform |
| `app/src/main/kotlin/com/wifiproxy/app/wifi/GroupLink.kt` | Create | `LinkState`, `LinkConfig`, `LinkManager`, `P2pApi` seam types |
| `app/src/main/kotlin/com/wifiproxy/app/wifi/P2pGroupLink.kt` | Create | The pure group state machine (create/verify/recreate/backoff) |
| `app/src/main/kotlin/com/wifiproxy/app/wifi/AndroidP2pApi.kt` | Create | `WifiP2pManager` + broadcast adapter implementing `P2pApi` |
| `app/src/main/kotlin/com/wifiproxy/app/service/SessionController.kt` | Rename from `UsbSessionController.kt` | Session state machine for both listeners |
| `app/src/main/kotlin/com/wifiproxy/app/service/ProxySessionService.kt` | Modify | Builds the real link/stores; START_STICKY restart; WifiLock |
| `app/src/main/kotlin/com/wifiproxy/app/settings/HostConfigSource.kt` | Create | Composes prefs + credentials into `HostSettings` and `SessionConfig` |
| `app/src/main/kotlin/com/wifiproxy/app/settings/AppPreferences.kt` | Modify | maxClients, wifiPort, prefer5Ghz, sessionDesired |
| `app/src/main/kotlin/com/wifiproxy/app/session/SessionRepository.kt` | Modify | `SessionUi.wifiUp` |
| `app/src/main/kotlin/com/wifiproxy/app/session/ServiceSessionRepository.kt` | Modify | Real addDevice/inviteFor/settings/checks |
| `app/src/main/kotlin/com/wifiproxy/app/session/FakeSessionRepository.kt` | Modify | Sets `wifiUp` |
| `app/src/main/kotlin/com/wifiproxy/app/devices/AddDeviceScreen.kt` | Modify | Real-mode Wi-Fi device list + invite sheet |
| `app/src/main/kotlin/com/wifiproxy/app/settings/SettingsScreen.kt` | Modify | Real USB switch, inline credential validation |
| `app/src/main/kotlin/com/wifiproxy/app/MainActivity.kt` | Modify | Requests Nearby devices / location permission at Start |
| `app/src/main/AndroidManifest.xml` | Modify | Wi-Fi/P2P permissions, `wifi.direct` feature |
| Tests under `app/src/test/kotlin/com/wifiproxy/app/wifi/` and `.../service/` | Create/Modify | One test file per pure unit |

---

### Task 1: Credentials — rules, encryption, store

**Files:**
- Create: `app/src/main/kotlin/com/wifiproxy/app/settings/KeyValueStore.kt`
- Create: `app/src/main/kotlin/com/wifiproxy/app/wifi/WifiCredentialRules.kt`
- Create: `app/src/main/kotlin/com/wifiproxy/app/wifi/SecretBox.kt`
- Create: `app/src/main/kotlin/com/wifiproxy/app/wifi/CredentialStore.kt`
- Test: `app/src/test/kotlin/com/wifiproxy/app/wifi/WifiCredentialRulesTest.kt`
- Test: `app/src/test/kotlin/com/wifiproxy/app/wifi/CredentialStoreTest.kt`

**Interfaces:**
- Produces:
  - `interface KeyValueStore { fun getString(key: String): String?; fun putString(key: String, value: String?) }`, plus `class SharedPrefsStore(prefs: SharedPreferences) : KeyValueStore`;
  - `data class StoredCredentials(val ssidCode: String, val suffix: String, val password: String)` with `val ssid: String` and a redacted `toString()`;
  - `object WifiCredentialRules { const val SUFFIX_MAX = 22; fun suffixError(suffix: String): String?; fun passwordError(password: String): String?; fun newSsidCode(random: java.util.Random): String }`;
  - `interface SecretBox { fun seal(plain: ByteArray): ByteArray; fun open(sealed: ByteArray): ByteArray }`, plus `class AesGcmSecretBox(key: () -> javax.crypto.SecretKey) : SecretBox` and `object KeystoreKeys { fun getOrCreate(alias: String): SecretKey }`;
  - `class CredentialStore(store: KeyValueStore, box: SecretBox, random: java.util.Random = SecureRandom()) { fun get(): StoredCredentials?; fun set(suffix: String, password: String): StoredCredentials; fun clear() }`.

- [ ] **Step 1: Write the failing rules test**

```kotlin
package com.wifiproxy.app.wifi

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WifiCredentialRulesTest {
    @Test
    fun `suffix accepts letters digits dash underscore up to 22`() {
        assertNull(WifiCredentialRules.suffixError("HostPhone"))
        assertNull(WifiCredentialRules.suffixError("a-b_C9"))
        assertNull(WifiCredentialRules.suffixError("x".repeat(22)))
    }

    @Test
    fun `suffix rejects empty, too long, spaces and non-ascii`() {
        assertNotNull(WifiCredentialRules.suffixError(""))
        assertNotNull(WifiCredentialRules.suffixError("x".repeat(23)))
        assertNotNull(WifiCredentialRules.suffixError("my phone"))
        assertNotNull(WifiCredentialRules.suffixError("téléphone"))
    }

    @Test
    fun `password must be 8 to 63 printable ascii`() {
        assertNull(WifiCredentialRules.passwordError("12345678"))
        assertNull(WifiCredentialRules.passwordError("correct horse battery staple!"))
        assertNull(WifiCredentialRules.passwordError("p".repeat(63)))
        assertNotNull(WifiCredentialRules.passwordError("1234567"))
        assertNotNull(WifiCredentialRules.passwordError("p".repeat(64)))
        assertNotNull(WifiCredentialRules.passwordError("pass\nword1"))
        assertNotNull(WifiCredentialRules.passwordError("pässwörd1"))
    }

    @Test
    fun `ssid code is two lowercase alphanumerics`() {
        val random = Random(42)
        repeat(100) {
            val code = WifiCredentialRules.newSsidCode(random)
            assertEquals(2, code.length)
            assertTrue(code.all { it in 'a'..'z' || it in '0'..'9' })
        }
    }

    @Test
    fun `ssid is prefix plus suffix and fits 32 bytes, toString redacts password`() {
        val c = StoredCredentials(ssidCode = "k3", suffix = "x".repeat(22), password = "secret-password")
        assertEquals("DIRECT-k3-" + "x".repeat(22), c.ssid)
        assertEquals(32, c.ssid.toByteArray(Charsets.UTF_8).size)
        assertFalse(c.toString().contains("secret-password"))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails to compile** (`WifiCredentialRules` is not defined)

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*WifiCredentialRulesTest*"`
Expected: FAIL, `Unresolved reference 'WifiCredentialRules'`.

- [ ] **Step 3: Implement `KeyValueStore.kt` and `WifiCredentialRules.kt`**

```kotlin
// settings/KeyValueStore.kt
package com.wifiproxy.app.settings

import android.content.SharedPreferences
import androidx.core.content.edit

/** The one persistence seam the Wi-Fi stores need, so they can be tested with a map on the JVM. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) = prefs.edit { if (value == null) remove(key) else putString(key, value) }
}
```

```kotlin
// wifi/WifiCredentialRules.kt
package com.wifiproxy.app.wifi

import java.util.Random

/** What the host user configures for the Wi-Fi Direct group (design spec §4 Layer 1). [ssidCode]
 *  is the `xy` in `DIRECT-xy-`: chosen once at random and kept, so the SSID never changes and
 *  clients auto-rejoin. */
data class StoredCredentials(val ssidCode: String, val suffix: String, val password: String) {
    val ssid: String get() = "DIRECT-$ssidCode-$suffix"
    override fun toString() = "StoredCredentials(ssid=$ssid, password=****)"
}

object WifiCredentialRules {
    /** 32-byte SSID limit minus the 10-byte `DIRECT-xy-` prefix. */
    const val SUFFIX_MAX = 22
    private val SUFFIX = Regex("[A-Za-z0-9_-]{1,$SUFFIX_MAX}")
    private const val CODE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun suffixError(suffix: String): String? = when {
        suffix.isEmpty() -> "Enter a network name"
        suffix.length > SUFFIX_MAX -> "At most $SUFFIX_MAX characters"
        !SUFFIX.matches(suffix) -> "Use letters, digits, - and _ only"
        else -> null
    }

    /** WPA2 passphrase rule: 8-63 printable ASCII characters. */
    fun passwordError(password: String): String? = when {
        password.length < 8 -> "At least 8 characters"
        password.length > 63 -> "At most 63 characters"
        password.any { it.code !in 0x20..0x7E } -> "Use plain ASCII characters only"
        else -> null
    }

    fun newSsidCode(random: Random): String =
        String(CharArray(2) { CODE_CHARS[random.nextInt(CODE_CHARS.length)] })
}
```

- [ ] **Step 4: Run the rules test and confirm it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*WifiCredentialRulesTest*"`
Expected: PASS (5 tests).

- [ ] **Step 5: Write the failing store test.** It uses a plain JVM AES key; the Keystore isn't available on the JVM.

```kotlin
package com.wifiproxy.app.wifi

import com.wifiproxy.app.settings.KeyValueStore
import java.util.Random
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MapStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

class CredentialStoreTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun box() = AesGcmSecretBox { key }

    @Test
    fun `nothing stored reads as null`() {
        assertNull(CredentialStore(MapStore(), box()).get())
    }

    @Test
    fun `set then get round-trips and the stored value is not plaintext`() {
        val kv = MapStore()
        val saved = CredentialStore(kv, box(), Random(1)).set("HostPhone", "correct horse battery")
        val loaded = CredentialStore(kv, box()).get()
        assertEquals(saved, loaded)
        assertFalse(kv.map.values.single().contains("correct horse"))
    }

    @Test
    fun `changing suffix or password keeps the ssid code`() {
        val store = CredentialStore(MapStore(), box(), Random(1))
        val first = store.set("HostPhone", "password-one")
        val second = store.set("OtherName", "password-two")
        assertEquals(first.ssidCode, second.ssidCode)
        assertEquals("DIRECT-${first.ssidCode}-OtherName", second.ssid)
    }

    @Test
    fun `undecryptable data reads as null and is cleared`() {
        val kv = MapStore()
        CredentialStore(kv, box()).set("HostPhone", "password-one")
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(CredentialStore(kv, AesGcmSecretBox { otherKey }).get())
        assertTrue(kv.map.isEmpty())
    }

    @Test
    fun `set rejects invalid input`() {
        val store = CredentialStore(MapStore(), box())
        kotlin.test.assertFailsWith<IllegalArgumentException> { store.set("bad name", "password-one") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { store.set("HostPhone", "short") }
    }
}
```

- [ ] **Step 6: Run it and confirm it fails to compile**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*CredentialStoreTest*"`
Expected: FAIL, `Unresolved reference 'CredentialStore'`.

- [ ] **Step 7: Implement `SecretBox.kt` and `CredentialStore.kt`**

```kotlin
// wifi/SecretBox.kt
package com.wifiproxy.app.wifi

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    /** Throws [java.security.GeneralSecurityException] if [sealed] wasn't sealed with this key. */
    fun open(sealed: ByteArray): ByteArray
}

/** AES-256-GCM, `IV (12 bytes) || ciphertext+tag`. The IV is generated by the provider
 *  (the Android Keystore refuses caller-chosen IVs by default). */
class AesGcmSecretBox(private val key: () -> SecretKey) : SecretBox {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "sealed data too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

object KeystoreKeys {
    fun getOrCreate(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
```

```kotlin
// wifi/CredentialStore.kt
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

    fun set(suffix: String, password: String): StoredCredentials {
        WifiCredentialRules.suffixError(suffix)?.let { throw IllegalArgumentException(it) }
        WifiCredentialRules.passwordError(password)?.let { throw IllegalArgumentException(it) }
        val code = get()?.ssidCode ?: WifiCredentialRules.newSsidCode(random)
        val credentials = StoredCredentials(code, suffix, password)
        val plain = listOf(VERSION, code, suffix, password).joinToString("\n").toByteArray(Charsets.UTF_8)
        store.putString(KEY, Base64.getEncoder().encodeToString(box.seal(plain)))
        return credentials
    }

    fun clear() = store.putString(KEY, null)
}
```

- [ ] **Step 8: Run the full app suite and confirm it passes with zero warnings**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: PASS, 90 + 10 = 100 tests.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/wifiproxy/app/settings/KeyValueStore.kt app/src/main/kotlin/com/wifiproxy/app/wifi/WifiCredentialRules.kt app/src/main/kotlin/com/wifiproxy/app/wifi/SecretBox.kt app/src/main/kotlin/com/wifiproxy/app/wifi/CredentialStore.kt app/src/test/kotlin/com/wifiproxy/app/wifi/WifiCredentialRulesTest.kt app/src/test/kotlin/com/wifiproxy/app/wifi/CredentialStoreTest.kt
git commit -m "feat(app): Wi-Fi credential rules and Keystore-encrypted CredentialStore" -m "Co-Authored-By: Claude Opus 5.5 (1M context)"
```

---

### Task 2: Slot persistence

**Files:**
- Create: `app/src/main/kotlin/com/wifiproxy/app/wifi/SlotStore.kt`
- Test: `app/src/test/kotlin/com/wifiproxy/app/wifi/SlotStoreTest.kt`

**Interfaces:**
- Consumes: `KeyValueStore` (Task 1); `com.wifiproxy.core.clients.SlotRecord`; `ClientRegistry.TOKEN_PATTERN`; `com.wifiproxy.app.session.ClientPlatform`.
- Produces:
  - `data class PersistedSlot(val record: SlotRecord, val platform: ClientPlatform)`;
  - `interface SlotPersistence { fun load(): List<PersistedSlot>; fun save(slots: List<PersistedSlot>) }`;
  - `class SlotStore(store: KeyValueStore) : SlotPersistence` with `fun clear()`;
  - `object PersistedSlotsCodec { fun encode(slots: List<PersistedSlot>): String; fun decode(text: String?): List<PersistedSlot> }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.wifiproxy.app.wifi

import com.wifiproxy.app.session.ClientPlatform
import com.wifiproxy.app.settings.KeyValueStore
import com.wifiproxy.core.clients.SlotRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlotStoreTest {
    private val iphone = PersistedSlot(
        SlotRecord(id = "0b9f2c1e-0000-4000-8000-000000000001", name = "Ben's iPhone | 2", token = "A".repeat(22), createdAtMs = 1_700_000_000_000),
        ClientPlatform.IPHONE,
    )
    private val pixel = PersistedSlot(
        SlotRecord(id = "0b9f2c1e-0000-4000-8000-000000000002", name = "Pixel 7\nline", token = "b_-".repeat(7) + "c", createdAtMs = 5),
        ClientPlatform.ANDROID,
    )

    @Test
    fun `encode then decode round-trips names with separators and newlines`() {
        assertEquals(listOf(iphone, pixel), PersistedSlotsCodec.decode(PersistedSlotsCodec.encode(listOf(iphone, pixel))))
    }

    @Test
    fun `null or empty decodes to nothing`() {
        assertTrue(PersistedSlotsCodec.decode(null).isEmpty())
        assertTrue(PersistedSlotsCodec.decode("").isEmpty())
    }

    @Test
    fun `malformed lines and invalid tokens are skipped, not fatal`() {
        val good = PersistedSlotsCodec.encode(listOf(iphone))
        val text = listOf("garbage", "v1|id|bmFtZQ|short-token|5|ANDROID", "v1|id|!!!|${"A".repeat(22)}|5|ANDROID", good, "v1|id|bmFtZQ|${"A".repeat(22)}|5|TOASTER").joinToString("\n")
        assertEquals(listOf(iphone), PersistedSlotsCodec.decode(text))
    }

    @Test
    fun `store saves, loads and clears`() {
        val kv = object : KeyValueStore {
            var value: String? = null
            override fun getString(key: String) = value
            override fun putString(key: String, value: String?) { this.value = value }
        }
        val store = SlotStore(kv)
        store.save(listOf(pixel))
        assertEquals(listOf(pixel), SlotStore(kv).load())
        store.clear()
        assertTrue(store.load().isEmpty())
    }
}
```

- [ ] **Step 2: Run it and confirm it fails to compile**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*SlotStoreTest*"`
Expected: FAIL, `Unresolved reference 'PersistedSlot'`.

- [ ] **Step 3: Implement `SlotStore.kt`**

```kotlin
package com.wifiproxy.app.wifi

import com.wifiproxy.app.session.ClientPlatform
import com.wifiproxy.app.settings.KeyValueStore
import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.clients.SlotRecord
import java.util.Base64

/** A Wi-Fi device slot as persisted between sessions (design spec §4: id, name, token,
 *  createdAt; never the IP binding), plus the platform the invite was made for. */
data class PersistedSlot(val record: SlotRecord, val platform: ClientPlatform)

interface SlotPersistence {
    fun load(): List<PersistedSlot>
    fun save(slots: List<PersistedSlot>)
}

/** One line per slot: `v1|id|base64url(name)|token|createdAtMs|PLATFORM`. Ids are UUIDs and
 *  tokens match [ClientRegistry.TOKEN_PATTERN], so neither can contain `|`; the free-text name is
 *  base64url-encoded. A line that doesn't parse is dropped rather than failing the whole load -
 *  that slot's device just needs a new invite. */
object PersistedSlotsCodec {
    fun encode(slots: List<PersistedSlot>): String = slots.joinToString("\n") { s ->
        val name = Base64.getUrlEncoder().withoutPadding().encodeToString(s.record.name.toByteArray(Charsets.UTF_8))
        listOf("v1", s.record.id, name, s.record.token, s.record.createdAtMs.toString(), s.platform.name).joinToString("|")
    }

    fun decode(text: String?): List<PersistedSlot> =
        text.orEmpty().lineSequence().mapNotNull(::decodeLine).toList()

    private fun decodeLine(line: String): PersistedSlot? {
        val f = line.split('|')
        if (f.size != 6 || f[0] != "v1" || f[1].isEmpty()) return null
        if (!ClientRegistry.TOKEN_PATTERN.matches(f[3])) return null
        val name = runCatching { String(Base64.getUrlDecoder().decode(f[2]), Charsets.UTF_8) }.getOrNull() ?: return null
        val createdAt = f[4].toLongOrNull() ?: return null
        val platform = ClientPlatform.entries.firstOrNull { it.name == f[5] && it != ClientPlatform.LAPTOP } ?: return null
        return PersistedSlot(SlotRecord(f[1], name, f[3], createdAt), platform)
    }
}

/** Tokens are stored in app-private preferences (`allowBackup=false`); they only admit a device
 *  that also knows the Wi-Fi password (spec §4 Layer 1 is the real trust boundary). */
class SlotStore(private val store: KeyValueStore) : SlotPersistence {
    override fun load(): List<PersistedSlot> = PersistedSlotsCodec.decode(store.getString(KEY))
    override fun save(slots: List<PersistedSlot>) = store.putString(KEY, PersistedSlotsCodec.encode(slots))
    fun clear() = store.putString(KEY, null)

    private companion object {
        const val KEY = "wifi_slots"
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*SlotStoreTest*"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/wifiproxy/app/wifi/SlotStore.kt app/src/test/kotlin/com/wifiproxy/app/wifi/SlotStoreTest.kt
git commit -m "feat(app): persist Wi-Fi slot records between sessions" -m "Co-Authored-By: Claude Opus 5.5 (1M context)"
```

---

### Task 3: `P2pGroupLink` — the group state machine (pure, TDD)

**Files:**
- Create: `app/src/main/kotlin/com/wifiproxy/app/wifi/GroupLink.kt`
- Create: `app/src/main/kotlin/com/wifiproxy/app/wifi/P2pGroupLink.kt`
- Test: `app/src/test/kotlin/com/wifiproxy/app/wifi/P2pGroupLinkTest.kt`

**Interfaces:**
- Consumes: `com.wifiproxy.core.WifiCredentials(ssid, password)`.
- Produces (exact; Tasks 4–8 rely on these names):

```kotlin
package com.wifiproxy.app.wifi

import com.wifiproxy.core.WifiCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address

data class LinkConfig(val credentials: WifiCredentials, val prefer5Ghz: Boolean)

sealed interface LinkState {
    data object Idle : LinkState
    /** Not usable right now, but [P2pGroupLink] is working on it; [reason] is user-facing. */
    data class Down(val reason: String) : LinkState
    data class Up(val ownerAddress: Inet4Address, val credentials: WifiCredentials) : LinkState
    /** Needs the user (spec §6 "Failed(needsUser)"); no further retries until stop()/start(). */
    data class Failed(val message: String) : LinkState
}

/** What [com.wifiproxy.app.service.SessionController] needs from the Wi-Fi Direct link. */
interface LinkManager {
    val state: StateFlow<LinkState>
    /** Starts (or restarts) the link with [config]. Returns immediately; follow [state]. */
    fun start(config: LinkConfig)
    /** Stops the link and removes the group. Suspends until the group is removed. */
    suspend fun stop()
    /** Asks the link to re-check that its group still exists (the 30 s watchdog). */
    fun checkHealth()
}

enum class GroupBand { GHZ_5, AUTO }
data class GroupRequest(val ssid: String, val passphrase: String, val band: GroupBand) {
    override fun toString() = "GroupRequest(ssid=$ssid, passphrase=****, band=$band)"
}
data class GroupInfo(val networkName: String, val passphrase: String?, val ownerAddress: Inet4Address?) {
    override fun toString() = "GroupInfo(networkName=$networkName, passphrase=****, ownerAddress=$ownerAddress)"
}

sealed interface CreateResult {
    data object Success : CreateResult
    /** [reason] is a `WifiP2pManager` failure code, or [MISSING_PERMISSION]. */
    data class Failure(val reason: Int) : CreateResult

    companion object {
        const val ERROR = 0            // WifiP2pManager.ERROR
        const val P2P_UNSUPPORTED = 1  // WifiP2pManager.P2P_UNSUPPORTED
        const val BUSY = 2             // WifiP2pManager.BUSY
        const val MISSING_PERMISSION = -1
    }
}

/** The thin Android seam; [AndroidP2pApi] is the only real implementation. */
interface P2pApi {
    /** Wi-Fi P2P enabled state (follows Wi-Fi on/off). */
    val p2pEnabled: StateFlow<Boolean>
    /** Emits whenever the system reports a P2P connection/group change. */
    val connectionChanged: Flow<Unit>
    suspend fun createGroup(request: GroupRequest): CreateResult
    /** Removes our group if one exists. Never throws. */
    suspend fun removeGroup()
    /** The current group as the system reports it, or null when there is none. */
    suspend fun groupInfo(): GroupInfo?
}
```

- `class P2pGroupLink(api: P2pApi, scope: CoroutineScope, deviceModel: String, maxConsecutiveFailures: Int = 10, infoRetryDelayMs: Long = 500L, infoRetries: Int = 6) : LinkManager`
- User-facing reasons (constants in `P2pGroupLink.kt`, used by tests and the UI):
  - `REASON_CREATING = "Creating the Wi-Fi Direct group"`
  - `REASON_RETRYING = "Wi-Fi Direct group failed; retrying"`
  - `REASON_WIFI_OFF = "Wi-Fi is off"`
  - `REASON_LOST = "Wi-Fi Direct group lost; recreating"`

**Behaviour:**
1. `start(config)` cancels any previous run and launches one on `scope`.
2. **Wi-Fi off:** if `p2pEnabled` is false, set `Down(REASON_WIFI_OFF)` and wait until it's true.
3. **Create:**
   - Set `Down(REASON_CREATING)` (or `REASON_RETRYING` after a failure), call `removeGroup()`, then `createGroup` with band `GHZ_5` if `prefer5Ghz`, else `AUTO`.
   - If `GHZ_5` fails with anything but `BUSY`, retry once with `AUTO` in the same attempt.
4. **Verify:** poll `groupInfo()` up to `infoRetries` times, `infoRetryDelayMs` apart, until it's non-null with an `ownerAddress`. Then:
   - **Name or passphrase mismatch:** call `removeGroup()`, then `Failed("This phone ($deviceModel) ignored the requested Wi-Fi name or password. Wi-Fi Direct sharing can't be used on it.")`. Stop running.
   - **Match:** set `Up(ownerAddress, credentials)` and reset the failure count.
5. **Failures:**
   - `P2P_UNSUPPORTED` → `Failed("This phone doesn't support Wi-Fi Direct.")`.
   - `MISSING_PERMISSION` → `Failed("Allow the Nearby devices permission (Location on Android 10–12), then start sharing again.")`.
   - Anything else, or no group info: count a failure. At `maxConsecutiveFailures` → `Failed("Couldn't create the Wi-Fi Direct group (error <reason>).")`. Otherwise wait `min(1000 shl (n-1), 60000)` ms and try again.
6. **While Up:** wait for a `connectionChanged` emission, a `checkHealth()` call, or `p2pEnabled` going false.
   - On the first two, re-query `groupInfo()`. If it's null, the group is lost: set `Down(REASON_LOST)` and recreate.
   - If `p2pEnabled` goes false, go back to step 2.
7. `stop()` cancels the run and joins it, calls `removeGroup()`, and sets `Idle`.

- [ ] **Step 1: Write `GroupLink.kt` exactly as in Interfaces above.** It's types only, so it needs no test of its own.

- [ ] **Step 2: Write the failing test**

```kotlin
package com.wifiproxy.app.wifi

import com.wifiproxy.core.WifiCredentials
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val OWNER = InetAddress.getByName("192.168.49.1") as Inet4Address
private val CREDS = WifiCredentials("DIRECT-k3-HostPhone", "correct horse battery")

private class FakeP2pApi : P2pApi {
    override val p2pEnabled = MutableStateFlow(true)
    private val connection = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    override val connectionChanged = connection
    val requests = mutableListOf<GroupRequest>()
    /** Consumed one per createGroup call; empty means Success. */
    val results = ArrayDeque<CreateResult>()
    /** What the "firmware" reports back for a created group. */
    var report: (GroupRequest) -> GroupInfo = { GroupInfo(it.ssid, it.passphrase, OWNER) }
    var group: GroupInfo? = null
    var removeCalls = 0

    override suspend fun createGroup(request: GroupRequest): CreateResult {
        requests += request
        val result = results.removeFirstOrNull() ?: CreateResult.Success
        if (result == CreateResult.Success) group = report(request)
        return result
    }

    override suspend fun removeGroup() {
        removeCalls++
        group = null
    }

    override suspend fun groupInfo(): GroupInfo? = group

    fun loseGroup() {
        group = null
        connection.tryEmit(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class P2pGroupLinkTest {
    private fun TestScope.newLink(api: FakeP2pApi) = P2pGroupLink(api, backgroundScope, deviceModel = "Pixel 11")

    @Test
    fun `start creates a 5 GHz group with the requested credentials and goes Up`() = runTest {
        val api = FakeP2pApi()
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = true))
        runCurrent()
        assertEquals(LinkState.Up(OWNER, CREDS), link.state.value)
        assertEquals(listOf(GroupRequest(CREDS.ssid, CREDS.password, GroupBand.GHZ_5)), api.requests)
    }

    @Test
    fun `5 GHz failure falls back to AUTO in the same attempt`() = runTest {
        val api = FakeP2pApi().apply { results += CreateResult.Failure(CreateResult.ERROR) }
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = true))
        runCurrent()
        assertIs<LinkState.Up>(link.state.value)
        assertEquals(listOf(GroupBand.GHZ_5, GroupBand.AUTO), api.requests.map { it.band })
    }

    @Test
    fun `firmware that ignores the credentials fails and removes the group`() = runTest {
        val api = FakeP2pApi().apply { report = { GroupInfo("DIRECT-zz-Android_1234", "randompass", OWNER) } }
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()
        advanceTimeBy(120_000)
        val state = assertIs<LinkState.Failed>(link.state.value)
        assertTrue("Pixel 11" in state.message)
        assertEquals(1, api.requests.size)
        assertEquals(null, api.group)
    }

    @Test
    fun `wifi off waits without creating, then creates when wifi comes back`() = runTest {
        val api = FakeP2pApi().apply { p2pEnabled.value = false }
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()
        assertEquals(LinkState.Down(REASON_WIFI_OFF), link.state.value)
        assertTrue(api.requests.isEmpty())
        api.p2pEnabled.value = true
        runCurrent()
        assertIs<LinkState.Up>(link.state.value)
    }

    @Test
    fun `a lost group is recreated with the same credentials`() = runTest {
        val api = FakeP2pApi()
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()
        api.loseGroup()
        runCurrent()
        assertIs<LinkState.Up>(link.state.value)
        assertEquals(2, api.requests.size)
        assertEquals(api.requests[0], api.requests[1])
    }

    @Test
    fun `wifi turning off while up goes Down then recovers`() = runTest {
        val api = FakeP2pApi()
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()
        api.p2pEnabled.value = false
        runCurrent()
        assertEquals(LinkState.Down(REASON_WIFI_OFF), link.state.value)
        api.p2pEnabled.value = true
        runCurrent()
        assertIs<LinkState.Up>(link.state.value)
    }

    @Test
    fun `health check notices a group that vanished without an event`() = runTest {
        val api = FakeP2pApi()
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()
        api.group = null
        link.checkHealth()
        runCurrent()
        assertEquals(2, api.requests.size)
        assertIs<LinkState.Up>(link.state.value)
    }

    @Test
    fun `failures back off exponentially and give up after 10`() = runTest {
        val api = FakeP2pApi().apply { repeat(10) { results += CreateResult.Failure(CreateResult.BUSY) } }
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()
        assertEquals(1, api.requests.size)
        assertEquals(LinkState.Down(REASON_RETRYING), link.state.value)
        advanceTimeBy(999); runCurrent(); assertEquals(1, api.requests.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, api.requests.size)   // after 1 s
        advanceTimeBy(2_000); runCurrent(); assertEquals(3, api.requests.size) // after 2 s more
        advanceTimeBy(600_000); runCurrent()
        assertEquals(10, api.requests.size)
        assertIs<LinkState.Failed>(link.state.value)
    }

    @Test
    fun `unsupported and missing permission fail immediately`() = runTest {
        for (reason in listOf(CreateResult.P2P_UNSUPPORTED, CreateResult.MISSING_PERMISSION)) {
            val api = FakeP2pApi().apply { results += CreateResult.Failure(reason) }
            val link = newLink(api)
            link.start(LinkConfig(CREDS, prefer5Ghz = false))
            runCurrent()
            assertIs<LinkState.Failed>(link.state.value)
            assertEquals(1, api.requests.size)
        }
    }

    @Test
    fun `stop removes the group and goes Idle for good`() = runTest {
        val api = FakeP2pApi()
        val link = newLink(api)
        link.start(LinkConfig(CREDS, prefer5Ghz = false))
        runCurrent()
        val removesBefore = api.removeCalls
        link.stop()
        api.loseGroup()
        runCurrent()
        assertEquals(LinkState.Idle, link.state.value)
        assertEquals(removesBefore + 1, api.removeCalls)
        assertEquals(1, api.requests.size)
    }
}
```

- [ ] **Step 3: Run it and confirm it fails to compile**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*P2pGroupLinkTest*"`
Expected: FAIL, `Unresolved reference 'P2pGroupLink'`.

- [ ] **Step 4: Implement `P2pGroupLink.kt`**

```kotlin
package com.wifiproxy.app.wifi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

const val REASON_CREATING = "Creating the Wi-Fi Direct group"
const val REASON_RETRYING = "Wi-Fi Direct group failed; retrying"
const val REASON_WIFI_OFF = "Wi-Fi is off"
const val REASON_LOST = "Wi-Fi Direct group lost; recreating"

private const val MAX_BACKOFF_MS = 60_000L

/**
 * Creates the autonomous Wi-Fi Direct group, verifies the firmware really used the requested SSID
 * and passphrase (spec §4 read-back verification), and recreates it with the same credentials
 * whenever it is lost (spec §6), backing off 1 s → 2 s → … → 60 s. Every Android call goes
 * through [api], so all of this runs on the JVM under virtual time.
 */
class P2pGroupLink(
    private val api: P2pApi,
    private val scope: CoroutineScope,
    private val deviceModel: String,
    private val maxConsecutiveFailures: Int = 10,
    private val infoRetryDelayMs: Long = 500L,
    private val infoRetries: Int = 6,
) : LinkManager {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val healthChecks = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    override fun start(config: LinkConfig) {
        job?.cancel()
        job = scope.launch { run(config) }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        api.removeGroup()
        _state.value = LinkState.Idle
    }

    override fun checkHealth() {
        healthChecks.trySend(Unit)
    }

    private sealed interface Attempt {
        data class Up(val state: LinkState.Up) : Attempt
        data class Fatal(val message: String) : Attempt
        data class Retry(val reason: Int) : Attempt
    }

    private suspend fun run(config: LinkConfig) {
        var failures = 0
        while (true) {
            if (!api.p2pEnabled.value) {
                _state.value = LinkState.Down(REASON_WIFI_OFF)
                api.p2pEnabled.first { it }
            }
            _state.value = LinkState.Down(if (failures == 0) REASON_CREATING else REASON_RETRYING)
            when (val attempt = createAndVerify(config)) {
                is Attempt.Up -> {
                    failures = 0
                    _state.value = attempt.state
                    if (awaitLoss()) _state.value = LinkState.Down(REASON_LOST)
                }
                is Attempt.Fatal -> {
                    _state.value = LinkState.Failed(attempt.message)
                    return
                }
                is Attempt.Retry -> {
                    failures++
                    if (failures >= maxConsecutiveFailures) {
                        _state.value = LinkState.Failed("Couldn't create the Wi-Fi Direct group (error ${attempt.reason}).")
                        return
                    }
                    _state.value = LinkState.Down(REASON_RETRYING)
                    delay(minOf(1000L shl (failures - 1), MAX_BACKOFF_MS))
                }
            }
        }
    }

    private suspend fun createAndVerify(config: LinkConfig): Attempt {
        val creds = config.credentials
        api.removeGroup()
        var result = api.createGroup(GroupRequest(creds.ssid, creds.password, if (config.prefer5Ghz) GroupBand.GHZ_5 else GroupBand.AUTO))
        if (config.prefer5Ghz && result is CreateResult.Failure && result.reason != CreateResult.BUSY &&
            result.reason != CreateResult.P2P_UNSUPPORTED && result.reason != CreateResult.MISSING_PERMISSION
        ) {
            result = api.createGroup(GroupRequest(creds.ssid, creds.password, GroupBand.AUTO))
        }
        if (result is CreateResult.Failure) {
            return when (result.reason) {
                CreateResult.P2P_UNSUPPORTED -> Attempt.Fatal("This phone doesn't support Wi-Fi Direct.")
                CreateResult.MISSING_PERMISSION ->
                    Attempt.Fatal("Allow the Nearby devices permission (Location on Android 10–12), then start sharing again.")
                else -> Attempt.Retry(result.reason)
            }
        }
        val info = awaitGroupInfo() ?: return Attempt.Retry(CreateResult.ERROR)
        if (info.networkName != creds.ssid || info.passphrase != creds.password) {
            api.removeGroup()
            return Attempt.Fatal(
                "This phone ($deviceModel) ignored the requested Wi-Fi name or password. Wi-Fi Direct sharing can't be used on it.",
            )
        }
        return Attempt.Up(LinkState.Up(info.ownerAddress!!, creds))
    }

    /** Group info can lag createGroup's success callback; poll briefly for a complete answer. */
    private suspend fun awaitGroupInfo(): GroupInfo? {
        repeat(infoRetries) { i ->
            api.groupInfo()?.takeIf { it.ownerAddress != null }?.let { return it }
            if (i < infoRetries - 1) delay(infoRetryDelayMs)
        }
        return null
    }

    /** Suspends while the group is up. True when the group was lost; false when Wi-Fi went off
     *  (the outer loop then waits for it to come back). */
    private suspend fun awaitLoss(): Boolean {
        var lost = false
        merge(
            api.connectionChanged.map { false },
            healthChecks.receiveAsFlow().map { false },
            api.p2pEnabled.filter { !it }.map { true },
        ).first { wifiOff ->
            when {
                wifiOff -> true
                api.groupInfo() == null -> { lost = true; true }
                else -> false
            }
        }
        return lost
    }
}
```

- [ ] **Step 5: Run the test and confirm it passes.** If the backoff test is off by one step, fix the implementation, not the test. The schedule is 1 s, 2 s, 4 s and so on, capped at 60 s, and the 10th consecutive failure is fatal.

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*P2pGroupLinkTest*"`
Expected: PASS (10 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/wifiproxy/app/wifi/GroupLink.kt app/src/main/kotlin/com/wifiproxy/app/wifi/P2pGroupLink.kt app/src/test/kotlin/com/wifiproxy/app/wifi/P2pGroupLinkTest.kt
git commit -m "feat(app): P2pGroupLink creates, verifies and recreates the Wi-Fi Direct group" -m "Co-Authored-By: Claude Opus 5.5 (1M context)"
```

---

### Task 4: `AndroidP2pApi` + manifest

**Files:**
- Create: `app/src/main/kotlin/com/wifiproxy/app/wifi/AndroidP2pApi.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: the `P2pApi`, `GroupRequest`, `GroupBand`, `GroupInfo` and `CreateResult` types from Task 3.
- Produces: `class AndroidP2pApi(context: Context) : P2pApi` with `fun close()`. It must be constructed and closed on the main thread.

This is Android glue and can't be tested on the JVM. Task 9 tests it on a device; this task must build with zero warnings and pass lint for `MissingPermission`.

- [ ] **Step 1: Add to `AndroidManifest.xml`**, next to the existing permissions:

```xml
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
    <uses-permission
        android:name="android.permission.NEARBY_WIFI_DEVICES"
        android:usesPermissionFlags="neverForLocation"
        tools:targetApi="33" />
    <uses-permission
        android:name="android.permission.ACCESS_FINE_LOCATION"
        android:maxSdkVersion="32" />

    <uses-feature android:name="android.hardware.wifi.direct" android:required="false" />
```

Add `xmlns:tools="http://schemas.android.com/tools"` to the `<manifest>` element.

- [ ] **Step 2: Implement `AndroidP2pApi.kt`**

```kotlin
package com.wifiproxy.app.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import kotlin.coroutines.resume

/**
 * [P2pApi] over [WifiP2pManager]. Callbacks arrive on the main looper. Every call checks the
 * runtime permission first (NEARBY_WIFI_DEVICES on API 33+, ACCESS_FINE_LOCATION on 29-32) and
 * reports [CreateResult.MISSING_PERMISSION] instead of throwing.
 */
class AndroidP2pApi(private val context: Context) : P2pApi {
    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private val channel: WifiP2pManager.Channel? = manager?.initialize(context, Looper.getMainLooper(), null)

    private val _p2pEnabled = MutableStateFlow(context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true)
    override val p2pEnabled: StateFlow<Boolean> = _p2pEnabled.asStateFlow()

    private val _connectionChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    override val connectionChanged: SharedFlow<Unit> = _connectionChanged.asSharedFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION ->
                    _p2pEnabled.value = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> _connectionChanged.tryEmit(Unit)
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) // sticky: delivers the current state now
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission") // checked by hasPermission() just above every call
    override suspend fun createGroup(request: GroupRequest): CreateResult {
        val m = manager ?: return CreateResult.Failure(CreateResult.P2P_UNSUPPORTED)
        val ch = channel ?: return CreateResult.Failure(CreateResult.P2P_UNSUPPORTED)
        if (!hasPermission()) return CreateResult.Failure(CreateResult.MISSING_PERMISSION)
        val config = WifiP2pConfig.Builder()
            .setNetworkName(request.ssid)
            .setPassphrase(request.passphrase)
            .setGroupOperatingBand(
                if (request.band == GroupBand.GHZ_5) WifiP2pConfig.GROUP_OWNER_BAND_5GHZ else WifiP2pConfig.GROUP_OWNER_BAND_AUTO,
            )
            .enablePersistentMode(false)
            .build()
        return try {
            suspendCancellableCoroutine { cont ->
                m.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = cont.resume(CreateResult.Success)
                    override fun onFailure(reason: Int) = cont.resume(CreateResult.Failure(reason))
                })
            }
        } catch (_: SecurityException) {
            CreateResult.Failure(CreateResult.MISSING_PERMISSION)
        }
    }

    override suspend fun removeGroup() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            suspendCancellableCoroutine { cont ->
                m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = cont.resume(Unit)
                    override fun onFailure(reason: Int) = cont.resume(Unit) // no group, or busy: nothing to undo
                })
            }
        } catch (_: SecurityException) {
            // Nothing we can remove without the permission; the group dies with Wi-Fi anyway.
        }
    }

    @SuppressLint("MissingPermission") // checked by hasPermission()
    override suspend fun groupInfo(): GroupInfo? {
        val m = manager ?: return null
        val ch = channel ?: return null
        if (!hasPermission()) return null
        return try {
            val group = suspendCancellableCoroutine { cont -> m.requestGroupInfo(ch) { cont.resume(it) } }
            if (group == null || !group.isGroupOwner) return null
            val owner = suspendCancellableCoroutine { cont ->
                m.requestConnectionInfo(ch) { info -> cont.resume(info?.groupOwnerAddress as? Inet4Address) }
            }
            GroupInfo(group.networkName, group.passphrase, owner)
        } catch (_: SecurityException) {
            null
        }
    }

    fun close() {
        runCatching { context.unregisterReceiver(receiver) }
        channel?.close()
    }
}
```

- [ ] **Step 3: Build and run all tests**

Run: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
Expected: BUILD SUCCESSFUL with zero Kotlin warnings, and no `MissingPermission` errors in `app/build/reports/lint-results-debug.html`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/wifiproxy/app/wifi/AndroidP2pApi.kt app/src/main/AndroidManifest.xml
git commit -m "feat(app): AndroidP2pApi over WifiP2pManager, Wi-Fi Direct permissions" -m "Co-Authored-By: Claude Opus 5.5 (1M context)"
```

---

### Task 5: Rename to `SessionController` (mechanical, no behaviour change)

**Files:**
- Rename: `app/src/main/kotlin/com/wifiproxy/app/service/UsbSessionController.kt` → `SessionController.kt`
- Rename: `app/src/test/kotlin/com/wifiproxy/app/service/UsbSessionControllerTest.kt` → `SessionControllerTest.kt`
- Modify: `ProxySessionService.kt`, `SessionHolder.kt`, `CellularEgress.kt` (kdoc only), `ServiceSessionRepository.kt`, `settings/SettingsScreen.kt`

**Interfaces:**
- Produces the following renames, and nothing else changes:

| Old name | New name |
|---|---|
| `UsbSessionController` | `SessionController` |
| `UsbProxyServer` | `SessionServer` |
| `RealUsbProxyServer` | `RealSessionServer` |
| `UsbProxyServerFactory` | `SessionServerFactory` |
| `FakeUsbProxyServer` (tests) | `FakeSessionServer` |

- [ ] **Step 1: Run the renames**

```bash
cd /c/source/android-wifi-proxy
git mv app/src/main/kotlin/com/wifiproxy/app/service/UsbSessionController.kt app/src/main/kotlin/com/wifiproxy/app/service/SessionController.kt
git mv app/src/test/kotlin/com/wifiproxy/app/service/UsbSessionControllerTest.kt app/src/test/kotlin/com/wifiproxy/app/service/SessionControllerTest.kt
grep -rl 'UsbSessionController\|UsbProxyServer\|FakeUsbProxyServer\|RealUsbProxyServer' app/src | xargs sed -i -e 's/RealUsbProxyServer/RealSessionServer/g' -e 's/FakeUsbProxyServer/FakeSessionServer/g' -e 's/UsbProxyServerFactory/SessionServerFactory/g' -e 's/UsbProxyServer/SessionServer/g' -e 's/UsbSessionControllerTest/SessionControllerTest/g' -e 's/UsbSessionController/SessionController/g'
grep -rn 'UsbSession\|UsbProxy' app/src || echo "no old names left"
```

Expected: `no old names left`.

- [ ] **Step 2: Build and run all tests.** The same 90 + 24 = 114 app tests should pass, plus 142 core tests, with zero warnings.

Run: `.\gradlew.bat :proxy-core:test :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/wifiproxy/app/service app/src/test/kotlin/com/wifiproxy/app/service app/src/main/kotlin/com/wifiproxy/app/egress/CellularEgress.kt app/src/main/kotlin/com/wifiproxy/app/session/ServiceSessionRepository.kt app/src/main/kotlin/com/wifiproxy/app/settings/SettingsScreen.kt
git diff --cached --name-only
git commit -m "refactor(app): rename UsbSessionController to SessionController" -m "Co-Authored-By: Claude Opus 5.5 (1M context)"
```

---

### Task 6: `SessionController` runs the Wi-Fi listener (TDD)

**Files:**
- Modify: `app/src/main/kotlin/com/wifiproxy/app/service/SessionController.kt`
- Test: `app/src/test/kotlin/com/wifiproxy/app/service/SessionControllerTest.kt`

**Interfaces:**
- Consumes: `LinkManager`, `LinkState`, `LinkConfig` (Task 3); `SlotPersistence`, `PersistedSlot` (Task 2); `ClientPlatform`.
- Produces, used by Task 7:

```kotlin
data class SessionConfig(
    val usbEnabled: Boolean,
    /** null = no Wi-Fi credentials set: the session is USB-only. */
    val wifi: LinkConfig?,
    val wifiPort: Int,
    val maxClients: Int,
)

class SessionController(
    private val egress: SessionEgress,
    private val link: LinkManager,
    private val slots: SlotPersistence,
    private val config: () -> SessionConfig,
    private val usbBindAddress: InetAddress = InetAddress.getByName("127.0.0.1"),
    private val usbPort: Int = DEFAULT_PORT,               // 8081
    private val serverFactory: SessionServerFactory = defaultServerFactory,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope,
    private val watchdogIntervalMs: Long = DEFAULT_WATCHDOG_INTERVAL_MS,
)
// new public members:
val linkState: StateFlow<LinkState>          // = link.state
val wifiPort: Int?                           // the Wi-Fi listener's port while the session runs, else null
fun addDevice(name: String, platform: ClientPlatform): Slot?   // null when not running, link not Up, or full
fun platformOf(slotId: String): ClientPlatform                  // LAPTOP for pinned, stored platform otherwise
suspend fun shutdown()                       // replaces shutdownBlocking(); also stops the link
```

`maxClients`, `bindAddress`, `port`, `peerSubnet` and `wifiDirectSubnet` are removed from the constructor. They now come from `config()` or are derived from the link.

**Behaviour to implement:**
1. **`doStart`:**
   - Read `config()` once into a `sessionConfig` field.
   - If `!usbEnabled && wifi == null`, throw `IllegalStateException("Set a Wi-Fi password in Settings or turn on USB sharing")`. The existing failure path turns this into `lastError` and IDLE.
   - Build the registry: `ClientRegistry(maxClients = cfg.maxClients.coerceIn(1, 3), initial = slots.load().map { it.record }.take(maxClients))`, and keep the platforms in `platforms: MutableMap<String, ClientPlatform>`.
   - If `usbEnabled`: add the pinned slot and start the USB server as today.
   - If `wifi != null`: call `link.start(wifi)` after the USB server.
   - The initial phase comes from `healthReason()` (step 2), set with the existing CAS from STARTING.
   - Collect `link.state` on `scope`, calling `onLinkState(it)` then `refreshHealth()`.
2. **Health.** `applyAvailability` is replaced by `refreshHealth()`, which uses the same RUNNING/DEGRADED CAS rules:

   ```kotlin
   private fun healthReason(): String? {
       if (!egress.available.value) return "No mobile data"
       if (sessionConfig?.wifi == null) return null
       return when (val s = link.state.value) {
           is LinkState.Up -> null
           is LinkState.Down -> s.reason
           is LinkState.Failed -> s.message
           LinkState.Idle -> REASON_CREATING
       }
   }
   ```

   `refreshHealth()` moves between RUNNING and DEGRADED by CAS. It updates `degradedReason` whenever the result is DEGRADED, even if the phase is unchanged, so the reason follows the link from "Wi-Fi is off" to "recreating". Log only when the phase changes.
3. **`onLinkState(state)`** runs on `scope`:
   - **`Up`, and the Wi-Fi server isn't serving this exact `(ownerAddress, credentials)`:**
     1. Stop any old Wi-Fi server and call `registry.clearBindings()`.
     2. Build a server with `ProxyConfig(bindAddress = owner, port = cfg.wifiPort, peerSubnet = subnetOf(owner), wifi = credentials)` and the current filter.
     3. Start it and record what it serves.
     - If the start throws, log `"Wi-Fi listener failed: <class>"` and leave `wifiServerFor` null so the watchdog retries.
   - **Any other state** while a Wi-Fi server is running: stop it, `registry.clearBindings()`, and set `wifiServerFor = null`. Keep the stopped server object in `lastWifiServer` for `revoke`.
   - `subnetOf(owner)` is `Cidr(InetAddress.getByAddress(owner.address.copyOf().also { it[3] = 0 }), 24)`.
4. **Filter.** `buildFilter()` uses `subnetOf(owner)` while Up, else `Cidr.parse("192.168.49.0/24")`. `onLinkChange`, the watchdog tick and `rebuildForNetworkChange` apply to **both** servers.
5. **Watchdog tick:**
   - also call `link.checkHealth()`;
   - if the link is Up but the Wi-Fi server is missing or `!isAccepting`, rebuild it by running the Up branch of `onLinkState` again with `wifiServerFor` forced to null.
6. **Devices:**
   - `addDevice` requires phase RUNNING/DEGRADED, a non-null registry and `link.state.value is LinkState.Up`. It calls `registry.addSlot(name)`, records the platform, then persists.
   - `revoke(id)`: a pinned slot goes to `usbServer?.revoke(id)`. Anything else goes to `wifiServer ?: lastWifiServer ?: usbServer`. If none exists, build a never-started server with `serverFactory.create(ProxyConfig(usbBindAddress, 0, Cidr.parse("127.0.0.0/8")), registry, egress, buildFilter()) {}`, call `.revoke(id)` on it, and persist on success. A never-started `ProxyServer` has no tunnels, so its `revoke` just removes the slot through the only public revoke API.
   - Persisting means `slots.save(registry.snapshot().map { PersistedSlot(it, platforms[it.id] ?: ClientPlatform.ANDROID) })`.
7. **Stop and rollback.** `doStop`, `rollbackStart` and `shutdown` call `link.stop()` after the servers stop and before `egress.stop()`. They also stop the Wi-Fi server and cancel the link-state collector. `shutdown()` is `suspend` and takes `shutdownBlocking()`'s place in `ProxySessionService.onDestroy` (Task 7).

- [ ] **Step 1: Update the test fixtures.**
  - Add a `FakeLink` and a `MemorySlots` to `SessionControllerTest.kt`.
  - Change the `controller(...)` helper so existing tests keep their meaning: USB on, no Wi-Fi.
  - The R2 test that builds `SessionController(...)` directly needs `link = FakeLink(), slots = MemorySlots(), config = { usbOnly }` added. The `egress start failure rolls back cleanly` test needs the same.

```kotlin
private class FakeLink : LinkManager {
    override val state = MutableStateFlow<LinkState>(LinkState.Idle)
    val starts = mutableListOf<LinkConfig>()
    var stops = 0
    var healthChecks = 0
    override fun start(config: LinkConfig) { starts += config; state.value = LinkState.Down(REASON_CREATING) }
    override suspend fun stop() { stops++; state.value = LinkState.Idle }
    override fun checkHealth() { healthChecks++ }
    fun up(owner: String = "192.168.49.1") { state.value = LinkState.Up(InetAddress.getByName(owner) as Inet4Address, WIFI.credentials) }
}

private class MemorySlots(var saved: List<PersistedSlot> = emptyList()) : SlotPersistence {
    var saves = 0
    override fun load() = saved
    override fun save(slots: List<PersistedSlot>) { saved = slots; saves++ }
}

private val WIFI = LinkConfig(WifiCredentials("DIRECT-k3-HostPhone", "correct horse battery"), prefer5Ghz = false)
private val USB_ONLY = SessionConfig(usbEnabled = true, wifi = null, wifiPort = 8080, maxClients = 3)
private val WIFI_ONLY = SessionConfig(usbEnabled = false, wifi = WIFI, wifiPort = 8080, maxClients = 3)
private val BOTH = SessionConfig(usbEnabled = true, wifi = WIFI, wifiPort = 8080, maxClients = 3)
```

The helper becomes:

```kotlin
private fun controller(
    egress: FakeSessionEgress = FakeSessionEgress(),
    server: FakeSessionServer = FakeSessionServer(),
    scope: CoroutineScope,
    watchdogIntervalMs: Long = 30_000L,
    now: () -> Long = { 0L },
    link: FakeLink = FakeLink(),
    slots: MemorySlots = MemorySlots(),
    config: SessionConfig = USB_ONLY,
    servers: MutableList<Pair<ProxyConfig, FakeSessionServer>>? = null,
) = SessionController(
    egress = egress,
    link = link,
    slots = slots,
    config = { config },
    serverFactory = SessionServerFactory { cfg, _, _, _, _ ->
        if (servers == null) server else FakeSessionServer().also { servers += cfg to it }
    },
    scope = scope,
    watchdogIntervalMs = watchdogIntervalMs,
    now = now,
)
```

- [ ] **Step 2: Run the existing suite and confirm it fails only to compile** (constructor mismatch)

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*SessionControllerTest*"`
Expected: FAIL, `No parameter with name 'link'`.

- [ ] **Step 3: Add the new failing tests** to `SessionControllerTest`

```kotlin
@Test
fun `wifi session is degraded until the group is up, then serves on the owner address`() = runTest {
    val link = FakeLink()
    val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
    val c = controller(scope = backgroundScope, link = link, config = WIFI_ONLY, servers = servers)
    c.start(); runCurrent()
    assertEquals(SessionPhase.DEGRADED, c.state.value)
    assertEquals(REASON_CREATING, c.degradedReason.value)
    assertEquals(listOf(WIFI), link.starts)
    assertTrue(servers.isEmpty())

    link.up(); runCurrent()
    assertEquals(SessionPhase.RUNNING, c.state.value)
    val (cfg, server) = servers.single()
    assertEquals(InetAddress.getByName("192.168.49.1"), cfg.bindAddress)
    assertEquals(8080, cfg.port)
    assertEquals(WIFI.credentials, cfg.wifi)
    assertTrue(cfg.peerSubnet.contains(InetAddress.getByName("192.168.49.77")))
    assertEquals(1, server.startCalls)
}

@Test
fun `group loss stops the wifi listener, clears bindings and degrades with the link reason`() = runTest {
    val link = FakeLink()
    val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
    val c = controller(scope = backgroundScope, link = link, config = WIFI_ONLY, servers = servers)
    c.start(); runCurrent(); link.up(); runCurrent()
    val slot = c.addDevice("Pixel 7", ClientPlatform.ANDROID)!!
    c.registry!!.onPacFetch(slot.token, InetAddress.getByName("192.168.49.5"))

    link.state.value = LinkState.Down(REASON_LOST); runCurrent()
    assertEquals(SessionPhase.DEGRADED, c.state.value)
    assertEquals(REASON_LOST, c.degradedReason.value)
    assertEquals(1, servers.single().second.stopCalls)
    assertNull(c.registry!!.slots().single { it.id == slot.id }.boundIp)

    link.up(); runCurrent()
    assertEquals(SessionPhase.RUNNING, c.state.value)
    assertEquals(2, servers.size)   // a fresh server for the recreated group
}

@Test
fun `a new owner address rebuilds the listener`() = runTest {
    val link = FakeLink()
    val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
    val c = controller(scope = backgroundScope, link = link, config = WIFI_ONLY, servers = servers)
    c.start(); runCurrent(); link.up("192.168.49.1"); runCurrent()
    link.up("192.168.50.1"); runCurrent()
    assertEquals(2, servers.size)
    assertEquals(1, servers[0].second.stopCalls)
    assertEquals(InetAddress.getByName("192.168.50.1"), servers[1].first.bindAddress)
}

@Test
fun `link failure degrades with its message and keeps usb serving`() = runTest {
    val link = FakeLink()
    val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
    val c = controller(scope = backgroundScope, link = link, config = BOTH, servers = servers)
    c.start(); runCurrent()
    link.state.value = LinkState.Failed("This phone doesn't support Wi-Fi Direct."); runCurrent()
    assertEquals(SessionPhase.DEGRADED, c.state.value)
    assertEquals("This phone doesn't support Wi-Fi Direct.", c.degradedReason.value)
    val usb = servers.single { it.first.bindAddress.isLoopbackAddress }.second
    assertEquals(0, usb.stopCalls)
}

@Test
fun `no mobile data outranks the link reason`() = runTest {
    val egress = FakeSessionEgress(available = false)
    val c = controller(egress = egress, scope = backgroundScope, config = WIFI_ONLY)
    c.start(); runCurrent()
    assertEquals("No mobile data", c.degradedReason.value)
}

@Test
fun `neither usb nor wifi refuses to start`() = runTest {
    val c = controller(scope = backgroundScope, config = SessionConfig(false, null, 8080, 3))
    c.start(); runCurrent()
    assertEquals(SessionPhase.IDLE, c.state.value)
    assertEquals("Set a Wi-Fi password in Settings or turn on USB sharing", c.lastError.value)
}

@Test
fun `usb disabled means no pinned slot and no loopback listener`() = runTest {
    val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
    val c = controller(scope = backgroundScope, config = WIFI_ONLY, servers = servers)
    c.start(); runCurrent()
    assertTrue(c.registry!!.slots().none { it.pinned })
    assertTrue(servers.none { it.first.bindAddress.isLoopbackAddress })
}

@Test
fun `devices persist with their platform and are restored on the next start`() = runTest {
    val link = FakeLink()
    val slots = MemorySlots()
    val c = controller(scope = backgroundScope, link = link, slots = slots, config = WIFI_ONLY)
    c.start(); runCurrent(); link.up(); runCurrent()
    val added = c.addDevice("Ben's iPhone", ClientPlatform.IPHONE)!!
    assertEquals(listOf(PersistedSlot(c.registry!!.snapshot().single(), ClientPlatform.IPHONE)), slots.saved)

    c.stop(); runCurrent(); c.start(); runCurrent()
    val restored = c.registry!!.slots().single()
    assertEquals(added.id, restored.id)
    assertEquals(added.token, restored.token)
    assertEquals(ClientPlatform.IPHONE, c.platformOf(added.id))
}

@Test
fun `addDevice needs the group up and room`() = runTest {
    val link = FakeLink()
    val c = controller(scope = backgroundScope, link = link, config = BOTH.copy(maxClients = 2))
    c.start(); runCurrent()
    assertNull(c.addDevice("Too early", ClientPlatform.ANDROID))
    link.up(); runCurrent()
    assertNotNull(c.addDevice("Pixel", ClientPlatform.ANDROID))      // USB pin + 1 = 2
    assertNull(c.addDevice("No room", ClientPlatform.ANDROID))
}

@Test
fun `revoke goes through a server and is persisted, even while the group is down`() = runTest {
    val link = FakeLink()
    val slots = MemorySlots()
    val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
    val c = controller(scope = backgroundScope, link = link, slots = slots, config = WIFI_ONLY, servers = servers)
    c.start(); runCurrent(); link.up(); runCurrent()
    val slot = c.addDevice("Pixel", ClientPlatform.ANDROID)!!
    link.state.value = LinkState.Down(REASON_LOST); runCurrent()
    val savesBefore = slots.saves

    assertTrue(c.revoke(slot.id))
    // The stopped Wi-Fi server is reused for the revoke. The fake can't remove the slot from the
    // registry (ClientRegistry.revoke is internal to :proxy-core), so assert the routing and that
    // persistence ran; the real ProxyServer.revoke removes it, and Task 9 checks that on a device.
    assertEquals(listOf(slot.id), servers.single().second.revokedIds)
    assertEquals(savesBefore + 1, slots.saves)
}

@Test
fun `stop stops the link and every server`() = runTest {
    val link = FakeLink()
    val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
    val c = controller(scope = backgroundScope, link = link, config = BOTH, servers = servers)
    c.start(); runCurrent(); link.up(); runCurrent()
    c.stop(); runCurrent()
    assertEquals(SessionPhase.IDLE, c.state.value)
    assertEquals(1, link.stops)
    assertTrue(servers.all { it.second.stopCalls == 1 })
}

@Test
fun `watchdog checks the link and rebuilds a dead wifi listener`() = runTest {
    val link = FakeLink()
    val servers = mutableListOf<Pair<ProxyConfig, FakeSessionServer>>()
    val c = controller(scope = backgroundScope, link = link, config = WIFI_ONLY, servers = servers)
    c.start(); runCurrent(); link.up(); runCurrent()
    servers.single().second.killAcceptLoop()
    advanceTimeBy(30_000); runCurrent()
    assertEquals(1, link.healthChecks)
    assertEquals(2, servers.size)
    assertEquals(SessionPhase.RUNNING, c.state.value)
}
```

`FakeSessionServer.revoke` must now return `true` and record the id, as it already does. These tests need the following imports: `ProxyConfig`, `WifiCredentials`, `Inet4Address`, `LinkConfig`, `LinkManager`, `LinkState`, `REASON_CREATING`, `REASON_LOST`, `PersistedSlot`, `SlotPersistence`, `ClientPlatform`, `assertNotNull`.

- [ ] **Step 4: Implement behaviours 1–7 in `SessionController.kt`.** Keep every existing guarantee: the serial scope, CAS phase writes, `checkNotInterruptedByStop`, rollback order (servers → link → egress), `onIdle` from `teardown()` only, and the `doStop` `!= STOPPING` guard.
  - Server lifecycle: `usbServer`, `wifiServer`, `lastWifiServer` and `wifiServerFor: LinkState.Up?` are all `@Volatile` fields.
  - `rollbackStart` and `doStop` both call a shared `stopAll()` that stops the servers and then `link.stop()`.
  - `teardown()` also cancels the link collector and clears `wifiServerFor`, `wifiServer`, `lastWifiServer`, `usbServer`, `sessionConfig` and `platforms`.

- [ ] **Step 5: Run the controller tests and confirm all pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*SessionControllerTest*"`
Expected: PASS (28 existing + 12 new).

- [ ] **Step 6: Run the full suite with zero warnings.** `ProxySessionService` still compiles against `shutdownBlocking`, so wrap that call for now: `CoroutineScope(serial + Job()).launch { c.shutdown() }`.

Run: `.\gradlew.bat :proxy-core:test :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/wifiproxy/app/service/SessionController.kt app/src/test/kotlin/com/wifiproxy/app/service/SessionControllerTest.kt app/src/main/kotlin/com/wifiproxy/app/service/ProxySessionService.kt
git commit -m "feat(app): SessionController serves Wi-Fi Direct clients alongside USB" -m "Co-Authored-By: Claude Opus 5.5 (1M context)"
```

---

### Task 7: Config, service and repository

**Files:**
- Create: `app/src/main/kotlin/com/wifiproxy/app/settings/HostConfigSource.kt`
- Modify: `app/src/main/kotlin/com/wifiproxy/app/settings/AppPreferences.kt`
- Modify: `app/src/main/kotlin/com/wifiproxy/app/service/ProxySessionService.kt`
- Modify: `app/src/main/kotlin/com/wifiproxy/app/session/SessionRepository.kt`
- Modify: `app/src/main/kotlin/com/wifiproxy/app/session/ServiceSessionRepository.kt`
- Modify: `app/src/main/kotlin/com/wifiproxy/app/session/FakeSessionRepository.kt`
- Test: `app/src/test/kotlin/com/wifiproxy/app/settings/HostConfigSourceTest.kt`

**Interfaces:**
- Consumes: `CredentialStore`, `StoredCredentials`, `SlotStore`, `KeyValueStore`, `SharedPrefsStore` (Tasks 1–2); `SessionConfig`, `SessionController.addDevice/platformOf/linkState/wifiPort/shutdown` (Task 6); `AndroidP2pApi` (Task 4); `P2pGroupLink` (Task 3).
- Produces:
  - `AppPreferences` gains `maxClients: Int` (default 1), `wifiPort: Int` (default 8080), `prefer5Ghz: Boolean` (default true) and `sessionDesired: Boolean` (default false);
  - `class HostConfigSource(prefs: HostPrefs, credentials: CredentialStore)`, where `interface HostPrefs { var usbSharingEnabled: Boolean; var maxClients: Int; var wifiPort: Int; var prefer5Ghz: Boolean }` is implemented by `AppPreferences`. It has `fun hostSettings(): HostSettings`, `fun sessionConfig(): SessionConfig` and `fun save(settings: HostSettings): SaveResult`, where `enum class SaveResult { SAVED, SAVED_CREDENTIALS_CHANGED, INVALID }`;
  - `SessionUi.wifiUp: Boolean = false`.
- `HostConfigSource.forContext(context)` builds the real instance. It is a companion function that wires `AppPreferences(context)` with `CredentialStore(SharedPrefsStore(context.getSharedPreferences("wifi_proxy_secure", MODE_PRIVATE)), AesGcmSecretBox { KeystoreKeys.getOrCreate("wifi-credentials") })`.

**`HostConfigSource` rules (test-first):**
- `hostSettings()`: `ssidSuffix` and `password` come from the credentials, or `""` when unset. The other fields come from the prefs.
- `sessionConfig()`:
  - `usbEnabled` comes from the prefs;
  - `wifi` is `LinkConfig(WifiCredentials(c.ssid, c.password), prefer5Ghz)` when credentials exist, otherwise null;
  - `wifiPort` and `maxClients` come from the prefs.
- `save(s)`:
  - Return INVALID, changing nothing, when the port is outside `1024..65535`, the port is `8081` (the USB port), or `maxClients` is outside `1..3`.
  - If both the suffix and the password are blank, clear the credentials. Otherwise apply the `WifiCredentialRules` checks and return INVALID on any error.
  - Otherwise write the prefs and the credentials. Return SAVED_CREDENTIALS_CHANGED iff the suffix or the password differs from before.

- [ ] **Step 1: Write the failing `HostConfigSourceTest`**

```kotlin
package com.wifiproxy.app.settings

import com.wifiproxy.app.session.HostSettings
import com.wifiproxy.app.wifi.AesGcmSecretBox
import com.wifiproxy.app.wifi.CredentialStore
import java.util.Random
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryPrefs : HostPrefs {
    override var usbSharingEnabled = true
    override var maxClients = 1
    override var wifiPort = 8080
    override var prefer5Ghz = true
}

private class MapKv : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

class HostConfigSourceTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val prefs = MemoryPrefs()
    private val source = HostConfigSource(prefs, CredentialStore(MapKv(), AesGcmSecretBox { key }, Random(7)))
    private val valid = HostSettings(ssidSuffix = "HostPhone", password = "correct horse battery", maxClients = 3, port = 8080, prefer5Ghz = false)

    @Test
    fun `defaults are usb only`() {
        val cfg = source.sessionConfig()
        assertTrue(cfg.usbEnabled)
        assertNull(cfg.wifi)
        assertEquals(1, cfg.maxClients)
        assertEquals("", source.hostSettings().password)
    }

    @Test
    fun `saving credentials enables wifi and reports the change once`() {
        assertEquals(SaveResult.SAVED_CREDENTIALS_CHANGED, source.save(valid))
        val wifi = source.sessionConfig().wifi!!
        assertTrue(Regex("DIRECT-[a-z0-9]{2}-HostPhone").matches(wifi.credentials.ssid))
        assertEquals("correct horse battery", wifi.credentials.password)
        assertEquals(false, wifi.prefer5Ghz)
        assertEquals(3, source.sessionConfig().maxClients)
        assertEquals(SaveResult.SAVED, source.save(valid))
        assertEquals(SaveResult.SAVED, source.save(valid.copy(maxClients = 2)))
    }

    @Test
    fun `invalid settings change nothing`() {
        source.save(valid)
        for (bad in listOf(valid.copy(password = "short"), valid.copy(port = 8081), valid.copy(port = 80), valid.copy(maxClients = 4), valid.copy(ssidSuffix = "has space"))) {
            assertEquals(SaveResult.INVALID, source.save(bad))
        }
        assertEquals(valid, source.hostSettings())
    }

    @Test
    fun `blank name and password clears the credentials`() {
        source.save(valid)
        assertEquals(SaveResult.SAVED_CREDENTIALS_CHANGED, source.save(valid.copy(ssidSuffix = "", password = "")))
        assertNull(source.sessionConfig().wifi)
    }
}
```

- [ ] **Step 2: Run it** — FAIL (unresolved `HostConfigSource`).

- [ ] **Step 3: Implement `AppPreferences` additions and `HostConfigSource`.** Then run the test and confirm it passes.

- [ ] **Step 4: `ProxySessionService` changes**
  - **Build the controller** with:

    ```kotlin
    SessionController(
        egress,
        link = P2pGroupLink(AndroidP2pApi(applicationContext), serviceScope, Build.MODEL),
        slots = SlotStore(SharedPrefsStore(...)),
        config = hostConfig::sessionConfig,
        scope = serviceScope,
    )
    ```

    `AndroidP2pApi` is created on Main inside `buildController`, which runs from `onStartCommand`. Keep the reference and `close()` it in `onDestroy` after scheduling the shutdown.
  - **Process death (spec §3).**
    - Return `START_STICKY`.
    - A Start intent sets `prefs.sessionDesired = true`. A Stop intent sets it false. So does the `onIdle` hand-off's `stopSelf` branch, since any path to IDLE while alive means the user no longer wants sharing.
    - A `null` intent (sticky restart): if `sessionDesired`, treat it as Start; otherwise `stopSelf(startId)`.
  - **Foreground entry may be refused** after a background restart on API 31+.
    - Wrap both `startForeground` calls in `enterForeground(notification): Boolean`, which catches `IllegalStateException`. That covers `ForegroundServiceStartNotAllowedException` and keeps API 29 working.
    - On failure, post a plain notification on the same channel, "Sharing stopped. Tap to resume.", whose content intent opens `MainActivity`. Then `stopSelf()`.
    - The battery-optimisation exemption (an existing setup check) makes this rare.
  - **Shutdown:** replace `c.shutdownBlocking()` with `c.shutdown()` in the `onDestroy` launch.
  - **WifiLock:** next to the wake lock in `startWakeLockManagement`, acquire `WifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "$packageName:sharing")` (reference-counted false) while RUNNING/DEGRADED, and release it with the wake lock.
  - **Notification:** the title becomes "Sharing mobile data". While RUNNING, the text adds the SSID when the link is Up: `DIRECT-k3-HostPhone · ↑ … ↓ …`.

- [ ] **Step 5: `ServiceSessionRepository` changes**
  - `settings` is backed by `HostConfigSource.hostSettings()`, loaded at construction.
  - **`updateSettings(s)`:**
    1. If `s.maxClients` is below the active slot count, return false. The count is the running registry's `slots().size`, or `SlotStore.load().size` + (USB ? 1 : 0) when idle.
    2. Call `hostConfig.save(s)`. INVALID returns false.
    3. SAVED_CREDENTIALS_CHANGED: clear the `SlotStore`. If the session is running, `stop()` then `start()`. The service's M-b pending-start path turns that into a restart.
    4. Refresh `settings` and the checks, then return true.
  - **`addDevice(name, platform)`:** `controller.addDevice(name, platform)?.let { inviteFor(it.id) }`.
  - **`inviteFor(slotId)`:**
    - Return null unless the controller's `linkState` is `Up` and the slot exists and isn't pinned.
    - Otherwise build `DeviceInvite` with `QrPayloads.wifiQr(up.credentials.ssid, up.credentials.password)`, plus `pacUrl` and `mobileConfigUrl` from `up.ownerAddress.hostAddress`, `controller.wifiPort` and the slot token.
  - **`session` mapping:**
    - Each slot's `platform` is `controller.platformOf(id)`.
    - `wifiUp` is `linkState is Up`.
    - `ssid` is the credentials' SSID, or `"USB only"` when there are none.
    - `proxyHost` is the owner address while Up, otherwise `"192.168.49.1"`.
    - `port` is `wifiPort`.
    - Add `controller.linkState` to the `combine`. With six flows, use the `combine(Iterable)` overload or nest two `combine`s.
  - **`checks`** gains three entries:
    - "Wi-Fi password set": ACTION_NEEDED with detail "Needed for Wi-Fi Direct sharing", and no fix intent because the user goes to the Settings tab;
    - "Nearby devices permission" (API 33+) or "Location permission" (29–32): ACTION_NEEDED when not granted, with the fix intent `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for the package;
    - "Wi-Fi on": ACTION_NEEDED when `WifiManager.isWifiEnabled` is false, with the fix intent `Settings.Panel.ACTION_WIFI`.
- [ ] **Step 6: `FakeSessionRepository`:** set `wifiUp = true` while RUNNING/DEGRADED and false otherwise. Update `FakeSessionRepositoryTest` if it compares whole `SessionUi` values.

- [ ] **Step 7: Build and run everything with zero warnings**

Run: `.\gradlew.bat :proxy-core:test :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit** the files listed above by explicit path, with the message `feat(app): persisted host settings, real device invites, sticky restart`.

---

### Task 8: UI — devices, settings, permissions

**Files:**
- Modify: `app/src/main/kotlin/com/wifiproxy/app/devices/AddDeviceScreen.kt`. The signature becomes `AddDeviceScreen(repository, modifier, isDemoMode, usbSharingEnabled: Boolean = true)`.
- Modify: `app/src/main/kotlin/com/wifiproxy/app/AppRoot.kt`, which passes `prefs.usbSharingEnabled` to `AddDeviceScreen`.
- Modify: `app/src/main/kotlin/com/wifiproxy/app/settings/SettingsScreen.kt`
- Modify: `app/src/main/kotlin/com/wifiproxy/app/MainActivity.kt`

**Interfaces:**
- Consumes: `SessionUi.wifiUp`, `SlotUi.platform` (`LAPTOP` = pinned), `SessionRepository.addDevice/inviteFor/revoke`, `WifiCredentialRules`, `AppPreferences.usbSharingEnabled`.

- [ ] **Step 1: Devices (real mode).** Replace `UsbDevicesContent` with `RealDevicesContent(repository, modifier)`. It reuses `DeviceRow`, `AddDeviceDialog`, `InviteSheetContent` and `ActiveInvite` from the demo content.
  - **Title:** "Devices", with a trailing count `slots.size/maxClients`.
  - **Laptop:** `LaptopUsbCard(slot = session.slots.firstOrNull { it.platform == ClientPlatform.LAPTOP })`, shown only when that slot exists or `usbSharingEnabled`.
  - **Wi-Fi devices:** a `DeviceRow` for each non-LAPTOP slot. A tap opens the invite sheet via `inviteFor`. A null invite shows the snackbar text "Start sharing and wait for the Wi-Fi Direct group to show this device's codes".
  - **"Add device" button:** enabled only when `session.wifiUp && session.slots.size < session.maxClients`. Below it, show the first line that applies:
    1. while IDLE: "Start sharing to add Wi-Fi devices";
    2. while the link isn't up: `session.degradedReason ?: "Waiting for the Wi-Fi Direct group…"`;
    3. when full: the existing full message.
  - **Laptop kit:** `LaptopKitCard()` stays at the bottom.
  - Remove the "Wi-Fi devices arrive in the next version" text.
- [ ] **Step 2: Settings.**
  - **USB switch:** the "USB sharing (laptop)" switch becomes real. It is bound to `prefs.usbSharingEnabled` and disabled while a real session is running, using the existing `demoSwitchLocked` logic. While locked, its help text reads "Applies the next time sharing starts. Stop sharing to change it."
  - **Credentials:** remove the `LaunchedEffect` that forced it on. Show `WifiCredentialRules.suffixError(suffix)` under the network-name field and `passwordError(password)` under the password field, only when the field is non-empty. Disable Save while either shows an error. Both fields empty is allowed, meaning USB only.
  - **SSID preview:** below the name field, show the full SSID, `DIRECT-xy-<suffix>`. Get it from `repository.session.value.ssid` when the suffix matches the saved one; otherwise show `DIRECT-··-<suffix>`.
  - **Rejections:** when `updateSettings` returns false for a non-maxClients reason, show "Check the port (1024–65535, not 8081) and the Wi-Fi fields." Keep the existing revoke-first message for the maxClients case: check whether `maxClients` is below the active slot count before calling.
- [ ] **Step 3: Permissions at Start.** `MainActivity`'s `onBeforeStart` switches to `ActivityResultContracts.RequestMultiplePermissions()` and requests the missing subset of:
  - `POST_NOTIFICATIONS` (API 33+);
  - `NEARBY_WIFI_DEVICES` (API 33+), or `ACCESS_FINE_LOCATION` (API 29–32), only when a Wi-Fi password is set: `repository.settings.value.password.isNotEmpty()`.

  Start proceeds either way. A denied permission surfaces as the link's "Allow the Nearby devices permission…" degraded reason.
- [ ] **Step 4: Build, run all tests, and install on the emulator for a smoke check.** On the emulator, Wi-Fi Direct is unavailable and must show as a degraded reason, not a crash. Steps:
  1. In Settings, set a password with USB off and press Start.
  2. Home shows Degraded with "This phone doesn't support Wi-Fi Direct." (or a permission message). No crash.
  3. Turn USB on and restart sharing. `curl -x http://127.0.0.1:8081 -sS -o NUL -w "%{http_code}" https://example.com` through `adb forward` returns 200.

  `adb logcat -d | grep -c FATAL` must print `0`.
- [ ] **Step 5: Commit** the three files with the message `feat(app): real Wi-Fi device list, settings validation, P2P permission request`.

---

### Task 9: On-device verification and docs

Wi-Fi Direct needs real hardware: the **host** phone with a SIM and mobile data, plus at least one client phone. Ideally also an iPhone and a Windows laptop. Use `adb install -r app/build/outputs/apk/debug/app-debug.apk` on the host. Never change this PC's proxy settings, except deliberately for the laptop-client test, and restore them afterwards.

- [ ] **Step 1: Basic flow.**
  1. On the host: Settings → name `HostPhone`, a strong password, Save. Grant Nearby devices. Press Start.
  2. Home must reach Running, with SSID `DIRECT-xy-HostPhone`.
  3. Devices → Add device "Pixel" (Android). On the client, scan the Wi-Fi QR, then set Proxy → Proxy Auto-Config to the PAC URL shown, with client mobile data off.
  4. Browse three sites. The device row shows its IP (`192.168.49.x`), and the counters move on Home.
- [ ] **Step 2: iPhone, if available.** Scan the Wi-Fi QR. Open the `.mobileconfig` URL **in Safari**, install the profile in Settings, and browse.
- [ ] **Step 3: Laptop over Wi-Fi.** Join `DIRECT-xy-HostPhone`. On Windows, use Settings → Proxy → "Use setup script" with the PAC URL. Browse, then remove the setup script.
- [ ] **Step 4: Resilience.** Record each result:
  - turn the host's Wi-Fi off and on: Degraded "Wi-Fi is off", then Running, and the client rejoins on its own;
  - host airplane mode on and off;
  - revoke the client: its next request gets 403;
  - change the password: everyone is dropped, and the old QR no longer joins;
  - `adb shell am crash com.wifiproxy.app`: the service restarts by itself within about a minute, the client rejoins with the same PAC URL, and the slot keeps its token;
  - 3 clients at once.
- [ ] **Step 5: README device tests.** These are the tests still open from Plan 1:
  - log `Selector.open().javaClass` on the oldest available device;
  - check that browser preconnect bursts on a client don't cause resets (the per-peer cap of 16).
- [ ] **Step 6: Docs.**
  - **README:** the module table shows `:app` as Plan 2a + 2b done. Mark the Plan 2 handoff duties that are now done: `clearBindings`, `snapshot()` persistence, watchdog, Wi-Fi.
  - **Spec:** add a "Plan 2b decisions" note listing the six decisions at the top of this plan. Correct `targetSdk`/`compileSdk` to 36, with the reason (Compose BOM).
  - **HANDOFF.md:** update the State and Open items sections.
- [ ] **Step 7: Commit** the docs by explicit path, with the message `docs: Plan 2b results, spec decisions, handoff update`.

## Out of scope (2b)
OEM battery guides (Samsung, Xiaomi, OnePlus), thermal throttling, Data Saver and system-hotspot detection, `ACCESS_LOCAL_NETWORK` (target 37), proxy authentication, SOCKS5 and the v2 client app.
