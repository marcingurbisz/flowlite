package io.flowlite.test

import io.flowlite.Stage
import io.flowlite.StageStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.repository.CrudRepository

fun awaitStatus(
    timeout: Duration = Duration.ofSeconds(2),
    pollInterval: Duration = Duration.ofMillis(10),
    fetch: () -> Pair<Stage, StageStatus>,
    expected: Pair<Stage, StageStatus>,
) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        if (fetch() == expected) return
        Thread.sleep(pollInterval.toMillis())
    }
    val finalStatus = fetch()
    require(finalStatus == expected) { "Expected $expected but was $finalStatus after ${timeout.toMillis()}ms" }
}

inline fun <T : Any, ID : Any> CrudRepository<T, ID>.saveWithOptimisticLockRetry(
    id: ID,
    initial: T? = null,
    maxAttempts: Int = 5,
    crossinline updateFromLatest: (T) -> T,
): T {
    require(maxAttempts >= 1) { "maxAttempts must be >= 1" }

    var attempt = 0
    var last: Throwable? = null

    if (initial != null) {
        attempt++
        try {
            return save(updateFromLatest(initial))
        } catch (ex: OptimisticLockingFailureException) {
            last = ex
        }
    }

    while (attempt++ < maxAttempts) {
        val latest = findById(id).orElseThrow()
        val updated = updateFromLatest(latest)
        try {
            return save(updated)
        } catch (ex: OptimisticLockingFailureException) {
            last = ex
        }
    }

    throw requireNotNull(last) { "saveWithOptimisticLockRetry exhausted retries" }
}

class AdjustableClock private constructor(
    private val baseClock: Clock,
    private val offsetRef: AtomicReference<Duration>,
) : Clock() {
    constructor(baseClock: Clock = Clock.systemUTC()) : this(baseClock, AtomicReference(Duration.ZERO))

    override fun getZone(): ZoneId = baseClock.zone

    override fun withZone(zone: ZoneId): Clock = AdjustableClock(baseClock.withZone(zone), offsetRef)

    override fun instant(): Instant = baseClock.instant().plus(offsetRef.get())

    fun advanceBy(duration: Duration) {
        offsetRef.updateAndGet { it.plus(duration) }
    }

    fun resetOffset() {
        offsetRef.set(Duration.ZERO)
    }

    companion object {
        fun systemUTC() = AdjustableClock(Clock.systemUTC())
    }
}
