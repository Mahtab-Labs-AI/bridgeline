# Proxy Core Implementation Plan (Plan 1 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `:proxy-core`, a pure Kotlin/JVM library that implements the whole proxy data plane from the spec: client slots and admission, request parsing, PAC/mobileconfig/probe responses, destination filtering, happy-eyeballs dialing, and the NIO tunnel server. It must be fully testable on a desktop JVM without the Android SDK.

**Architecture:** One selector thread (`NioLoop`) lets coroutines suspend until non-blocking channels are ready. `ProxyServer` accepts on the group-owner IP only, reads one request head, then either serves local content (PAC/mobileconfig), answers connectivity probes, or opens an upstream connection through the `Egress` abstraction and pumps bytes both ways (`Tunnel`). Android-specific egress (a cellular `Network` binding) arrives in Plan 2 as another `Egress` implementation. This plan ships `DirectEgress` for tests.

**Tech Stack:** Kotlin 2.2.20 (JVM target 17), kotlinx-coroutines 1.10.2, JUnit 5.12.2 + kotlin-test, OkHttp/MockWebServer 4.12.0 (tests only), Gradle 8.14.3 wrapper, Temurin JDK 21.

**Spec:** `docs/superpowers/specs/2026-11-19-android-wifi-proxy-design.md`

**Roadmap:**
- **Plan 1 (this):** `:proxy-core`.
- **Plan 2:** Android host shell: `:app` module, `CredentialStore`, `P2pGroupLink`, cellular `EgressNetworkProvider` (implements `Egress`), `ProxySessionService`, `SessionController`, `PowerKeeper`, `SystemMonitor`, `HealthWatchdog`, slot persistence via `ClientRegistry.snapshot()`.
- **Plan 3:** Compose UI and onboarding: Home, Add device (QR), Settings, Setup check/`OemGuide`, Diagnostics.

**Conventions:**
- **Working directory:** run all commands in PowerShell from `E:\Source\android-wifi-proxy`.
- **JDK setup:** every new PowerShell session must first run `. .\scripts\env.ps1` to put JDK 21 on `JAVA_HOME`/`Path`. The machine default is JDK 8, which cannot run Gradle 8.14.
- **Commit trailer:** end every commit message with the line `Co-Authored-By: Claude Opus 5.5 (1M context)`. The `-m` examples below omit it for brevity. Use a second `-m` to add it.
- **Android compatibility:** core code must stay usable on Android API 29. Use only `java.nio`, `java.net`, `java.util`, `java.security` and kotlinx-coroutines. Do not use `java.net.http`, `java.nio.file`, virtual threads, or other JDK 11+ library APIs.

---

## File structure

```
android-wifi-proxy/
├── .gitattributes, .gitignore, gradle.properties
├── settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
├── gradlew, gradlew.bat, gradle/wrapper/*        (generated)
├── scripts/env.ps1                                JDK 21 session setup
├── README.md
└── proxy-core/
    ├── build.gradle.kts
    └── src/
        ├── main/kotlin/com/wifiproxy/core/
        │   ├── ProxyConfig.kt           ProxyConfig, WifiCredentials
        │   ├── ProxyServer.kt           accept loop, routing, admission, tunnels, revoke
        │   ├── net/Cidr.kt              CIDR parse + contains
        │   ├── net/DestinationFilter.kt blocked-range policy
        │   ├── clients/ClientRegistry.kt Slot, SlotRecord, TokenSource, ClientRegistry
        │   ├── http/HttpResponse.kt     canned responses
        │   ├── http/RequestParser.kt    ProxyRequest + parser + upstream head rewrite
        │   ├── content/PacFile.kt
        │   ├── content/MobileConfig.kt
        │   ├── content/Probes.kt        connectivity-probe answers
        │   ├── content/LocalContent.kt  serves /<token>.pac and /<token>.mobileconfig
        │   ├── io/NioLoop.kt            selector thread + suspend await
        │   ├── io/ChannelIo.kt          readHead, writeFully, pump
        │   ├── io/Tunnel.kt             bidirectional pump + idle timeout
        │   ├── egress/Egress.kt         Egress interface + DirectEgress
        │   └── egress/Dialer.kt         happy-eyeballs dialer + connectChannel
        └── test/kotlin/com/wifiproxy/core/
            ├── TestSockets.kt           socket-pair helpers shared by tests
            ├── ProxyServerTest.kt
            ├── net/CidrTest.kt, net/DestinationFilterTest.kt
            ├── clients/ClientRegistryTest.kt
            ├── http/RequestParserTest.kt
            ├── content/ContentTest.kt, content/LocalContentTest.kt
            ├── io/NioLoopTest.kt, io/ChannelIoTest.kt, io/TunnelTest.kt
            └── egress/DialerTest.kt
```

---

### Task 1: Toolchain and Gradle scaffold

**Files:**
- Create: `scripts/env.ps1`, `.gitattributes`, `.gitignore`, `gradle.properties`, `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `proxy-core/build.gradle.kts`
- Generated: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`

- [ ] **Step 1: Install JDK 21** (one-time; skip if `C:\Program Files\Eclipse Adoptium\jdk-21*` exists)

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK -e --accept-source-agreements --accept-package-agreements
```

- [ ] **Step 2: Create `scripts/env.ps1`**

```powershell
# Dot-source in every PowerShell session: . .\scripts\env.ps1
$jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdk) { throw 'JDK 21 not found. Install with: winget install --id EclipseAdoptium.Temurin.21.JDK -e' }
$env:JAVA_HOME = $jdk.FullName
$env:Path = "$($jdk.FullName)\bin;$env:Path"
Write-Host "JAVA_HOME=$env:JAVA_HOME"
```

Run `. .\scripts\env.ps1; java -version`.
Expected: `openjdk version "21...`

- [ ] **Step 3: Create `.gitattributes`**

```
* text=auto eol=lf
*.bat text eol=crlf
*.ps1 text eol=crlf
*.jar binary
```

- [ ] **Step 4: Create `.gitignore`**

```
.gradle/
.kotlin/
build/
local.properties
.idea/
*.iml
captures/
```

- [ ] **Step 5: Create `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
kotlin.code.style=official
```

- [ ] **Step 6: Create `gradle/libs.versions.toml`**

```toml
[versions]
kotlin = "2.2.20"
coroutines = "1.10.2"
junit = "5.12.2"
okhttp = "4.12.0"

[libraries]
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-launcher = { module = "org.junit.platform:junit-platform-launcher" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

- [ ] **Step 7: Create `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-wifi-proxy"
include(":proxy-core")
```

- [ ] **Step 8: Create root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
```

- [ ] **Step 9: Create `proxy-core/build.gradle.kts`**

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 10: Generate the Gradle wrapper with a throwaway Gradle download**

```powershell
. .\scripts\env.ps1
$zip = Join-Path $env:TEMP 'gradle-8.14.3-bin.zip'
Invoke-WebRequest https://services.gradle.org/distributions/gradle-8.14.3-bin.zip -OutFile $zip
Expand-Archive $zip (Join-Path $env:TEMP 'gradle-boot') -Force
& (Join-Path $env:TEMP 'gradle-boot\gradle-8.14.3\bin\gradle.bat') wrapper --gradle-version 8.14.3 --distribution-type bin
```

Expected: `BUILD SUCCESSFUL`, and `gradlew.bat` plus `gradle/wrapper/` now exist.

- [ ] **Step 11: Verify the build**

Run: `.\gradlew.bat projects`
Expected: output lists `Project ':proxy-core'` and ends with `BUILD SUCCESSFUL`.

- [ ] **Step 12: Commit**

```powershell
git add .gitattributes .gitignore gradle.properties settings.gradle.kts build.gradle.kts gradle proxy-core/build.gradle.kts gradlew gradlew.bat scripts/env.ps1
git commit -m "build: Gradle scaffold with :proxy-core module"
```

---

### Task 2: CIDR matching and destination filter

**Files:**
- Create: `proxy-core/src/main/kotlin/com/wifiproxy/core/net/Cidr.kt`
- Create: `proxy-core/src/main/kotlin/com/wifiproxy/core/net/DestinationFilter.kt`
- Test: `proxy-core/src/test/kotlin/com/wifiproxy/core/net/CidrTest.kt`
- Test: `proxy-core/src/test/kotlin/com/wifiproxy/core/net/DestinationFilterTest.kt`

- [ ] **Step 1: Write the failing tests**

`CidrTest.kt`:

```kotlin
package com.wifiproxy.core.net

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CidrTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun `ipv4 prefix match`() {
        val c = Cidr.parse("192.168.49.0/24")
        assertTrue(c.contains(ip("192.168.49.1")))
        assertTrue(c.contains(ip("192.168.49.255")))
        assertFalse(c.contains(ip("192.168.50.1")))
    }

    @Test
    fun `non byte aligned prefix`() {
        val c = Cidr.parse("100.64.0.0/10")
        assertTrue(c.contains(ip("100.127.255.255")))
        assertFalse(c.contains(ip("100.128.0.0")))
    }

    @Test
    fun `ipv6 prefix match`() {
        val c = Cidr.parse("fe80::/10")
        assertTrue(c.contains(ip("fe80::1")))
        assertTrue(c.contains(ip("febf::1")))
        assertFalse(c.contains(ip("fec0::1")))
    }

    @Test
    fun `families never match each other`() {
        assertFalse(Cidr.parse("0.0.0.0/0").contains(ip("::1")))
        assertFalse(Cidr.parse("::/0").contains(ip("127.0.0.1")))
    }

    @Test
    fun `rejects prefix longer than address`() {
        assertFailsWith<IllegalArgumentException> { Cidr.parse("10.0.0.0/33") }
        assertFailsWith<IllegalArgumentException> { Cidr.parse("10.0.0.0") }
    }
}
```

`DestinationFilterTest.kt`:

```kotlin
package com.wifiproxy.core.net

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DestinationFilterTest {
    private val filter = DestinationFilter.default(Cidr.parse("192.168.49.0/24"))
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun `public addresses are allowed`() {
        assertTrue(filter.isAllowed(ip("8.8.8.8")))
        assertTrue(filter.isAllowed(ip("17.57.146.52")))
        assertTrue(filter.isAllowed(ip("2001:4860:4860::8888")))
    }

    @Test
    fun `private, loopback, link-local, cgnat and multicast are blocked`() {
        listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.1.1",
            "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255",
            "::1", "::", "fd00::1", "fe80::1", "ff02::1",
        ).forEach { assertFalse(filter.isAllowed(ip(it)), it) }
    }

    @Test
    fun `p2p subnet is blocked even outside the default list`() {
        val f = DestinationFilter.default(Cidr.parse("203.0.113.0/24"))
        assertFalse(f.isAllowed(ip("203.0.113.7")))
    }

    @Test
    fun `ipv4-mapped ipv6 literal is treated as ipv4`() {
        assertFalse(filter.isAllowed(ip("::ffff:127.0.0.1")))
    }

    @Test
    fun `empty filter allows everything`() {
        assertTrue(DestinationFilter(emptyList()).isAllowed(ip("127.0.0.1")))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.net.*"`
Expected: FAIL, compilation error `Unresolved reference 'Cidr'`.

- [ ] **Step 3: Implement `Cidr.kt`**

