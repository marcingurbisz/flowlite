package io.flowlite.test

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers

class CockpitUiStaticConfigTest : BehaviorSpec({
    given("the test web application") {
        `when`("the cockpit SPA entrypoints are requested") {
            val context = startTestWebApplication(
                showcaseEnabled = false,
                extraArgs = arrayOf("--server.port=0"),
            )

            afterContainer {
                context.close()
            }

            val port = requireNotNull(context.environment.getProperty("local.server.port"))
            val client = HttpClient.newHttpClient()

            fun get(path: String): String {
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build()
                val response = client.send(request, BodyHandlers.ofString())
                response.statusCode() shouldBe 200
                return response.body()
            }

            then("root forwards to the cockpit index") {
                get("/") shouldContain "<title>FlowLite Cockpit</title>"
            }

            then("/cockpit forwards to the cockpit index") {
                get("/cockpit") shouldContain "<title>FlowLite Cockpit</title>"
            }
        }
    }
})