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

    @Test
    fun `clearBindings drops every admission but keeps the slots`() {
        val r = registry()
        val a = r.addSlot("a")!!
        val b = r.addSlot("b")!!
        r.onPacFetch(a.token, ip("192.168.49.10"))
        r.onPacFetch(b.token, ip("192.168.49.20"))
        r.clearBindings()
        assertNull(r.admittedSlot(ip("192.168.49.10")))
        assertNull(r.admittedSlot(ip("192.168.49.20")))
        assertEquals(2, r.slots().size)
        assertEquals(a.id, r.onPacFetch(a.token, ip("192.168.49.10"))?.id)
    }

    @Test
    fun `unbind clears only that ip`() {
        val r = registry()
        val a = r.addSlot("a")!!
        val b = r.addSlot("b")!!
        r.onPacFetch(a.token, ip("192.168.49.10"))
        r.onPacFetch(b.token, ip("192.168.49.20"))
        r.unbind(ip("192.168.49.10"))
        assertNull(r.admittedSlot(ip("192.168.49.10")))
        assertEquals(b.id, r.admittedSlot(ip("192.168.49.20"))?.id)
    }

    @Test
    fun `toString never contains the token`() {
        val r = registry()
        val slot = r.addSlot("a")!!
        assertFalse(slot.toString().contains(slot.token), "Slot.toString() contains token: ${slot.toString()}")
        val record = r.snapshot().single()
        assertFalse(record.toString().contains(record.token), "SlotRecord.toString() contains token: ${record.toString()}")
    }
}