```kotlin
package com.wifiproxy.core.net

import java.net.InetAddress

/** An IPv4 or IPv6 network in CIDR form. [parse] expects an address literal, never a hostname. */
class Cidr(network: InetAddress, val prefix: Int) {
    private val netBytes: ByteArray = network.address

    init {
        require(prefix in 0..netBytes.size * 8) { "prefix $prefix out of range for $network" }
    }

    fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != netBytes.size) return false
        var remaining = prefix
        var i = 0
        while (remaining > 0) {
            val mask = if (remaining >= 8) 0xFF else (0xFF shl (8 - remaining)) and 0xFF
            if ((bytes[i].toInt() and mask) != (netBytes[i].toInt() and mask)) return false
            remaining -= 8
            i++
        }
        return true
    }

    override fun toString() = "${InetAddress.getByAddress(netBytes).hostAddress}/$prefix"

    companion object {
        fun parse(text: String): Cidr {
            val slash = text.indexOf('/')
            require(slash > 0) { "missing prefix in '$text'" }
            return Cidr(InetAddress.getByName(text.substring(0, slash)), text.substring(slash + 1).toInt())
        }
    }
}
```

- [ ] **Step 4: Implement `DestinationFilter.kt`**

```kotlin
package com.wifiproxy.core.net

import java.net.InetAddress

/** Decides which resolved upstream addresses clients may reach (stops SSRF into the host or LAN). */
class DestinationFilter(private val blocked: List<Cidr>) {
    fun isAllowed(address: InetAddress): Boolean = blocked.none { it.contains(address) }

    companion object {
        private val DEFAULT_BLOCKED = listOf(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
            "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "255.255.255.255/32",
            "::/128", "::1/128", "fc00::/7", "fe80::/10", "ff00::/8",
        ).map(Cidr::parse)

        fun default(p2pSubnet: Cidr) = DestinationFilter(DEFAULT_BLOCKED + p2pSubnet)
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.net.*"`
Expected: `BUILD SUCCESSFUL`, 10 tests passed.

- [ ] **Step 6: Commit**

```powershell
git add proxy-core/src
git commit -m "feat(core): CIDR matching and destination filter"
```

---

### Task 3: Client registry (slots, tokens, admission, caps)

**Files:**
- Create: `proxy-core/src/main/kotlin/com/wifiproxy/core/clients/ClientRegistry.kt`
- Test: `proxy-core/src/test/kotlin/com/wifiproxy/core/clients/ClientRegistryTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.wifiproxy.core.clients

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientRegistryTest {
    private var counter = 0
    private val tokens = TokenSource { "token-${counter++}".padEnd(22, 'x') }
    private fun ip(s: String) = InetAddress.getByName(s)
    private fun registry(max: Int = 3) =
        ClientRegistry(maxClients = max, tunnelCapPerSlot = 2, tokens = tokens, clock = { 1000L })

    @Test
    fun `addSlot respects maxClients`() {
        val r = registry(max = 1)
        assertNotNull(r.addSlot("a"))
        assertNull(r.addSlot("b"))
    }

    @Test
    fun `ip is admitted only after its slot token is fetched`() {
        val r = registry()
        val s = r.addSlot("phone")!!
        assertNull(r.admittedSlot(ip("192.168.49.10")))
        assertEquals(s.id, r.onPacFetch(s.token, ip("192.168.49.10"))?.id)
        assertEquals(s.id, r.admittedSlot(ip("192.168.49.10"))?.id)
    }

    @Test
    fun `unknown token admits nobody`() {
        val r = registry()
        r.addSlot("phone")
        assertNull(r.onPacFetch("unknown-token-xxxxxxxx", ip("192.168.49.10")))
        assertNull(r.admittedSlot(ip("192.168.49.10")))
    }

    @Test
    fun `slot follows its device to a new ip`() {
        val r = registry()
        val s = r.addSlot("phone")!!
        r.onPacFetch(s.token, ip("192.168.49.10"))
        r.onPacFetch(s.token, ip("192.168.49.20"))
        assertNull(r.admittedSlot(ip("192.168.49.10")))
        assertEquals(s.id, r.admittedSlot(ip("192.168.49.20"))?.id)
    }

    @Test
    fun `an ip belongs to at most one slot`() {
        val r = registry()
        val a = r.addSlot("a")!!
        val b = r.addSlot("b")!!
        r.onPacFetch(a.token, ip("192.168.49.10"))
        r.onPacFetch(b.token, ip("192.168.49.10"))
        assertEquals(b.id, r.admittedSlot(ip("192.168.49.10"))?.id)
        assertNull(r.slots().first { it.id == a.id }.boundIp)
    }

    @Test
    fun `revoke removes admission and frees capacity`() {
        val r = registry(max = 1)
        val s = r.addSlot("phone")!!
        r.onPacFetch(s.token, ip("192.168.49.10"))
        assertTrue(r.revoke(s.id))
        assertFalse(r.revoke(s.id))
        assertNull(r.admittedSlot(ip("192.168.49.10")))
        assertNotNull(r.addSlot("next"))
        r.releaseTunnel(s.id) // releasing on a revoked slot is a no-op
    }

    @Test
    fun `setMaxClients refuses to drop below active slots`() {
        val r = registry(max = 3)
        r.addSlot("a"); r.addSlot("b")
        assertFalse(r.setMaxClients(1))
        assertEquals(3, r.maxClients)
        assertTrue(r.setMaxClients(2))
        assertEquals(2, r.maxClients)
    }

    @Test
    fun `maxClients must be between 1 and 3`() {
        assertFailsWith<IllegalArgumentException> { registry().setMaxClients(4) }
        assertFailsWith<IllegalArgumentException> { registry().setMaxClients(0) }
        assertFailsWith<IllegalArgumentException> { ClientRegistry(maxClients = 4) }
    }

    @Test
    fun `tunnel cap is enforced per slot`() {
        val r = registry()
        val s = r.addSlot("phone")!!
        assertTrue(r.tryAcquireTunnel(s.id))
        assertTrue(r.tryAcquireTunnel(s.id))
        assertFalse(r.tryAcquireTunnel(s.id))
        r.releaseTunnel(s.id)
        assertTrue(r.tryAcquireTunnel(s.id))
        assertEquals(2, r.slots().single().activeTunnels)
        assertFalse(r.tryAcquireTunnel("no-such-slot"))
    }

    @Test
    fun `byte counters accumulate`() {
        val r = registry()
        val s = r.addSlot("phone")!!
        r.addBytes(s.id, 10, 20)
        r.addBytes(s.id, 5, 0)
        val slot = r.slots().single()
        assertEquals(15, slot.bytesUp)
        assertEquals(20, slot.bytesDown)
    }

    @Test
    fun `snapshot restores slots but not ip bindings`() {
        val r = registry()
        val s = r.addSlot("phone")!!
        r.onPacFetch(s.token, ip("192.168.49.10"))
        val restored = ClientRegistry(maxClients = 3, initial = r.snapshot())
        assertEquals(listOf(s.token), restored.slots().map { it.token })
        assertNull(restored.admittedSlot(ip("192.168.49.10")))
        assertEquals(s.id, restored.onPacFetch(s.token, ip("192.168.49.11"))?.id)
    }

    @Test
    fun `secure tokens are 22 url-safe characters`() {
        val t = SecureTokenSource().next()
        assertTrue(Regex("[A-Za-z0-9_-]{22}").matches(t), t)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.clients.*"`
Expected: FAIL, compilation error `Unresolved reference 'ClientRegistry'`.

- [ ] **Step 3: Implement `ClientRegistry.kt`**

```kotlin
package com.wifiproxy.core.clients

import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Point-in-time view of one client slot. */
data class Slot(
    val id: String,
    val name: String,
    val token: String,
    val boundIp: InetAddress?,
    val createdAtMs: Long,
    val lastSeenMs: Long?,
    val bytesUp: Long,
    val bytesDown: Long,
    val activeTunnels: Int,
)

/** The persistent part of a slot. The Android layer stores these and passes them back as `initial`. */
data class SlotRecord(val id: String, val name: String, val token: String, val createdAtMs: Long)

fun interface TokenSource {
    fun next(): String
}

class SecureTokenSource(private val random: SecureRandom = SecureRandom()) : TokenSource {
    override fun next(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * Client slots: each slot is identified by its token, not its IP. Fetching the slot's PAC
 * (or mobileconfig) binds the slot to the fetching IP; only bound IPs may use the proxy.
 */
class ClientRegistry(
    maxClients: Int = 1,
    private val tunnelCapPerSlot: Int = DEFAULT_TUNNEL_CAP,
    private val tokens: TokenSource = SecureTokenSource(),
    private val clock: () -> Long = System::currentTimeMillis,
    initial: List<SlotRecord> = emptyList(),
) {
    private class Entry(val record: SlotRecord) {
        var boundIp: InetAddress? = null
        var lastSeenMs: Long? = null
        var activeTunnels = 0
        val bytesUp = AtomicLong()
        val bytesDown = AtomicLong()

        fun toSlot() = Slot(
            record.id, record.name, record.token, boundIp, record.createdAtMs,
            lastSeenMs, bytesUp.get(), bytesDown.get(), activeTunnels,
        )
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    var maxClients: Int = maxClients
        private set

    init {
        requireValidMax(maxClients)
        require(initial.size <= maxClients) { "${initial.size} slots exceed maxClients $maxClients" }
        initial.forEach { entries[it.id] = Entry(it) }
    }

    fun addSlot(name: String): Slot? = synchronized(lock) {
        if (entries.size >= maxClients) return null
        val record = SlotRecord(UUID.randomUUID().toString(), name, tokens.next(), clock())
        Entry(record).also { entries[record.id] = it }.toSlot()
    }

    fun revoke(id: String): Boolean = synchronized(lock) { entries.remove(id) != null }

    /** Returns false (and changes nothing) if more slots are active than [n]; revoke first. */
    fun setMaxClients(n: Int): Boolean = synchronized(lock) {
        requireValidMax(n)
        if (entries.size > n) return false
        maxClients = n
        true
    }

    fun onPacFetch(token: String, ip: InetAddress): Slot? = synchronized(lock) {
        val presented = token.toByteArray()
        val entry = entries.values.firstOrNull { MessageDigest.isEqual(it.record.token.toByteArray(), presented) }
            ?: return null
        entries.values.forEach { if (it !== entry && it.boundIp == ip) it.boundIp = null }
        entry.boundIp = ip
        entry.lastSeenMs = clock()
        entry.toSlot()
    }

    fun admittedSlot(ip: InetAddress): Slot? = synchronized(lock) {
        val entry = entries.values.firstOrNull { it.boundIp == ip } ?: return null
        entry.lastSeenMs = clock()
        entry.toSlot()
    }

    fun tryAcquireTunnel(id: String): Boolean = synchronized(lock) {
        val entry = entries[id] ?: return false
        if (entry.activeTunnels >= tunnelCapPerSlot) return false
        entry.activeTunnels++
        true
    }

    fun releaseTunnel(id: String) {
        synchronized(lock) {
            val entry = entries[id] ?: return
            if (entry.activeTunnels > 0) entry.activeTunnels--
        }
    }

    fun addBytes(id: String, up: Long, down: Long) {
        val entry = synchronized(lock) { entries[id] } ?: return
        entry.bytesUp.addAndGet(up)
        entry.bytesDown.addAndGet(down)
    }

    fun slots(): List<Slot> = synchronized(lock) { entries.values.map { it.toSlot() } }

    fun snapshot(): List<SlotRecord> = synchronized(lock) { entries.values.map { it.record } }

    companion object {
        const val MAX_CLIENTS_LIMIT = 3
        const val DEFAULT_TUNNEL_CAP = 256

        private fun requireValidMax(n: Int) =
            require(n in 1..MAX_CLIENTS_LIMIT) { "maxClients must be 1..$MAX_CLIENTS_LIMIT, was $n" }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.clients.*"`
