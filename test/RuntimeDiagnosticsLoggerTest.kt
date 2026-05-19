package io.flowlite.test

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly

class RuntimeDiagnosticsLoggerTest : BehaviorSpec({
    given("PeriodicThreadDumpLogger") {
        `when`("enabled") {
            val dumps = mutableListOf<String>()
            val logger = PeriodicThreadDumpLogger(
                enabled = true,
                intervalSeconds = 3600,
                pidProvider = { 4242L },
                threadDumpProvider = {
                    dumps += "thread dump line"
                    "thread dump line"
                },
            )

            then("it uses the diagnostic command MBean") {
                dumps.shouldContainExactly(listOf("thread dump line"))
                logger.close()
            }
        }

        `when`("disabled") {
            val logger = PeriodicThreadDumpLogger(
                enabled = false,
                intervalSeconds = 3600,
                pidProvider = { 4242L },
                threadDumpProvider = { error("should not be called") },
            )

            then("it does not trigger a thread dump") {
                logger.close()
            }
        }
    }
})
