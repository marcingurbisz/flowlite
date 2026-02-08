package io.flowlite.test

import io.flowlite.Stage
import io.flowlite.StageStatus
import java.time.Duration

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