Expected: `BUILD SUCCESSFUL`, 12 tests passed.

- [ ] **Step 5: Commit**

```powershell
git add proxy-core/src
git commit -m "feat(core): client registry with token-bound slots and caps"
```

---

### Task 4: HTTP responses and request parser

**Files:**
- Create: `proxy-core/src/main/kotlin/com/wifiproxy/core/http/HttpResponse.kt`
- Create: `proxy-core/src/main/kotlin/com/wifiproxy/core/http/RequestParser.kt`
- Test: `proxy-core/src/test/kotlin/com/wifiproxy/core/http/RequestParserTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.wifiproxy.core.http

import com.wifiproxy.core.http.ProxyRequest.Connect
import com.wifiproxy.core.http.ProxyRequest.Forward
import com.wifiproxy.core.http.ProxyRequest.Local
import com.wifiproxy.core.http.ProxyRequest.Malformed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequestParserTest {
    private fun parse(head: String) = RequestParser.parse(head)
    private fun ByteArray.text() = toString(Charsets.ISO_8859_1)

    @Test
    fun `connect with host and port`() {
        assertEquals(Connect("example.com", 443), parse("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443"))
    }

    @Test
    fun `connect with bracketed ipv6`() {
        assertEquals(Connect("2001:db8::1", 443), parse("CONNECT [2001:db8::1]:443 HTTP/1.1"))
    }

    @Test
    fun `connect without port is malformed`() {
        assertIs<Malformed>(parse("CONNECT example.com HTTP/1.1"))
    }

    @Test
    fun `absolute http uri becomes forward`() {
        val r = assertIs<Forward>(parse("GET http://example.com:8081/a/b?q=1 HTTP/1.1\r\nHost: example.com:8081\r\nAccept: */*"))
        assertEquals("GET", r.method)
        assertEquals("example.com", r.host)
        assertEquals(8081, r.port)
        assertEquals("/a/b?q=1", r.target)
        assertEquals("HTTP/1.1", r.version)
    }

    @Test
    fun `absolute uri without path defaults to slash and port 80`() {
        val r = assertIs<Forward>(parse("GET http://example.com HTTP/1.1"))
        assertEquals("/", r.target)
        assertEquals(80, r.port)
    }

    @Test
    fun `origin form path is local and drops the query`() {
        assertEquals(Local("GET", "/abc.pac"), parse("GET /abc.pac?x=1 HTTP/1.1\r\nHost: 192.168.49.1:8080"))
    }

    @Test
    fun `unsupported or broken requests are malformed`() {
        listOf(
            "GET https://example.com/ HTTP/1.1",
            "GET http://user@example.com/ HTTP/1.1",
            "hello",
            "GET / HTTP/2.0",
            "GET /\tHTTP/1.1",
            "GET / HTTP/1.1\r\nNoColonHeader",
            "CONNECT example.com:99999 HTTP/1.1",
            "CONNECT example.com:+80 HTTP/1.1",
        ).forEach { assertIs<Malformed>(parse(it), it) }
    }

    @Test
    fun `upstream head strips hop-by-hop headers and forces close`() {
        val r = parse(
            "POST http://example.com/x HTTP/1.1\r\nHost: example.com\r\nProxy-Connection: keep-alive\r\n" +
                "Proxy-Authorization: Basic abc\r\nConnection: keep-alive, X-Foo\r\nX-Foo: 1\r\nKeep-Alive: 300\r\n" +
                "Content-Length: 3"
        ) as Forward
        assertEquals(
            "POST /x HTTP/1.1\r\nHost: example.com\r\nContent-Length: 3\r\nConnection: close\r\n\r\n",
            r.upstreamHead().text(),
        )
    }

    @Test
    fun `upstream head adds a missing host and keeps the version`() {
        val r = parse("GET http://example.com:8081/ HTTP/1.0") as Forward
        assertEquals("GET / HTTP/1.0\r\nHost: example.com:8081\r\nConnection: close\r\n\r\n", r.upstreamHead().text())
    }

    @Test
    fun `responses serialize with length and close`() {
        val text = HttpResponse(200, "OK", "text/plain", "hi".toByteArray()).toBytes().text()
        assertEquals(
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\nCache-Control: no-store\r\nConnection: close\r\n\r\nhi",
            text,
        )
        assertTrue(!HttpResponse(204, "No Content").toBytes().text().contains("Content-Length"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.http.*"`
Expected: FAIL, compilation error `Unresolved reference 'RequestParser'`.

- [ ] **Step 3: Implement `HttpResponse.kt`**

```kotlin
package com.wifiproxy.core.http

/** A complete, self-contained response; the proxy always closes the connection after sending it. */
class HttpResponse(
    val status: Int,
    val reason: String,
    val contentType: String? = null,
    val body: ByteArray = ByteArray(0),
) {
    fun toBytes(): ByteArray {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            if (status != 204) append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    companion object {
        val BAD_REQUEST = HttpResponse(400, "Bad Request")
        val FORBIDDEN = HttpResponse(403, "Forbidden")
        val NOT_FOUND = HttpResponse(404, "Not Found")
        val METHOD_NOT_ALLOWED = HttpResponse(405, "Method Not Allowed")
        val BAD_GATEWAY = HttpResponse(502, "Bad Gateway")
        val SERVICE_UNAVAILABLE = HttpResponse(503, "Service Unavailable")
        val GATEWAY_TIMEOUT = HttpResponse(504, "Gateway Timeout")

        val CONNECTION_ESTABLISHED: ByteArray =
            "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}
```

- [ ] **Step 4: Implement `RequestParser.kt`**

```kotlin
package com.wifiproxy.core.http

sealed interface ProxyRequest {
    data class Connect(val host: String, val port: Int) : ProxyRequest

    data class Forward(
        val method: String,
        val host: String,
        val port: Int,
        /** Origin-form target: path plus query, always starting with '/'. */
        val target: String,
        val version: String,
        val headers: List<Pair<String, String>>,
    ) : ProxyRequest {
        /** Head to send upstream: origin-form, hop-by-hop headers removed, one request per connection. */
        fun upstreamHead(): ByteArray {
            val listed = headers.filter { it.first.equals("Connection", ignoreCase = true) }
                .flatMap { it.second.split(',') }
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            val kept = headers.filter { (name, _) -> name.lowercase().let { it !in HOP_BY_HOP && it !in listed } }
            val sb = StringBuilder().append(method).append(' ').append(target).append(' ').append(version).append("\r\n")
            if (kept.none { it.first.equals("Host", ignoreCase = true) }) sb.append("Host: ").append(hostHeader()).append("\r\n")
            kept.forEach { (name, value) -> sb.append(name).append(": ").append(value).append("\r\n") }
            sb.append("Connection: close\r\n\r\n")
            return sb.toString().toByteArray(Charsets.ISO_8859_1)
        }

        private fun hostHeader(): String {
            val h = if (':' in host) "[$host]" else host
            return if (port == 80) h else "$h:$port"
        }
    }

    /** Origin-form request addressed to the proxy itself (PAC, mobileconfig). */
    data class Local(val method: String, val path: String) : ProxyRequest

    data class Malformed(val reason: String) : ProxyRequest
}

private val HOP_BY_HOP = setOf(
    "connection", "proxy-connection", "keep-alive", "proxy-authorization",
    "proxy-authenticate", "te", "trailer", "upgrade",
)

object RequestParser {
    /** [head] is the request head without the terminating blank line. */
    fun parse(head: String): ProxyRequest {
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3) return ProxyRequest.Malformed("bad request line")
        val (method, target, version) = parts
        if (method.isEmpty() || !version.startsWith("HTTP/1.")) return ProxyRequest.Malformed("unsupported version")

        val headers = ArrayList<Pair<String, String>>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) return ProxyRequest.Malformed("bad header")
            headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }

        return when {
            method == "CONNECT" -> parseAuthority(target, defaultPort = null)
                ?.let { (host, port) -> ProxyRequest.Connect(host, port) }
                ?: ProxyRequest.Malformed("bad CONNECT target")
            target.startsWith("http://", ignoreCase = true) -> parseAbsolute(method, target.substring(7), version, headers)
            target.startsWith("/") -> ProxyRequest.Local(method, target.substringBefore('?'))
            else -> ProxyRequest.Malformed("unsupported target")
        }
    }

    private fun parseAbsolute(
        method: String,
        rest: String,
        version: String,
        headers: List<Pair<String, String>>,
    ): ProxyRequest {
        val end = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, end)
        if ('@' in authority) return ProxyRequest.Malformed("userinfo not allowed")
        val (host, port) = parseAuthority(authority, defaultPort = 80) ?: return ProxyRequest.Malformed("bad authority")
        val path = rest.substring(end).let {
            when {
                it.isEmpty() -> "/"
                it.startsWith("?") -> "/$it"
                else -> it
            }
        }
        return ProxyRequest.Forward(method, host, port, path, version, headers)
    }

    private fun parseAuthority(text: String, defaultPort: Int?): Pair<String, Int>? {
        val host: String
        val portText: String?
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close < 0) return null
            host = text.substring(1, close)
            val after = text.substring(close + 1)
            portText = when {
                after.isEmpty() -> null
                after.startsWith(":") -> after.substring(1)
                else -> return null
            }
        } else {
            val colon = text.lastIndexOf(':')
            if (colon >= 0 && text.indexOf(':') != colon) return null
            host = if (colon >= 0) text.substring(0, colon) else text
            portText = if (colon >= 0) text.substring(colon + 1) else null
        }
        if (host.isEmpty()) return null
        val port = if (portText == null) {
            defaultPort ?: return null
        } else {
            if (portText.isEmpty() || !portText.all { it in '0'..'9' }) return null
            portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        }
        return host to port
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.http.*"`
Expected: `BUILD SUCCESSFUL`, 10 tests passed.

- [ ] **Step 6: Commit**

```powershell
git add proxy-core/src
git commit -m "feat(core): HTTP proxy request parser and canned responses"
```

---

### Task 5: PAC file, mobileconfig profile, connectivity probes

