package io.flowlite.test

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldBeEmpty

class RuntimeDiagnosticsLoggerTest : BehaviorSpec({
    given("PeriodicThreadDumpLogger") {
        `when`("enabled") {
            val dumps = mutableListOf<String>()
            val commands = mutableListOf<List<String>>()
            val logger = PeriodicThreadDumpLogger(
                enabled = true,
                intervalSeconds = 3600,
                pidProvider = { 4242L },
                threadDumpProvider = {
                    dumps += "thread dump line"
                    "thread dump line"
                },
                commandRunner = { command ->
                    commands += command
                    CommandExecutionResult(0, "thread dump line")
                },
            )

            then("it uses the diagnostic command MBean before falling back to jcmd") {
                dumps.shouldContainExactly(listOf("thread dump line"))
                commands.shouldBeEmpty()
                logger.close()
            }
        }

        `when`("the diagnostic command MBean is unavailable") {
            val commands = mutableListOf<List<String>>()
            val logger = PeriodicThreadDumpLogger(
                enabled = true,
                intervalSeconds = 3600,
                pidProvider = { 4242L },
                threadDumpProvider = { error("mbean unavailable") },
                commandRunner = { command ->
                    commands += command
                    CommandExecutionResult(0, "thread dump line")
                },
            )

            then("it falls back to jcmd thread print against the current pid") {
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
                threadDumpProvider = { "thread dump line" },
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
