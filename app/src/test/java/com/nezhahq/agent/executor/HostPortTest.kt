package com.nezhahq.agent.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the parser that both `NatManager.parseHostPort` and TCP Ping now delegate to.
 *
 * Two behaviours are worth stating explicitly, because they are the reason the parser is shared:
 * the *only* intended difference between the two callers is [HostPort.parse]'s `defaultPort`, and an
 * unbracketed IPv6 literal is rejected rather than guessed at.
 */
class HostPortTest {

    // ── host:port ─────────────────────────────────────────────────────────────

    @Test
    fun `parses host and port`() {
        assertParsed("example.com", 80, HostPort.parse("example.com:80"))
        assertParsed("192.0.2.1", 8443, HostPort.parse("192.0.2.1:8443"))
    }

    @Test
    fun `accepts the port range boundaries`() {
        assertParsed("h", 1, HostPort.parse("h:1"))
        assertParsed("h", 65535, HostPort.parse("h:65535"))
    }

    // ── IPv6 ──────────────────────────────────────────────────────────────────

    @Test
    fun `strips brackets from an IPv6 literal`() {
        assertParsed("::1", 8080, HostPort.parse("[::1]:8080"))
        assertParsed("2001:db8::1", 443, HostPort.parse("[2001:db8::1]:443"))
    }

    @Test
    fun `bracketed IPv6 without a port takes the default`() {
        assertParsed("::1", 80, HostPort.parse("[::1]", defaultPort = 80))
        assertInvalid(HostPort.parse("[::1]"))
    }

    @Test
    fun `unbracketed IPv6 is rejected instead of guessed`() {
        // "2001:db8::1" could be an address, or an address plus port ":1" — there is no way to tell,
        // and the old lastIndexOf(':') split silently produced host="2001:db8:" port=1.
        assertInvalid(HostPort.parse("2001:db8::1", defaultPort = 80))
        assertInvalid(HostPort.parse("::1", defaultPort = 80))
    }

    @Test
    fun `malformed brackets are rejected`() {
        assertInvalid(HostPort.parse("[::1:8080", defaultPort = 80))
        assertInvalid(HostPort.parse("[]:8080", defaultPort = 80))
        assertInvalid(HostPort.parse("[", defaultPort = 80))
        assertInvalid(HostPort.parse("[::1]junk", defaultPort = 80))
    }

    // ── 缺省端口 ───────────────────────────────────────────────────────────────

    @Test
    fun `bare host takes the default port when one is offered`() {
        assertParsed("example.com", 80, HostPort.parse("example.com", defaultPort = 80))
    }

    @Test
    fun `bare host is an error when no default is offered`() {
        // NAT forwarding has no sensible guess for "which port", so it passes no default.
        assertInvalid(HostPort.parse("example.com"))
    }

    // ── 非法端口 ───────────────────────────────────────────────────────────────

    @Test
    fun `non numeric port is rejected with a diagnostic`() {
        val result = HostPort.parse("example.com:http", defaultPort = 80)
        assertTrue(reasonOf(result).contains("http"))
    }

    @Test
    fun `out of range ports are rejected rather than silently used`() {
        assertInvalid(HostPort.parse("example.com:0", defaultPort = 80))
        assertInvalid(HostPort.parse("example.com:65536", defaultPort = 80))
        assertInvalid(HostPort.parse("example.com:-1", defaultPort = 80))
        // Wider than Int: toIntOrNull returns null, so it must not wrap around into a valid port.
        assertInvalid(HostPort.parse("example.com:4294967376", defaultPort = 80))
    }

    // ── 畸形输入 ───────────────────────────────────────────────────────────────

    @Test
    fun `empty and colon only inputs are rejected`() {
        assertInvalid(HostPort.parse("", defaultPort = 80))
        assertInvalid(HostPort.parse(":", defaultPort = 80))
        assertInvalid(HostPort.parse(":80", defaultPort = 80))
    }

    @Test
    fun `trailing colon is rejected instead of falling back to the default port`() {
        // "host:" is a truncated address, not "host with the port omitted": the dashboard meant to
        // send a port and lost it, and quietly probing port 80 would hide that.
        assertInvalid(HostPort.parse("example.com:", defaultPort = 80))
    }

    // ── 结构化来源 ─────────────────────────────────────────────────────────────

    @Test
    fun `of validates an already split host and port`() {
        assertParsed("example.com", 443, HostPort.of("example.com", 443))
        assertInvalid(HostPort.of("", 443))
        assertInvalid(HostPort.of("   ", 443))
        assertInvalid(HostPort.of("example.com", 0))
        assertInvalid(HostPort.of("example.com", 70000))
    }

    private fun assertParsed(host: String, port: Int, result: HostPort.Result) {
        assertEquals(HostPort.Result.Parsed(host, port), result)
    }

    private fun assertInvalid(result: HostPort.Result) {
        assertTrue("expected Invalid but was $result", result is HostPort.Result.Invalid)
        assertTrue("diagnostic must not be empty", reasonOf(result).isNotBlank())
    }

    private fun reasonOf(result: HostPort.Result): String =
        (result as? HostPort.Result.Invalid)?.reason ?: throw AssertionError("expected Invalid but was $result")
}