**Files:**
- Create: `proxy-core/src/main/kotlin/com/wifiproxy/core/content/PacFile.kt`
- Create: `proxy-core/src/main/kotlin/com/wifiproxy/core/content/MobileConfig.kt`
- Create: `proxy-core/src/main/kotlin/com/wifiproxy/core/content/Probes.kt`
- Test: `proxy-core/src/test/kotlin/com/wifiproxy/core/content/ContentTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.wifiproxy.core.content

import com.wifiproxy.core.http.ProxyRequest
import com.wifiproxy.core.http.RequestParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentTest {
    private fun request(line: String) = RequestParser.parse(line) as ProxyRequest.Forward

    @Test
    fun `pac returns a single proxy directive`() {
        assertEquals(
            "function FindProxyForURL(url, host) {\n  return \"PROXY 192.168.49.1:8080\";\n}\n",
            PacFile.render("192.168.49.1", 8080),
        )
    }

    @Test
    fun `mobileconfig contains escaped wifi and auto proxy payload`() {
        val xml = MobileConfig.render("DIRECT-cv-Ben", "p&ss<word>", "http://192.168.49.1:8080/tok.pac", "tok")
        assertTrue(xml.startsWith("<?xml"))
        assertTrue("<key>SSID_STR</key><string>DIRECT-cv-Ben</string>" in xml)
        assertTrue("<key>Password</key><string>p&amp;ss&lt;word&gt;</string>" in xml)
        assertTrue("<key>ProxyType</key><string>Auto</string>" in xml)
        assertTrue("<key>ProxyPACURL</key><string>http://192.168.49.1:8080/tok.pac</string>" in xml)
    }

    @Test
    fun `mobileconfig payload uuids are stable per token`() {
        val a = MobileConfig.render("s", "password1", "http://h/t.pac", "tok")
        assertEquals(a, MobileConfig.render("s", "password1", "http://h/t.pac", "tok"))
        assertNotEquals(a, MobileConfig.render("s", "password1", "http://h/t.pac", "tok2"))
    }

    @Test
    fun `android probes get 204`() {
        assertEquals(204, Probes.match(request("GET http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1"))?.status)
        assertEquals(204, Probes.match(request("GET http://www.google.com/gen_204 HTTP/1.1"))?.status)
    }

    @Test
    fun `apple probe gets the success page`() {
        val r = Probes.match(request("GET http://captive.apple.com/hotspot-detect.html HTTP/1.1"))!!
        assertEquals(200, r.status)
        assertTrue("Success" in r.body.toString(Charsets.UTF_8))
    }

    @Test
    fun `other requests are not probes`() {
        assertNull(Probes.match(request("GET http://example.com/generate_204 HTTP/1.1")))
        assertNull(Probes.match(request("POST http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1")))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.content.ContentTest"`
Expected: FAIL, compilation error `Unresolved reference 'PacFile'`.

- [ ] **Step 3: Implement `PacFile.kt`**

```kotlin
package com.wifiproxy.core.content

/** The link has no DNS, so the PAC must never call dnsResolve/isInNet. */
object PacFile {
    const val CONTENT_TYPE = "application/x-ns-proxy-autoconfig"

    fun render(proxyHost: String, proxyPort: Int): String =
        "function FindProxyForURL(url, host) {\n  return \"PROXY $proxyHost:$proxyPort\";\n}\n"
}
```

- [ ] **Step 4: Implement `MobileConfig.kt`**

```kotlin
package com.wifiproxy.core.content

import java.util.UUID

/** iOS configuration profile: one Wi-Fi payload with automatic (PAC) proxy. */
object MobileConfig {
    const val CONTENT_TYPE = "application/x-apple-aspen-config"

    fun render(ssid: String, password: String, pacUrl: String, token: String): String {
        val wifiUuid = UUID.nameUUIDFromBytes("wifi:$token".toByteArray()).toString().uppercase()
        val profileUuid = UUID.nameUUIDFromBytes("profile:$token".toByteArray()).toString().uppercase()
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            |<plist version="1.0">
            |<dict>
            |<key>PayloadContent</key>
            |<array>
            |<dict>
            |<key>PayloadType</key><string>com.apple.wifi.managed</string>
            |<key>PayloadVersion</key><integer>1</integer>
            |<key>PayloadIdentifier</key><string>com.wifiproxy.wifi.$wifiUuid</string>
            |<key>PayloadUUID</key><string>$wifiUuid</string>
            |<key>PayloadDisplayName</key><string>Wi-Fi (${escape(ssid)})</string>
            |<key>SSID_STR</key><string>${escape(ssid)}</string>
            |<key>EncryptionType</key><string>WPA2</string>
            |<key>Password</key><string>${escape(password)}</string>
            |<key>AutoJoin</key><true/>
            |<key>ProxyType</key><string>Auto</string>
            |<key>ProxyPACURL</key><string>${escape(pacUrl)}</string>
            |<key>ProxyPACFallbackAllowed</key><false/>
            |</dict>
            |</array>
            |<key>PayloadType</key><string>Configuration</string>
            |<key>PayloadVersion</key><integer>1</integer>
            |<key>PayloadIdentifier</key><string>com.wifiproxy.profile.$profileUuid</string>
            |<key>PayloadUUID</key><string>$profileUuid</string>
            |<key>PayloadDisplayName</key><string>Wi-Fi Proxy (${escape(ssid)})</string>
            |<key>PayloadRemovalDisallowed</key><false/>
            |</dict>
            |</plist>
        """.trimMargin() + "\n"
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
```

- [ ] **Step 5: Implement `Probes.kt`**

```kotlin
package com.wifiproxy.core.content

import com.wifiproxy.core.http.HttpResponse
import com.wifiproxy.core.http.ProxyRequest

/** Connectivity-check URLs the proxy answers itself while the cellular egress is validated. */
object Probes {
    private val NO_CONTENT = setOf(
        "connectivitycheck.gstatic.com" to "/generate_204",
        "connectivitycheck.android.com" to "/generate_204",
        "clients3.google.com" to "/generate_204",
        "play.googleapis.com" to "/generate_204",
        "www.google.com" to "/gen_204",
    )

    private val APPLE_SUCCESS =
        "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>".toByteArray(Charsets.UTF_8)

    fun match(request: ProxyRequest.Forward): HttpResponse? {
        if (request.method != "GET" && request.method != "HEAD") return null
        val host = request.host.lowercase()
        val path = request.target.substringBefore('?')
        return when {
            (host to path) in NO_CONTENT -> HttpResponse(204, "No Content")
            host == "captive.apple.com" ||
                (host == "www.apple.com" && path == "/library/test/success.html") ->
                HttpResponse(200, "OK", "text/html", APPLE_SUCCESS)
            else -> null
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.content.ContentTest"`
Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 7: Commit**

```powershell
git add proxy-core/src
git commit -m "feat(core): PAC, iOS mobileconfig and connectivity probe responses"
```

---

### Task 6: NIO loop and channel I/O helpers

**Files:**
- Create: `proxy-core/src/main/kotlin/com/wifiproxy/core/io/NioLoop.kt`
- Create: `proxy-core/src/main/kotlin/com/wifiproxy/core/io/ChannelIo.kt`
- Create: `proxy-core/src/test/kotlin/com/wifiproxy/core/TestSockets.kt`
- Test: `proxy-core/src/test/kotlin/com/wifiproxy/core/io/NioLoopTest.kt`
- Test: `proxy-core/src/test/kotlin/com/wifiproxy/core/io/ChannelIoTest.kt`

- [ ] **Step 1: Create the shared test helpers `TestSockets.kt`**

```kotlin
package com.wifiproxy.core

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/** Two connected loopback channels, both non-blocking. */
fun socketPair(): Pair<SocketChannel, SocketChannel> {
    ServerSocketChannel.open().use { server ->
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val a = SocketChannel.open(server.localAddress)
        val b = server.accept()
        a.configureBlocking(false)
        b.configureBlocking(false)
        return a to b
    }
}

// The helpers below switch the channel to blocking mode; only use them on channels never awaited via NioLoop.

fun SocketChannel.writeBlocking(text: String) = writeBlocking(text.toByteArray(Charsets.ISO_8859_1))

fun SocketChannel.writeBlocking(bytes: ByteArray) {
    configureBlocking(true)
    val buf = ByteBuffer.wrap(bytes)
    while (buf.hasRemaining()) write(buf)
}

fun SocketChannel.readExactlyBlocking(n: Int): String {
    configureBlocking(true)
    val buf = ByteBuffer.allocate(n)
    while (buf.hasRemaining()) {
        if (read(buf) < 0) break
    }
    return String(buf.array(), 0, buf.position(), Charsets.ISO_8859_1)
}

fun SocketChannel.readAllBlocking(): ByteArray {
    configureBlocking(true)
    val out = ByteArrayOutputStream()
    val buf = ByteBuffer.allocate(64 * 1024)
    while (true) {
        buf.clear()
        val n = read(buf)
        if (n < 0) break
        out.write(buf.array(), 0, n)
    }
    return out.toByteArray()
}
```

- [ ] **Step 2: Write the failing tests**

`NioLoopTest.kt`:

```kotlin
package com.wifiproxy.core.io

import com.wifiproxy.core.socketPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class NioLoopTest {
    private val loop = NioLoop()

    @AfterTest
    fun tearDown() = loop.close()

    @Test
    fun `await read resumes when data arrives`() = runBlocking {
        val (a, b) = socketPair()
        val got = async(Dispatchers.Default) {
            loop.await(b, SelectionKey.OP_READ)
            val buf = ByteBuffer.allocate(8)
            b.read(buf)
            String(buf.array(), 0, buf.position())
        }
        delay(50)
        a.write(ByteBuffer.wrap("hi".toByteArray()))
        assertEquals("hi", withTimeout(2000) { got.await() })
        a.close(); b.close()
    }

    @Test
    fun `cancelled waiter does not block later waiters`() = runBlocking {
        val (a, b) = socketPair()
        val job = launch(Dispatchers.Default) { loop.await(b, SelectionKey.OP_READ) }
        delay(50)
        job.cancelAndJoin()
        a.write(ByteBuffer.wrap("x".toByteArray()))
        withTimeout(2000) { loop.await(b, SelectionKey.OP_READ) }
        a.close(); b.close()
    }

    @Test
    fun `await on a closed channel throws`() = runBlocking {
        val (a, b) = socketPair()
        b.close()
        assertFailsWith<ClosedChannelException> { withTimeout(2000) { loop.await(b, SelectionKey.OP_READ) } }
        a.close()
    }

    @Test
    fun `close fails pending waiters`() = runBlocking {
        val (a, b) = socketPair()
        val waiter = async(Dispatchers.Default) { runCatching { loop.await(b, SelectionKey.OP_READ) } }
        delay(50)
        loop.close()
        assertIs<ClosedChannelException>(withTimeout(2000) { waiter.await() }.exceptionOrNull())
        a.close(); b.close()
    }
}
```

`ChannelIoTest.kt`:

