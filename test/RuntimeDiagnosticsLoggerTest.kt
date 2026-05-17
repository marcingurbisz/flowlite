package io.flowlite.test

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldBeEmpty

class RuntimeDiagnosticsLoggerTest : BehaviorSpec({
    given("PeriodicThreadDumpLogger") {
        `when`("enabled") {
            val commands = mutableListOf<List<String>>()
            val logger = PeriodicThreadDumpLogger(
                enabled = true,
                intervalSeconds = 3600,
                pidProvider = { 4242L },
                commandRunner = { command ->
                    commands += command
                    CommandExecutionResult(0, "thread dump line")
                },
            )

            then("it runs jcmd thread print against the current pid on startup") {
                commands.shouldContainExactly(listOf(listOf("jcmd", "4242", "Thread.print")))
                logger.close()
            }
        }

        `when`("disabled") {
            val commands = mutableListOf<List<String>>()
            val logger = PeriodicThreadDumpLogger(
                enabled = false,
                intervalSeconds = 3600,
                pidProvider = { 4242L },
                commandRunner = { command ->
                    commands += command
                    CommandExecutionResult(0, "thread dump line")
                },
            )

            then("it does not invoke jcmd") {
                commands.shouldBeEmpty()
                logger.close()
            }
        }
    }
})
