package org.stohncoin.wallet.security

/**
 * Attempt throttling for the app-lock PIN.
 *
 * Policy: the first [FREE_ATTEMPTS] failures are free; every failure after that blocks further
 * attempts for a doubling backoff starting at [BASE_BACKOFF_MILLIS] and capped at
 * [MAX_BACKOFF_MILLIS]. A single success clears the counter.
 *
 * Kept as a pure state machine so the policy can be unit-tested and persisted verbatim
 * (failure count plus the wall-clock instant the block expires).
 */
object PinThrottle {
    const val FREE_ATTEMPTS = 5
    const val BASE_BACKOFF_MILLIS = 30_000L
    const val MAX_BACKOFF_MILLIS = 15L * 60_000L

    data class State(val failures: Int = 0, val blockedUntil: Long = 0L)

    fun isBlocked(state: State, now: Long): Boolean = state.blockedUntil > now

    fun remainingMillis(state: State, now: Long): Long = (state.blockedUntil - now).coerceAtLeast(0L)

    fun afterFailure(state: State, now: Long): State {
        val failures = state.failures + 1
        if (failures < FREE_ATTEMPTS) return State(failures = failures)
        return State(failures = failures, blockedUntil = now + backoffMillis(failures))
    }

    fun afterSuccess(): State = State()

    /** Backoff applied for the given cumulative failure count; zero while attempts are still free. */
    fun backoffMillis(failures: Int): Long {
        val over = failures - FREE_ATTEMPTS
        if (over < 0) return 0L
        var delay = BASE_BACKOFF_MILLIS
        repeat(over.coerceAtMost(16)) { delay = (delay * 2).coerceAtMost(MAX_BACKOFF_MILLIS) }
        return delay.coerceAtMost(MAX_BACKOFF_MILLIS)
    }
}
