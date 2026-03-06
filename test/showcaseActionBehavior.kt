package io.flowlite.test

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference

object ShowcaseActionBehavior {
    private data class Config(
        val enabled: Boolean,
        val maxDelayMs: Long,
        val failureRate: Double,
    )

    private val configRef = AtomicReference(Config(enabled = false, maxDelayMs = 0L, failureRate = 0.0))

    fun configure(enabled: Boolean, maxDelayMs: Long, failureRate: Double) {
        configRef.set(
            Config(
                enabled = enabled,
                maxDelayMs = maxDelayMs.coerceAtLeast(0),
                failureRate = failureRate.coerceIn(0.0, 1.0),
            ),
        )
    }

    fun apply(actionName: String, isShowcaseInstance: Boolean) {
        val config = configRef.get()
        if (!config.enabled || !isShowcaseInstance) return

        val delayMs = if (config.maxDelayMs == 0L) 0L else ThreadLocalRandom.current().nextLong(config.maxDelayMs + 1)
        if (delayMs > 0) {
            showcaseLog.info { "Showcase delay for action '$actionName': ${delayMs}ms" }
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }

        if (config.failureRate > 0.0 && ThreadLocalRandom.current().nextDouble() < config.failureRate) {
            throw IllegalStateException("Showcase simulated failure in action '$actionName'")
        }
    }
}

private val showcaseLog = KotlinLogging.logger {}
