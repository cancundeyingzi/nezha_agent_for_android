package com.nezhahq.agent.service

import java.util.concurrent.ThreadLocalRandom

/**
 * Picks the actual wait inside a backoff ceiling.
 *
 * Injectable so a test can pin the draw: a jitter assertion against a real random source is either
 * flaky or so loose it proves nothing.
 */
internal fun interface BackoffJitter {
    /** Returns a wait in `[0, ceilingMillis)`. [ceilingMillis] is always positive. */
    fun sample(ceilingMillis: Long): Long
}

/** Full jitter over the whole ceiling — the widest spread the ceiling allows. */
private val FULL_JITTER = BackoffJitter { ceilingMillis ->
    ThreadLocalRandom.current().nextLong(ceilingMillis)
}

/**
 * How long to wait before rebuilding the Dashboard connection after a failure.
 *
 * The wait used to be a flat five seconds. When a Dashboard restarts it drops every agent at once,
 * so every agent came back on the same five-second beat and kept hammering it in lockstep — the
 * moment recovery is hardest is the moment the load is most synchronised.
 *
 * Two changes fix that. The ceiling doubles per consecutive failure, so an outage that lasts stops
 * costing a request every five seconds; and the actual wait is drawn uniformly from below the
 * ceiling ("full jitter"), which spreads a fleet that failed together across the whole window
 * instead of just moving their collision to a later instant. Full jitter can draw a very short
 * wait, which is deliberate: if the Dashboard bounced quickly, someone should find out quickly, and
 * the ceiling has already doubled by the time the next draw happens.
 *
 * The schedule itself is [StartRetryPolicy], reused rather than reimplemented — it is the same
 * doubling-with-a-cap, already tested. Only the cap differs, and it is passed in rather than
 * changed there: a runtime that cannot start is a device-local problem worth backing off from for
 * a whole minute, while a dropped connection is usually the Dashboard's and the user is watching a
 * device sit offline the entire time.
 */
internal class ReconnectBackoffPolicy(
    private val ceilings: StartRetryPolicy = StartRetryPolicy(BASE_DELAY_MILLIS, MAX_DELAY_MILLIS),
    private val jitter: BackoffJitter = FULL_JITTER
) {
    /** Returns this attempt's wait, then widens the ceiling for the next one. */
    fun nextDelayMillis(): Long = jitter.sample(ceilings.nextDelayMillis())

    /** Call once a session is genuinely working, or the next blip starts at the last ceiling. */
    fun reset() = ceilings.reset()

    internal companion object {
        /** First ceiling. Kept at the flat delay this replaced, so recovery is never slower. */
        const val BASE_DELAY_MILLIS = 5_000L

        /**
         * Last ceiling, i.e. the longest an agent can stay dark after the Dashboard comes back.
         *
         * Conservative on purpose. A longer cap spreads load better, but "the device took a minute
         * to come back" is the part a user actually sees, and that complaint outweighs the saved
         * requests. Raise it if a fleet is large enough for the reconnect burst to be the problem.
         */
        const val MAX_DELAY_MILLIS = 30_000L
    }
}
