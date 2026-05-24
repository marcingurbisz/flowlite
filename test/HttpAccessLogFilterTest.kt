package io.flowlite.test

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class HttpAccessLogFilterTest : BehaviorSpec({
    given("HttpAccessLogFilter") {
        `when`("enabled") {
            val logLines = mutableListOf<String>()
            var now = 1_000_000L
            val filter = HttpAccessLogFilter(
                enabled = true,
                includeQueryString = true,
                nanoTimeProvider = { now },
                logSink = { message -> logLines += message },
            )
            val request = MockHttpServletRequest("GET", "/api/flows").apply {
                queryString = "bucket=error"
                remoteAddr = "10.0.0.1"
            }
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, ResponseStatusChain(503) {
                now = 6_000_000L
            })

            then("it logs method, target, status, duration, and remote address") {
                logLines.shouldContainExactly(
                    "http access method=GET target=/api/flows?bucket=error status=503 durationMs=5 remoteAddr=10.0.0.1 failure=-",
                )
            }
        }

        `when`("disabled") {
            val logLines = mutableListOf<String>()
            val filter = HttpAccessLogFilter(
                enabled = false,
                includeQueryString = true,
                logSink = { message -> logLines += message },
            )
            val request = MockHttpServletRequest("GET", "/api/flows")
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, ResponseStatusChain(200))

            then("it skips logging") {
                logLines.shouldContainExactly(emptyList())
            }
        }
    }
})

private class ResponseStatusChain(
    private val status: Int,
    private val beforeStatus: () -> Unit = {},
) : FilterChain {
    override fun doFilter(request: ServletRequest, response: ServletResponse) {
        beforeStatus()
        (response as MockHttpServletResponse).status = status
    }
}