```kotlin
package com.wifiproxy.core.io

import com.wifiproxy.core.readAllBlocking
import com.wifiproxy.core.socketPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChannelIoTest {
    private val loop = NioLoop()

    @AfterTest
    fun tearDown() = loop.close()

    private fun bytes(s: String) = ByteBuffer.wrap(s.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun `readHead splits head and leftover`() = runBlocking {
        val (a, b) = socketPair()
        a.write(bytes("GET / HTTP/1.1\r\nHost: x\r\n\r\nBODY"))
        val head = withTimeout(2000) { readHead(loop, b) }!!
        assertEquals("GET / HTTP/1.1\r\nHost: x", head.text)
        assertEquals("BODY", head.leftover.toString(Charsets.ISO_8859_1))
        a.close(); b.close()
    }

    @Test
    fun `readHead assembles a head split across packets`() = runBlocking {
        val (a, b) = socketPair()
        val head = async(Dispatchers.Default) { readHead(loop, b) }
        a.write(bytes("GET / HT"))
        delay(50)
        a.write(bytes("TP/1.1\r\n\r"))
        delay(50)
        a.write(bytes("\n"))
        assertEquals("GET / HTTP/1.1", withTimeout(2000) { head.await() }!!.text)
        a.close(); b.close()
    }

    @Test
    fun `readHead returns null on eof before a full head`() = runBlocking {
        val (a, b) = socketPair()
        a.write(bytes("GET /"))
        a.close()
        assertNull(withTimeout(2000) { readHead(loop, b) })
        b.close()
    }

    @Test
    fun `readHead rejects oversized heads`() = runBlocking {
        val (a, b) = socketPair()
        a.write(bytes("X".repeat(64)))
        assertFailsWith<HeadTooLargeException> { withTimeout(2000) { readHead(loop, b, maxBytes = 32) } }
        a.close(); b.close()
    }

    @Test
    fun `writeFully survives backpressure`() = runBlocking {
        val (a, b) = socketPair()
        val payload = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val reader = async(Dispatchers.IO) {
            b.configureBlocking(true)
            val out = ByteArrayOutputStream()
            val buf = ByteBuffer.allocate(64 * 1024)
            while (out.size() < payload.size) {
                buf.clear()
                val n = b.read(buf)
                if (n < 0) break
                out.write(buf.array(), 0, n)
            }
            out.toByteArray()
        }
        withTimeout(10_000) { writeFully(loop, a, payload) }
        assertContentEquals(payload, withTimeout(10_000) { reader.await() })
        a.close(); b.close()
    }

    @Test
    fun `pump copies until eof then half-closes the destination`() = runBlocking {
        val (src, srcPeer) = socketPair()
        val (dstPeer, dst) = socketPair()
        var counted = 0
        val job = async(Dispatchers.Default) { pump(loop, srcPeer, dstPeer, ByteBuffer.allocate(1024)) { counted += it } }
        src.write(bytes("hello"))
        src.shutdownOutput()
        withTimeout(2000) { job.await() }
        assertEquals("hello", dst.readAllBlocking().toString(Charsets.ISO_8859_1))
        assertEquals(5, counted)
        listOf(src, srcPeer, dstPeer, dst).forEach { it.close() }
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.io.*"`
Expected: FAIL, compilation error `Unresolved reference 'NioLoop'`.

- [ ] **Step 4: Implement `NioLoop.kt`**

```kotlin
package com.wifiproxy.core.io

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.IOException
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedChannelException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private typealias Waiters = HashMap<Int, CancellableContinuation<Unit>>

/**
 * A single selector thread that lets coroutines suspend until a non-blocking channel is ready.
 * Callers must cancel any coroutine waiting on a channel before closing that channel.
 */
class NioLoop : Closeable {
    private class Registration(val channel: SelectableChannel, val op: Int, val cont: CancellableContinuation<Unit>)

    private val selector: Selector = Selector.open()
    private val pending = ConcurrentLinkedQueue<Registration>()

    @Volatile
    private var open = true
    private val thread = Thread(::run, "nio-loop").apply {
        isDaemon = true
        start()
    }

    suspend fun await(channel: SelectableChannel, op: Int) {
        require(op in OPS) { "unsupported interest op $op" }
        if (!open) throw ClosedChannelException()
        suspendCancellableCoroutine { cont ->
            pending.add(Registration(channel, op, cont))
            selector.wakeup()
        }
    }

    @Synchronized
    override fun close() {
        if (!open) return
        open = false
        selector.wakeup()
        if (Thread.currentThread() !== thread) thread.join(1000)
    }

    private fun run() {
        try {
            while (open) {
                selector.select()
                registerPending()
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    dispatch(key)
                }
            }
        } catch (e: ClosedSelectorException) {
            // fall through to cleanup
        } catch (e: IOException) {
            // selector failure: fail every waiter below
        } finally {
            failAll()
            runCatching { selector.close() }
        }
    }

    private fun registerPending() {
        while (true) {
            val r = pending.poll() ?: return
            if (!r.cont.isActive) continue
            try {
                val key = r.channel.keyFor(selector) ?: r.channel.register(selector, 0, Waiters())
                @Suppress("UNCHECKED_CAST")
                (key.attachment() as Waiters)[r.op] = r.cont
                key.interestOps(key.interestOps() or r.op)
            } catch (e: Exception) {
                r.cont.resumeWithException(if (e is CancelledKeyException) ClosedChannelException() else e)
            }
        }
    }

    private fun dispatch(key: SelectionKey) {
        @Suppress("UNCHECKED_CAST")
        val waiters = key.attachment() as Waiters
        try {
            val ready = key.readyOps()
            var interest = key.interestOps()
            for (op in OPS) {
                if ((ready and op) == 0) continue
                interest = interest and op.inv()
                waiters.remove(op)?.resume(Unit)
            }
            val stale = waiters.filterValues { !it.isActive }.keys.toList()
            stale.forEach { op ->
                waiters.remove(op)
                interest = interest and op.inv()
            }
            key.interestOps(interest)
        } catch (e: CancelledKeyException) {
            waiters.values.forEach { it.resumeWithException(ClosedChannelException()) }
            waiters.clear()
        }
    }

    private fun failAll() {
        runCatching {
            selector.keys().forEach { key ->
                @Suppress("UNCHECKED_CAST")
                (key.attachment() as? Waiters)?.let { waiters ->
                    waiters.values.forEach { it.resumeWithException(ClosedChannelException()) }
                    waiters.clear()
                }
            }
        }
        while (true) {
            val r = pending.poll() ?: break
            r.cont.resumeWithException(ClosedChannelException())
        }
    }

    private companion object {
        val OPS = intArrayOf(SelectionKey.OP_READ, SelectionKey.OP_WRITE, SelectionKey.OP_CONNECT, SelectionKey.OP_ACCEPT)
    }
}
```

- [ ] **Step 5: Implement `ChannelIo.kt`**

```kotlin
package com.wifiproxy.core.io

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

const val MAX_HEAD_BYTES = 16 * 1024

class HeadTooLargeException : IOException("request head exceeds limit")

/** Request head text without the terminating blank line, plus any body bytes read past it. */
class Head(val text: String, val leftover: ByteArray)

/** Reads until CRLFCRLF. Returns null if the peer closes first. */
suspend fun readHead(loop: NioLoop, ch: SocketChannel, maxBytes: Int = MAX_HEAD_BYTES): Head? {
    val buf = ByteBuffer.allocate(maxBytes)
    var scanFrom = 0
    while (true) {
        val n = ch.read(buf)
        if (n < 0) return null
        if (n == 0) {
            loop.await(ch, SelectionKey.OP_READ)
            continue
        }
        val end = indexOfBlankLine(buf.array(), scanFrom, buf.position())
        if (end >= 0) {
            val text = String(buf.array(), 0, end, Charsets.ISO_8859_1)
            return Head(text, buf.array().copyOfRange(end + 4, buf.position()))
        }
        if (!buf.hasRemaining()) throw HeadTooLargeException()
        scanFrom = maxOf(0, buf.position() - 3)
    }
}

suspend fun writeFully(loop: NioLoop, ch: SocketChannel, bytes: ByteArray) {
    val buf = ByteBuffer.wrap(bytes)
    while (buf.hasRemaining()) {
        if (ch.write(buf) == 0) loop.await(ch, SelectionKey.OP_WRITE)
    }
}

/** Copies [src] to [dst] until EOF, then half-closes [dst]. [onBytes] runs after each chunk is written. */
suspend fun pump(loop: NioLoop, src: SocketChannel, dst: SocketChannel, buf: ByteBuffer, onBytes: (Int) -> Unit) {
    while (true) {
        buf.clear()
        val n = src.read(buf)
        if (n < 0) {
            runCatching { dst.shutdownOutput() }
            return
        }
        if (n == 0) {
            loop.await(src, SelectionKey.OP_READ)
            continue
        }
        buf.flip()
        while (buf.hasRemaining()) {
            if (dst.write(buf) == 0) loop.await(dst, SelectionKey.OP_WRITE)
        }
        onBytes(n)
    }
}

private const val CR: Byte = 13
private const val LF: Byte = 10

internal fun indexOfBlankLine(bytes: ByteArray, from: Int, until: Int): Int {
    var i = from
    while (i + 3 < until) {
        if (bytes[i] == CR && bytes[i + 1] == LF && bytes[i + 2] == CR && bytes[i + 3] == LF) return i
        i++
    }
    return -1
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.io.*"`
Expected: `BUILD SUCCESSFUL`, 10 tests passed.

- [ ] **Step 7: Commit**

```powershell
git add proxy-core/src
git commit -m "feat(core): selector loop with suspend await, head reader and pump"
```

---

### Task 7: Tunnel (bidirectional pump with idle timeout)

**Files:**
- Create: `proxy-core/src/main/kotlin/com/wifiproxy/core/io/Tunnel.kt`
- Test: `proxy-core/src/test/kotlin/com/wifiproxy/core/io/TunnelTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.wifiproxy.core.io

import com.wifiproxy.core.readExactlyBlocking
import com.wifiproxy.core.socketPair
import com.wifiproxy.core.writeBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TunnelTest {
    private val loop = NioLoop()

    @AfterTest
    fun tearDown() = loop.close()

    @Test
    fun `copies both directions and ends when both sides close`() = runBlocking {
        val (app, clientSide) = socketPair()
        val (upSide, server) = socketPair()
        val up = AtomicInteger()
        val down = AtomicInteger()
        val run = launch(Dispatchers.Default) {
            Tunnel(loop, clientSide, upSide, idleTimeoutMs = 60_000, onUp = { up.addAndGet(it) }, onDown = { down.addAndGet(it) })
                .run(closeWhenResponseEnds = false)
        }
        app.writeBlocking("ping")
        assertEquals("ping", server.readExactlyBlocking(4))
        server.writeBlocking("pong!")
        assertEquals("pong!", app.readExactlyBlocking(5))
        app.shutdownOutput()
        server.shutdownOutput()
        withTimeout(2000) { run.join() }
        assertEquals(4, up.get())
        assertEquals(5, down.get())
        listOf(app, clientSide, upSide, server).forEach { it.close() }
    }

    @Test
    fun `idle tunnel ends after the timeout`() = runBlocking {
        val (app, clientSide) = socketPair()
        val (upSide, server) = socketPair()
        val run = launch(Dispatchers.Default) {
            Tunnel(loop, clientSide, upSide, idleTimeoutMs = 200).run(closeWhenResponseEnds = false)
        }
        withTimeout(3000) { run.join() }
        listOf(app, clientSide, upSide, server).forEach { it.close() }
    }

    @Test
    fun `forward tunnel ends when the response ends`() = runBlocking {
        val (app, clientSide) = socketPair()
        val (upSide, server) = socketPair()
        val run = launch(Dispatchers.Default) {
            Tunnel(loop, clientSide, upSide, idleTimeoutMs = 60_000).run(closeWhenResponseEnds = true)
        }
        server.writeBlocking("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
        server.shutdownOutput()
        withTimeout(2000) { run.join() }
        listOf(app, clientSide, upSide, server).forEach { it.close() }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.io.TunnelTest"`
Expected: FAIL, compilation error `Unresolved reference 'Tunnel'`.

- [ ] **Step 3: Implement `Tunnel.kt`**

