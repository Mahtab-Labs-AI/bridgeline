package com.wifiproxy.app.egress

import com.wifiproxy.core.egress.EgressUnavailableException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A fake network handle: a plain value with identity equality, standing in for `android.net.Network`. */
private data class FakeNetwork(val id: Int)

/**
 * A [DnsQuery] fake. With [onQuery] set, [query] answers synchronously; left `null`, it never
 * answers (simulating an in-flight query) until the test cancels or times it out, letting tests
 * observe the returned cancel hook.
 */
private class FakeDnsQuery(
    private val onQuery: ((FakeNetwork, String) -> DnsOutcome)? = null,
) : DnsQuery<FakeNetwork> {
    var queryCalls = 0
        private set
    var cancelled = false
        private set

    override fun query(network: FakeNetwork, host: String, onResult: (DnsOutcome) -> Unit): () -> Unit {
        queryCalls++
        onQuery?.let { onResult(it(network, host)) }
        return { cancelled = true }
    }
}

class DnsResolutionEngineTest {

    @Test
    fun `IP literal short-circuits without a query or a network`() = runTest {
        val fakeQuery = FakeDnsQuery()
        val engine = DnsResolutionEngine(query = fakeQuery)

        val ipv4 = engine.resolve(network = null, host = "127.0.0.1")
        val ipv6 = engine.resolve(network = null, host = "::1")

        assertEquals(listOf(InetAddress.getByName("127.0.0.1")), ipv4)
        assertEquals(listOf(InetAddress.getByName("::1")), ipv6)
        assertEquals(0, fakeQuery.queryCalls)
    }

    @Test
    fun `no network throws EgressUnavailableException for a real hostname`() = runTest {
        val fakeQuery = FakeDnsQuery()
        val engine = DnsResolutionEngine(query = fakeQuery)

        assertFailsWith<EgressUnavailableException> {
            engine.resolve(network = null, host = "example.com")
        }
        assertEquals(0, fakeQuery.queryCalls)
    }

    @Test
    fun `empty answer throws UnknownHostException`() = runTest {
        val fakeQuery = FakeDnsQuery(onQuery = { _, _ -> DnsOutcome.Answer(emptyList()) })
        val engine = DnsResolutionEngine(query = fakeQuery)

        assertFailsWith<UnknownHostException> {
            engine.resolve(FakeNetwork(1), "example.com")
        }
    }

    @Test
    fun `a non-IOException query failure is wrapped as an IOException`() = runTest {
        val fakeQuery = FakeDnsQuery(onQuery = { _, _ -> DnsOutcome.Failure(RuntimeException("boom")) })
        val engine = DnsResolutionEngine(query = fakeQuery)

        assertFailsWith<IOException> {
            engine.resolve(FakeNetwork(1), "example.com")
        }
    }

    @Test
    fun `timeout throws SocketTimeoutException and cancels the query`() = runTest {
        val fakeQuery = FakeDnsQuery() // never answers
        val engine = DnsResolutionEngine(query = fakeQuery)

        assertFailsWith<SocketTimeoutException> {
            engine.resolve(FakeNetwork(1), "example.com")
        }
        assertTrue(fakeQuery.cancelled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancellation propagates to the query's cancel hook`() = runTest {
        val fakeQuery = FakeDnsQuery() // never answers
        val engine = DnsResolutionEngine(query = fakeQuery)

        val job = launch { engine.resolve(FakeNetwork(1), "example.com") }
        runCurrent() // let the coroutine reach the query call and suspend, without advancing past the timeout
        assertEquals(1, fakeQuery.queryCalls)
        assertFalse(fakeQuery.cancelled)

        job.cancel()
        job.join()

        assertTrue(fakeQuery.cancelled)
    }

    @Test
    fun `cache hit avoids a second query`() = runTest {
        val answer = listOf(InetAddress.getByName("93.184.216.34"))
        val fakeQuery = FakeDnsQuery(onQuery = { _, _ -> DnsOutcome.Answer(answer) })
        val engine = DnsResolutionEngine(query = fakeQuery)
        val network = FakeNetwork(1)

        val first = engine.resolve(network, "example.com")
        val second = engine.resolve(network, "example.com")

        assertEquals(answer, first)
        assertEquals(answer, second)
        assertEquals(1, fakeQuery.queryCalls)
    }

    @Test
    fun `cache entry expires after its TTL`() = runTest {
        var now = 0L
        val answer = listOf(InetAddress.getByName("93.184.216.34"))
        val fakeQuery = FakeDnsQuery(onQuery = { _, _ -> DnsOutcome.Answer(answer) })
        val engine = DnsResolutionEngine(query = fakeQuery, clock = { now }, ttlMillis = 100)
        val network = FakeNetwork(1)

        engine.resolve(network, "example.com")
        now += 150
        engine.resolve(network, "example.com")

        assertEquals(2, fakeQuery.queryCalls)
    }

    @Test
    fun `cache is keyed by network as well as host`() = runTest {
        val answer = listOf(InetAddress.getByName("93.184.216.34"))
        val fakeQuery = FakeDnsQuery(onQuery = { _, _ -> DnsOutcome.Answer(answer) })
        val engine = DnsResolutionEngine(query = fakeQuery)

        engine.resolve(FakeNetwork(1), "example.com")
        engine.resolve(FakeNetwork(2), "example.com")

        assertEquals(2, fakeQuery.queryCalls)
    }
}