```kotlin
package com.wifiproxy.core.io

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong

fun monotonicMs(): Long = System.nanoTime() / 1_000_000

/**
 * Pumps client<->upstream until both directions reach EOF, the tunnel is idle for
 * [idleTimeoutMs], or (when closeWhenResponseEnds) the upstream finishes its response.
 * The caller owns and closes both channels.
 */
class Tunnel(
    private val loop: NioLoop,
    private val client: SocketChannel,
    private val upstream: SocketChannel,
    private val idleTimeoutMs: Long,
    private val clock: () -> Long = ::monotonicMs,
    private val onUp: (Int) -> Unit = {},
    private val onDown: (Int) -> Unit = {},
) {
    suspend fun run(closeWhenResponseEnds: Boolean) = coroutineScope {
        val lastActivity = AtomicLong(clock())
        val up = launch {
            pump(loop, client, upstream, ByteBuffer.allocate(BUFFER_SIZE)) { n ->
                lastActivity.set(clock())
                onUp(n)
            }
        }
        val down = launch {
            pump(loop, upstream, client, ByteBuffer.allocate(BUFFER_SIZE)) { n ->
                lastActivity.set(clock())
                onDown(n)
            }
            if (closeWhenResponseEnds) up.cancel()
        }
        val idle = launch {
            val tick = (idleTimeoutMs / 4).coerceIn(10, 1000)
            while (isActive) {
                delay(tick)
                if (clock() - lastActivity.get() >= idleTimeoutMs) {
                    up.cancel()
                    down.cancel()
                }
            }
        }
        up.join()
        down.join()
        idle.cancel()
    }

    companion object {
        const val BUFFER_SIZE = 32 * 1024
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.io.TunnelTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```powershell
git add proxy-core/src
git commit -m "feat(core): bidirectional tunnel with idle timeout"
```

---

### Task 8: Egress abstraction and happy-eyeballs dialer

**Files:**
- Create: `proxy-core/src/main/kotlin/com/wifiproxy/core/egress/Egress.kt`
- Create: `proxy-core/src/main/kotlin/com/wifiproxy/core/egress/Dialer.kt`
- Test: `proxy-core/src/test/kotlin/com/wifiproxy/core/egress/DialerTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.wifiproxy.core.egress

import com.wifiproxy.core.io.NioLoop
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DialerTest {
    private val v6a = InetAddress.getByName("2001:db8::1")
    private val v6b = InetAddress.getByName("2001:db8::2")
    private val v4a = InetAddress.getByName("192.0.2.1")
    private val v4b = InetAddress.getByName("192.0.2.2")

    @Test
    fun `order interleaves families starting with ipv6`() {
        assertEquals(listOf(v6a, v4a, v6b, v4b), Dialer.order(listOf(v4a, v4b, v6a, v6b)))
        assertEquals(listOf(v4a, v4b), Dialer.order(listOf(v4a, v4b)))
    }

    @Test
    fun `first success wins without trying later addresses`() = runTest {
        val attempted = mutableListOf<InetAddress>()
        val winner = SocketChannel.open()
        val dialer = Dialer(staggerMs = 250, timeoutMs = 10_000) { addr, _ -> attempted += addr; winner }
        assertSame(winner, dialer.dial(listOf(v4a, v6a), 443))
        assertEquals(listOf(v6a), attempted)
        winner.close()
    }

    @Test
    fun `slow ipv6 loses to ipv4 after the stagger`() = runTest {
        val fast = SocketChannel.open()
        val dialer = Dialer(staggerMs = 250, timeoutMs = 10_000) { addr, _ ->
            if (addr == v6a) { delay(5_000); SocketChannel.open() } else fast
        }
        assertSame(fast, dialer.dial(listOf(v6a, v4a), 443))
        assertEquals(250L, currentTime)
        fast.close()
    }

    @Test
    fun `falls back when the first address fails`() = runTest {
        val ok = SocketChannel.open()
        val dialer = Dialer(staggerMs = 250, timeoutMs = 10_000) { addr, _ ->
            if (addr == v6a) throw ConnectException("refused") else ok
        }
        assertSame(ok, dialer.dial(listOf(v6a, v4a), 443))
        ok.close()
    }

    @Test
    fun `all failures surface as IOException`() = runTest {
        val dialer = Dialer(staggerMs = 250, timeoutMs = 10_000) { _, _ -> throw ConnectException("refused") }
        assertFailsWith<IOException> { dialer.dial(listOf(v6a, v4a), 443) }
    }

    @Test
    fun `overall timeout`() = runTest {
        val dialer = Dialer(staggerMs = 250, timeoutMs = 1_000) { _, _ -> delay(60_000); SocketChannel.open() }
        assertFailsWith<TimeoutCancellationException> { dialer.dial(listOf(v4a), 443) }
    }

    @Test
    fun `connects to a real loopback listener`() = runBlocking {
        ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val port = (server.localAddress as InetSocketAddress).port
            NioLoop().use { loop ->
                Dialer.forEgress(loop, DirectEgress()).dial(listOf(InetAddress.getLoopbackAddress()), port).use {
                    assertTrue(it.isConnected)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.egress.*"`
Expected: FAIL, compilation error `Unresolved reference 'Dialer'`.

- [ ] **Step 3: Implement `Egress.kt`**

```kotlin
package com.wifiproxy.core.egress

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.nio.channels.SocketChannel

/**
 * Where upstream traffic leaves the host. The Android implementation (Plan 2) binds every
 * channel and lookup to the cellular Network and never falls back to the default network.
 */
interface Egress {
    /** False while no egress network exists; the proxy answers 503. */
    val isAvailable: Boolean

    /** True when the egress network has validated internet; gates local probe answers. */
    val isValidated: Boolean

    suspend fun resolve(host: String): List<InetAddress>

    /** A new, unconnected channel already bound to the egress network. */
    fun newChannel(): SocketChannel
}

/** Uses the JVM's default network. For tests and desktop runs only. */
class DirectEgress(
    override val isAvailable: Boolean = true,
    override val isValidated: Boolean = true,
) : Egress {
    override suspend fun resolve(host: String): List<InetAddress> =
        withContext(Dispatchers.IO) { InetAddress.getAllByName(host).toList() }

    override fun newChannel(): SocketChannel = SocketChannel.open()
}
```

- [ ] **Step 4: Implement `Dialer.kt`**

```kotlin
package com.wifiproxy.core.egress

import com.wifiproxy.core.io.NioLoop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicInteger

/**
 * Happy-eyeballs-style dialer: attempts start [staggerMs] apart in [order], the first success wins
 * and the rest are cancelled. Throws IOException if every attempt fails, TimeoutCancellationException
 * after [timeoutMs].
 */
class Dialer(
    private val staggerMs: Long = 250,
    private val timeoutMs: Long = 10_000,
    private val connect: suspend (InetAddress, Int) -> SocketChannel,
) {
    suspend fun dial(addresses: List<InetAddress>, port: Int): SocketChannel =
        withTimeout(timeoutMs) { race(order(addresses), port) }

    private suspend fun race(addresses: List<InetAddress>, port: Int): SocketChannel = coroutineScope {
        require(addresses.isNotEmpty()) { "no addresses to dial" }
        val winner = CompletableDeferred<SocketChannel>()
        val failures = AtomicInteger()
        val attempts = addresses.mapIndexed { i, address ->
            launch {
                delay(i * staggerMs)
                val ch = try {
                    connect(address, port)
                } catch (e: IOException) {
                    if (failures.incrementAndGet() == addresses.size) winner.completeExceptionally(e)
                    return@launch
                }
                if (!winner.complete(ch)) ch.close()
            }
        }
        try {
            winner.await()
        } finally {
            attempts.forEach { it.cancel() }
        }
    }

    companion object {
        fun order(addresses: List<InetAddress>): List<InetAddress> {
            val v6 = addresses.filterIsInstance<Inet6Address>()
            val v4 = addresses.filterIsInstance<Inet4Address>()
            val out = ArrayList<InetAddress>(addresses.size)
            for (i in 0 until maxOf(v6.size, v4.size)) {
                v6.getOrNull(i)?.let(out::add)
                v4.getOrNull(i)?.let(out::add)
            }
            return out
        }

        fun forEgress(loop: NioLoop, egress: Egress, staggerMs: Long = 250, timeoutMs: Long = 10_000) =
            Dialer(staggerMs, timeoutMs) { address, port ->
                connectChannel(loop, egress.newChannel(), InetSocketAddress(address, port))
            }
    }
}

/** Non-blocking connect of an already-bound channel; closes the channel on any failure. */
suspend fun connectChannel(loop: NioLoop, ch: SocketChannel, target: InetSocketAddress): SocketChannel {
    try {
        ch.configureBlocking(false)
        if (!ch.connect(target)) {
            while (!ch.finishConnect()) loop.await(ch, SelectionKey.OP_CONNECT)
        }
        ch.socket().tcpNoDelay = true
        return ch
    } catch (t: Throwable) {
        ch.close()
        throw t
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.egress.*"`
Expected: `BUILD SUCCESSFUL`, 7 tests passed.

- [ ] **Step 6: Commit**

```powershell
git add proxy-core/src
git commit -m "feat(core): egress abstraction and happy-eyeballs dialer"
```

---

### Task 9: Proxy config and local content (PAC/mobileconfig endpoints)

**Files:**
- Create: `proxy-core/src/main/kotlin/com/wifiproxy/core/ProxyConfig.kt`
- Create: `proxy-core/src/main/kotlin/com/wifiproxy/core/content/LocalContent.kt`
- Test: `proxy-core/src/test/kotlin/com/wifiproxy/core/content/LocalContentTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.wifiproxy.core.content

import com.wifiproxy.core.ProxyConfig
import com.wifiproxy.core.WifiCredentials
import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.http.ProxyRequest.Local
import com.wifiproxy.core.net.Cidr
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalContentTest {
    private val registry = ClientRegistry(maxClients = 3)
    private val peer = InetAddress.getByName("192.168.49.10")
    private fun content(wifi: WifiCredentials? = null) = LocalContent(registry, "192.168.49.1", 8080, wifi)

    @Test
    fun `pac for a valid token admits the peer`() {
        val slot = registry.addSlot("a")!!
        val r = content().serve(Local("GET", "/${slot.token}.pac"), peer)
        assertEquals(200, r.status)
        assertEquals(PacFile.CONTENT_TYPE, r.contentType)
        assertTrue("PROXY 192.168.49.1:8080" in r.body.toString(Charsets.UTF_8))
        assertEquals(slot.id, registry.admittedSlot(peer)?.id)
    }

    @Test
    fun `unknown token is 404 and admits nobody`() {
        registry.addSlot("a")
        assertEquals(404, content().serve(Local("GET", "/AAAAAAAAAAAAAAAAAAAAAA.pac"), peer).status)
        assertNull(registry.admittedSlot(peer))
    }

    @Test
    fun `non-token paths are 404`() {
        listOf("/", "/favicon.ico", "/short.pac").forEach {
            assertEquals(404, content().serve(Local("GET", it), peer).status, it)
        }
    }

    @Test
    fun `non-GET is 405`() {
        val slot = registry.addSlot("a")!!
        assertEquals(405, content().serve(Local("POST", "/${slot.token}.pac"), peer).status)
        assertNull(registry.admittedSlot(peer))
    }

    @Test
    fun `mobileconfig requires wifi credentials`() {
        val slot = registry.addSlot("a")!!
        assertEquals(404, content(wifi = null).serve(Local("GET", "/${slot.token}.mobileconfig"), peer).status)
        assertNull(registry.admittedSlot(peer))

        val r = content(WifiCredentials("DIRECT-ab-Test", "password1")).serve(Local("GET", "/${slot.token}.mobileconfig"), peer)
        assertEquals(200, r.status)
        assertEquals(MobileConfig.CONTENT_TYPE, r.contentType)
        assertTrue("http://192.168.49.1:8080/${slot.token}.pac" in r.body.toString(Charsets.UTF_8))
        assertEquals(slot.id, registry.admittedSlot(peer)?.id)
    }

    @Test
    fun `wifi credentials never print the password`() {
        assertFalse("password1" in WifiCredentials("s", "password1").toString())
    }

    @Test
    fun `config refuses a wildcard bind address`() {
        assertFailsWith<IllegalArgumentException> {
            ProxyConfig(bindAddress = InetAddress.getByName("0.0.0.0"), peerSubnet = Cidr.parse("192.168.49.0/24"))
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyConfig(bindAddress = InetAddress.getByName("::"), peerSubnet = Cidr.parse("192.168.49.0/24"))
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.content.LocalContentTest"`
Expected: FAIL, compilation error `Unresolved reference 'LocalContent'`.

- [ ] **Step 3: Implement `ProxyConfig.kt`**

```kotlin
package com.wifiproxy.core

import com.wifiproxy.core.net.Cidr
import java.net.InetAddress

data class WifiCredentials(val ssid: String, val password: String) {
    override fun toString() = "WifiCredentials(ssid=$ssid, password=****)"
}

data class ProxyConfig(
    /** The Wi-Fi Direct group-owner address. Never a wildcard: cellular IPv6 is often globally routable. */
    val bindAddress: InetAddress,
    /** 0 picks an ephemeral port (tests). */
    val port: Int = 8080,
    /** Only peers inside this subnet are served. */
    val peerSubnet: Cidr,
    /** Enables /<token>.mobileconfig when set. */
    val wifi: WifiCredentials? = null,
    val idleTimeoutMs: Long = 5 * 60_000L,
    val pushIdleTimeoutMs: Long = 30 * 60_000L,
    val headTimeoutMs: Long = 10_000L,
    val connectTimeoutMs: Long = 10_000L,
) {
    init {
        require(!bindAddress.isAnyLocalAddress) { "refusing wildcard bind address $bindAddress" }
        require(port in 0..65535) { "port out of range: $port" }
    }
}
```

- [ ] **Step 4: Implement `LocalContent.kt`**

```kotlin
package com.wifiproxy.core.content

import com.wifiproxy.core.WifiCredentials
import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.http.HttpResponse
import com.wifiproxy.core.http.ProxyRequest
import java.net.InetAddress

/** Serves /<token>.pac and /<token>.mobileconfig. A successful fetch binds the slot to the peer IP. */
class LocalContent(
    private val registry: ClientRegistry,
    private val proxyHost: String,
    private val proxyPort: Int,
    private val wifi: WifiCredentials?,
) {
    fun serve(request: ProxyRequest.Local, peer: InetAddress): HttpResponse {
        val match = PATH.matchEntire(request.path) ?: return HttpResponse.NOT_FOUND
        if (request.method != "GET") return HttpResponse.METHOD_NOT_ALLOWED
        val (token, extension) = match.destructured
        if (extension == "mobileconfig" && wifi == null) return HttpResponse.NOT_FOUND
        registry.onPacFetch(token, peer) ?: return HttpResponse.NOT_FOUND

        val pac = PacFile.render(proxyHost, proxyPort)
        return when (extension) {
            "pac" -> HttpResponse(200, "OK", PacFile.CONTENT_TYPE, pac.toByteArray(Charsets.UTF_8))
            else -> {
                val pacUrl = "http://$proxyHost:$proxyPort/$token.pac"
                val profile = MobileConfig.render(wifi!!.ssid, wifi.password, pacUrl, token)
                HttpResponse(200, "OK", MobileConfig.CONTENT_TYPE, profile.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private companion object {
        val PATH = Regex("^/([A-Za-z0-9_-]{22})\\.(pac|mobileconfig)$")
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.content.LocalContentTest"`
Expected: `BUILD SUCCESSFUL`, 7 tests passed.

- [ ] **Step 6: Commit**

```powershell
git add proxy-core/src
git commit -m "feat(core): proxy config and token-gated PAC/mobileconfig endpoints"
```

---

### Task 10: ProxyServer (accept, route, admit, tunnel, revoke)

**Files:**
- Create: `proxy-core/src/main/kotlin/com/wifiproxy/core/ProxyServer.kt`
- Test: `proxy-core/src/test/kotlin/com/wifiproxy/core/ProxyServerTest.kt`

- [ ] **Step 1: Write the failing integration tests**

```kotlin
package com.wifiproxy.core

import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.clients.Slot
import com.wifiproxy.core.egress.DirectEgress
import com.wifiproxy.core.egress.Egress
import com.wifiproxy.core.net.Cidr
import com.wifiproxy.core.net.DestinationFilter
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ProxyServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private var registry = ClientRegistry(maxClients = 3)
    private lateinit var server: ProxyServer
    private val closeables = mutableListOf<AutoCloseable>()

    private fun start(
        egress: Egress = DirectEgress(),
        filter: DestinationFilter = DestinationFilter(emptyList()),
        idleMs: Long = 60_000,
        wifi: WifiCredentials? = null,
        peerSubnet: Cidr = Cidr.parse("127.0.0.0/8"),
    ) {
        val config = ProxyConfig(bindAddress = loopback, port = 0, peerSubnet = peerSubnet, wifi = wifi, idleTimeoutMs = idleMs)
        server = ProxyServer(config, registry, egress, filter).also { it.start() }
    }

    @AfterTest
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        if (::server.isInitialized) server.stop()
    }

    private fun rawRequest(text: String): String = Socket(loopback, server.localPort).use { s ->
        s.soTimeout = 5000
        s.getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1))
        s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
    }

    private fun admit(): Slot {
        val slot = registry.addSlot("test")!!
        val response = rawRequest("GET /${slot.token}.pac HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(response.startsWith("HTTP/1.1 200"), response)
        return slot
    }

    private fun startEcho(): ServerSocket {
        val ss = ServerSocket(0, 50, loopback)
        closeables += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) { s.use { runCatching { it.getInputStream().copyTo(it.getOutputStream()) } } }
            }
        }
        return ss
    }

    private fun readHeadBlocking(input: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun openConnect(port: Int): Pair<Socket, String> {
        val s = Socket(loopback, server.localPort)
        closeables += s
        s.soTimeout = 5000
        s.getOutputStream().write("CONNECT 127.0.0.1:$port HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray())
        return s to readHeadBlocking(s.getInputStream())
    }

    private fun assertClosed(s: Socket) {
        val r = try { s.getInputStream().read() } catch (e: SocketException) { -1 }
        assertEquals(-1, r)
    }

    private fun eventually(check: () -> Boolean) {
        repeat(100) {
            if (check()) return
            Thread.sleep(20)
        }
        fail("condition not met within 2s")
    }

    private val failingDns = object : Egress by DirectEgress() {
        override suspend fun resolve(host: String): List<InetAddress> = throw UnknownHostException(host)
    }

    @Test
    fun `pac fetch serves the pac and admits the client`() {
        start()
        val slot = registry.addSlot("test")!!
        val response = rawRequest("GET /${slot.token}.pac HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(response.startsWith("HTTP/1.1 200"), response)
        assertTrue("PROXY 127.0.0.1:${server.localPort}" in response, response)
        assertEquals(slot.id, registry.admittedSlot(loopback)?.id)
    }

    @Test
    fun `mobileconfig is served when wifi is configured`() {
        start(wifi = WifiCredentials("DIRECT-ab-Test", "password123"))
        val slot = registry.addSlot("iphone")!!
        val response = rawRequest("GET /${slot.token}.mobileconfig HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue("application/x-apple-aspen-config" in response, response)
        assertTrue("DIRECT-ab-Test" in response, response)
    }

    @Test
    fun `malformed request is 400 and unknown path is 404`() {
        start()
        assertTrue(rawRequest("hello\r\n\r\n").startsWith("HTTP/1.1 400"))
        assertTrue(rawRequest("GET /nope HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 404"))
    }

    @Test
    fun `peer outside the subnet is dropped without a response`() {
        start(peerSubnet = Cidr.parse("10.0.0.0/8"))
        // Closing with unread input sends RST on Windows, so a reset counts as "dropped" too.
        val response = try { rawRequest("GET /x HTTP/1.1\r\n\r\n") } catch (e: SocketException) { "" }
        assertEquals("", response)
    }

    @Test
    fun `unadmitted client gets 403`() {
        start()
        assertTrue(rawRequest("CONNECT example.com:443 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
    }

    @Test
    fun `connect tunnels raw bytes and counts them`() {
        start()
        val slot = admit()
        val echo = startEcho()
        val (s, head) = openConnect(echo.localPort)
        assertTrue(head.startsWith("HTTP/1.1 200 Connection Established"), head)
        s.getOutputStream().write("ping".toByteArray())
        assertEquals("ping", String(s.getInputStream().readNBytes(4)))
        eventually {
            val current = registry.slots().first { it.id == slot.id }
            current.bytesUp >= 4 && current.bytesDown >= 4
        }
    }

    @Test
    fun `plain http is forwarded with hop-by-hop headers stripped`() {
        start()
        admit()
        val origin = MockWebServer()
        closeables += origin
        origin.enqueue(MockResponse().setBody("hello"))
        origin.start(loopback, 0)
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(loopback, server.localPort)))
            .retryOnConnectionFailure(false)
            .build()
        val body = client.newCall(Request.Builder().url(origin.url("/x")).build()).execute().use { it.body!!.string() }
        assertEquals("hello", body)
        val recorded = origin.takeRequest()
        assertEquals("/x", recorded.path)
        assertEquals("close", recorded.getHeader("Connection"))
    }

    @Test
    fun `blocked destination gets 403`() {
        start(filter = DestinationFilter.default(Cidr.parse("192.168.49.0/24")))
        admit()
        assertTrue(rawRequest("CONNECT 127.0.0.1:1 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
    }

    @Test
    fun `no egress gets 503`() {
        start(egress = DirectEgress(isAvailable = false))
        admit()
        assertTrue(rawRequest("CONNECT example.com:443 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 503"))
    }

    @Test
    fun `unresolvable host gets 502`() {
        start(egress = failingDns)
        admit()
        assertTrue(rawRequest("CONNECT example.com:443 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 502"))
    }

    @Test
    fun `probe is answered locally only while egress is validated`() {
        val probe = "GET http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\n\r\n"
        start(egress = failingDns)
        admit()
        assertTrue(rawRequest(probe).startsWith("HTTP/1.1 204"))
        server.stop()

        val unvalidated = object : Egress by DirectEgress(isValidated = false) {
            override suspend fun resolve(host: String): List<InetAddress> = throw UnknownHostException(host)
        }
        start(egress = unvalidated)
        assertTrue(rawRequest(probe).startsWith("HTTP/1.1 502"))
    }

    @Test
    fun `tunnel cap per slot returns 503`() {
        registry = ClientRegistry(maxClients = 3, tunnelCapPerSlot = 1)
        start()
        admit()
        val echo = startEcho()
        val (_, first) = openConnect(echo.localPort)
        assertTrue(first.startsWith("HTTP/1.1 200"), first)
        assertTrue(rawRequest("CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 503"))
    }

    @Test
    fun `revoke closes live tunnels and blocks the client`() {
        start()
        val slot = admit()
        val echo = startEcho()
        val (s, head) = openConnect(echo.localPort)
        assertTrue(head.startsWith("HTTP/1.1 200"), head)
        assertTrue(server.revoke(slot.id))
        assertClosed(s)
        assertTrue(rawRequest("CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
    }

    @Test
    fun `idle tunnel is closed`() {
        start(idleMs = 300)
        admit()
        val echo = startEcho()
        val (s, head) = openConnect(echo.localPort)
        assertTrue(head.startsWith("HTTP/1.1 200"), head)
        assertClosed(s)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.ProxyServerTest"`
Expected: FAIL, compilation error `Unresolved reference 'ProxyServer'`.

- [ ] **Step 3: Implement `ProxyServer.kt`**

```kotlin
package com.wifiproxy.core

import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.content.LocalContent
import com.wifiproxy.core.content.Probes
import com.wifiproxy.core.egress.Dialer
import com.wifiproxy.core.egress.Egress
import com.wifiproxy.core.http.HttpResponse
import com.wifiproxy.core.http.ProxyRequest
import com.wifiproxy.core.http.RequestParser
import com.wifiproxy.core.io.NioLoop
import com.wifiproxy.core.io.Tunnel
import com.wifiproxy.core.io.readHead
import com.wifiproxy.core.io.writeFully
import com.wifiproxy.core.net.Cidr
import com.wifiproxy.core.net.DestinationFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * HTTP proxy bound to the group-owner address. One request head per connection decides:
 * local content, connectivity probe, CONNECT tunnel, or a single plain-HTTP forward.
 */
class ProxyServer(
    private val config: ProxyConfig,
    private val registry: ClientRegistry,
    private val egress: Egress,
    private val filter: DestinationFilter = DestinationFilter.default(config.peerSubnet),
    private val onError: (Throwable) -> Unit = {},
) {
    private class ProxyFailure(val response: HttpResponse) : Exception()

    private val tunnels = ConcurrentHashMap<String, MutableSet<Job>>()
    private var scope: CoroutineScope? = null
    private var server: ServerSocketChannel? = null
    private var loop: NioLoop? = null
    private lateinit var dialer: Dialer
    private lateinit var local: LocalContent

    val localPort: Int
        get() = (checkNotNull(server) { "not started" }.localAddress as InetSocketAddress).port

    @Synchronized
    fun start() {
        check(server == null) { "already started" }
        val nio = NioLoop()
        val ch = ServerSocketChannel.open()
        try {
            ch.bind(InetSocketAddress(config.bindAddress, config.port))
            ch.configureBlocking(false)
        } catch (e: IOException) {
            ch.close()
            nio.close()
            throw e
        }
        loop = nio
        server = ch
        dialer = Dialer.forEgress(nio, egress, timeoutMs = config.connectTimeoutMs)
        local = LocalContent(registry, config.bindAddress.hostAddress, localPort, config.wifi)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> onError(e) })
        scope = s
        s.launch { acceptLoop(s, nio, ch) }
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        server?.close()
        server = null
        loop?.close()
        loop = null
        tunnels.clear()
    }

    /** Revokes the slot and closes every tunnel it owns. */
    fun revoke(slotId: String): Boolean {
        val removed = registry.revoke(slotId)
        tunnels.remove(slotId)?.forEach { it.cancel() }
        return removed
    }

    private suspend fun acceptLoop(scope: CoroutineScope, nio: NioLoop, ch: ServerSocketChannel) {
        try {
            while (currentCoroutineContext().isActive) {
                val client = ch.accept()
                if (client == null) {
                    nio.await(ch, SelectionKey.OP_ACCEPT)
                    continue
                }
                scope.launch { handle(nio, client) }
            }
        } catch (e: ClosedChannelException) {
            // stopped
        }
    }

    private suspend fun handle(nio: NioLoop, client: SocketChannel) {
        try {
            client.configureBlocking(false)
            val peer = (client.remoteAddress as InetSocketAddress).address
            if (!config.peerSubnet.contains(peer)) return
            val head = withTimeoutOrNull(config.headTimeoutMs) { readHead(nio, client) } ?: return
            when (val request = RequestParser.parse(head.text)) {
                is ProxyRequest.Malformed -> respond(nio, client, HttpResponse.BAD_REQUEST)
                is ProxyRequest.Local -> respond(nio, client, local.serve(request, peer))
                is ProxyRequest.Connect ->
                    proxy(nio, client, peer, request.host, request.port, head.leftover, forward = null)
                is ProxyRequest.Forward ->
                    proxy(nio, client, peer, request.host, request.port, head.leftover, forward = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun proxy(
        nio: NioLoop,
        client: SocketChannel,
        peer: InetAddress,
        host: String,
        port: Int,
        leftover: ByteArray,
        forward: ProxyRequest.Forward?,
    ) {
        val slot = registry.admittedSlot(peer) ?: return respond(nio, client, HttpResponse.FORBIDDEN)
        if (forward != null && egress.isValidated) {
            Probes.match(forward)?.let { return respond(nio, client, it) }
        }
        if (!registry.tryAcquireTunnel(slot.id)) return respond(nio, client, HttpResponse.SERVICE_UNAVAILABLE)

        val job = currentCoroutineContext().job
        tunnels.computeIfAbsent(slot.id) { ConcurrentHashMap.newKeySet() }.add(job)
        var upstream: SocketChannel? = null
        try {
            // A revoke that raced with the admission check above must still win.
            if (registry.admittedSlot(peer)?.id != slot.id) return respond(nio, client, HttpResponse.FORBIDDEN)
            val up = try {
                open(host, port)
            } catch (e: ProxyFailure) {
                return respond(nio, client, e.response)
            }
            upstream = up
            if (forward == null) {
                writeFully(nio, client, HttpResponse.CONNECTION_ESTABLISHED)
            } else {
                writeFully(nio, up, forward.upstreamHead())
            }
            if (leftover.isNotEmpty()) {
                writeFully(nio, up, leftover)
                registry.addBytes(slot.id, leftover.size.toLong(), 0)
            }
            Tunnel(
                nio, client, up, idleTimeoutFor(up, port),
                onUp = { registry.addBytes(slot.id, it.toLong(), 0) },
                onDown = { registry.addBytes(slot.id, 0, it.toLong()) },
            ).run(closeWhenResponseEnds = forward != null)
        } finally {
            registry.releaseTunnel(slot.id)
            tunnels[slot.id]?.remove(job)
            runCatching { upstream?.close() }
        }
    }

    private suspend fun open(host: String, port: Int): SocketChannel {
        if (!egress.isAvailable) throw ProxyFailure(HttpResponse.SERVICE_UNAVAILABLE)
        val resolved = try {
            egress.resolve(host)
        } catch (e: IOException) {
            throw ProxyFailure(HttpResponse.BAD_GATEWAY)
        }
        if (resolved.isEmpty()) throw ProxyFailure(HttpResponse.BAD_GATEWAY)
        val allowed = resolved.filter(filter::isAllowed)
        if (allowed.isEmpty()) throw ProxyFailure(HttpResponse.FORBIDDEN)
        return try {
            dialer.dial(allowed, port)
        } catch (e: TimeoutCancellationException) {
            throw ProxyFailure(HttpResponse.GATEWAY_TIMEOUT)
        } catch (e: IOException) {
            throw ProxyFailure(HttpResponse.BAD_GATEWAY)
        }
    }

    private fun idleTimeoutFor(upstream: SocketChannel, port: Int): Long {
        val address = (upstream.remoteAddress as InetSocketAddress).address
        return if (port == APPLE_PUSH_PORT || APPLE_PUSH_RANGE.contains(address)) config.pushIdleTimeoutMs else config.idleTimeoutMs
    }

    private suspend fun respond(nio: NioLoop, client: SocketChannel, response: HttpResponse) {
        writeFully(nio, client, response.toBytes())
        runCatching { client.shutdownOutput() }
    }

    private companion object {
        const val APPLE_PUSH_PORT = 5223
        val APPLE_PUSH_RANGE = Cidr.parse("17.0.0.0/8")
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat :proxy-core:test --tests "com.wifiproxy.core.ProxyServerTest"`
Expected: `BUILD SUCCESSFUL`, 14 tests passed.

If `plain http is forwarded…` hangs until the OkHttp read timeout, check that `Tunnel.run(closeWhenResponseEnds = true)` is used for forwards. The client side must see EOF once the origin closes.

- [ ] **Step 5: Commit**

```powershell
git add proxy-core/src
git commit -m "feat(core): proxy server with admission, tunnels, probes and revoke"
```

---

### Task 11: README, spec alignment, full verification

**Files:**
- Create: `README.md`
- Modify: `docs/superpowers/specs/2026-11-19-android-wifi-proxy-design.md` (§4 and §5, exact edits below)

- [ ] **Step 1: Create `README.md`**

````markdown
# Android Wi-Fi Proxy

Shares a host Android phone's cellular data with up to 3 phones over a Wi-Fi Direct group, using an
app-level HTTP proxy (no system tethering). Design: `docs/superpowers/specs/2026-11-19-android-wifi-proxy-design.md`.

## Modules

| Module | Status | What |
|---|---|---|
| `:proxy-core` | Plan 1 | Pure Kotlin/JVM proxy data plane: slots/admission, parser, PAC/mobileconfig/probes, destination filter, dialer, NIO tunnel server |
| `:app` | Plan 2–3 | Android host app (service, Wi-Fi Direct, cellular egress, UI) |

## Build and test (Windows)

```powershell
. .\scripts\env.ps1          # puts JDK 21 on JAVA_HOME for this session
.\gradlew.bat :proxy-core:test
```

## `:proxy-core` rules

- Keep it Android-compatible (API 29). Use only `java.nio`, `java.net`, `java.util`, `java.security` and kotlinx-coroutines.
- No `java.net.http`, no `java.nio.file`, no JDK 11+ library APIs.
- `ProxyServer` refuses wildcard bind addresses. Always bind to the group-owner IP.
- `DirectEgress` is for tests only. On Android, upstream sockets and DNS must go through the cellular `Egress` (Plan 2).
````

- [ ] **Step 2: Align the spec with two implementation decisions**

In `docs/superpowers/specs/2026-11-19-android-wifi-proxy-design.md`, replace:

```
8. Bidirectional pump with pooled 64 KB direct buffers, `TCP_NODELAY`, kernel-default socket buffers, backpressure. Byte counts feed per-slot counters.
```

with:

```
8. Bidirectional pump with one 32 KB heap buffer per direction (no pool; revisit if profiling shows GC pressure), `TCP_NODELAY`, kernel-default socket buffers, backpressure. Byte counts feed per-slot counters. Plain-HTTP forwards carry exactly one request per upstream connection (`Connection: close`); the tunnel closes when the response ends.
```

Then replace:

```
- Lowering `maxClients` below active slot count prompts the user to choose which slot(s) to revoke.
```

with:

```
- Lowering `maxClients` below the active slot count is refused by `ClientRegistry.setMaxClients`; the UI first asks the user which slot(s) to revoke, then retries.
- Slot records (id, name, token, createdAt) are persisted by the Android layer via `ClientRegistry.snapshot()` and passed back on restart; IP bindings are not persisted (clients re-fetch the PAC on reconnect).
```

- [ ] **Step 3: Run the full test suite**

Run: `.\gradlew.bat :proxy-core:test`
Expected: `BUILD SUCCESSFUL`. Total 79 tests: Cidr 5, DestinationFilter 5, ClientRegistry 12, RequestParser 10, Content 6, NioLoop 4, ChannelIo 6, Tunnel 3, Dialer 7, LocalContent 7, ProxyServer 14.

- [ ] **Step 4: Commit**

```powershell
git add README.md docs/superpowers/specs/2026-11-19-android-wifi-proxy-design.md
git commit -m "docs: README and spec alignment for proxy-core"
```
