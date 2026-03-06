package io.flowlite.test

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.core.spec.style.BehaviorSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import org.springframework.context.ConfigurableApplicationContext

class CockpitPlaywrightTest : BehaviorSpec({
    val artifactsRoot = Path.of("build", "reports", "playwright")
    val screenshotDir = artifactsRoot.resolve("screenshots")
    val videoDir = artifactsRoot.resolve("videos")

    lateinit var context: ConfigurableApplicationContext
    lateinit var playwright: Playwright
    lateinit var browser: Browser

    beforeSpec {
        Files.createDirectories(screenshotDir)
        Files.createDirectories(videoDir)

        context = startTestWebApplication()
        playwright = Playwright.create()
        browser = playwright.chromium().launch(
            BrowserType.LaunchOptions()
                .setHeadless(true),
        )
    }

    afterSpec {
        runCatching { browser.close() }
        runCatching { playwright.close() }
        runCatching { context.close() }
    }

    fun sanitizeArtifactName(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "test" }

    fun withRecordedContext(testName: String, block: (Page) -> Unit) {
        val safeTestName = sanitizeArtifactName(testName)
        val timestamp = Instant.now().toEpochMilli()
        val artifactPrefix = "$safeTestName-$timestamp"
        val browserContext = browser.newContext(
            Browser.NewContextOptions()
                .setRecordVideoDir(videoDir)
                .setViewportSize(1440, 900),
        )
        val page = browserContext.newPage()
        val pageVideo = page.video()

        try {
            block(page)
        } catch (error: Throwable) {
            val screenshotPath = screenshotDir.resolve("$artifactPrefix.png")
            runCatching {
                page.screenshot(
                    Page.ScreenshotOptions()
                        .setPath(screenshotPath)
                        .setFullPage(true),
                )
            }
            throw error
        } finally {
            browserContext.close()

            val sourceVideoPath = runCatching { pageVideo?.path() }.getOrNull()
            if (sourceVideoPath != null && Files.exists(sourceVideoPath)) {
                val targetVideoPath = videoDir.resolve("$artifactPrefix.webm")
                runCatching {
                    Files.move(sourceVideoPath, targetVideoPath, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    given("cockpit UI with Playwright") {
        `when`("loading cockpit and opening an instance") {
            then("it renders flow list and instance details") {
                withRecordedContext("it-renders-flow-list-and-instance-details") { page ->
                    page.navigate("http://127.0.0.1:8080/index.html")

                    assertThat(page.getByTestId("cockpit-title")).isVisible()
                    assertThat(page.getByTestId("flows-heading")).isVisible()
                    assertThat(page.getByTestId("flow-card-order-confirmation")).isVisible()
                    assertThat(page.getByTestId("flow-card-employee-onboarding")).isVisible()

                    page.getByTestId("tab-instances").click()
                    assertThat(page.getByTestId("instances-search")).isVisible()

                    val firstRow = page.locator("[data-testid='instances-row']").first()
                    firstRow.waitFor(
                        Locator.WaitForOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(30_000.0),
                    )
                    firstRow.click()

                    assertThat(page.getByTestId("instance-details-title")).isVisible()
                    assertThat(page.getByTestId("instance-event-history-title")).isVisible()
                }
            }
        }

        `when`("opening flow diagram from flow card") {
            then("it displays and closes the flow diagram modal") {
                withRecordedContext("it-displays-and-closes-flow-diagram-modal") { page ->
                    page.navigate("http://127.0.0.1:8080/index.html")

                    page.getByTestId("flow-view-diagram-order-confirmation").click()
                    assertThat(page.getByTestId("flow-diagram-modal")).isVisible()
                    assertThat(page.getByTestId("flow-diagram-title")).containsText("order-confirmation")

                    page.getByTestId("flow-diagram-close").click()
                    assertThat(page.getByTestId("flow-diagram-modal")).isHidden()
                }
            }
        }

        `when`("jumping to instances from flow card") {
            then("it pre-fills the instances search with flow id") {
                withRecordedContext("it-prefills-instances-search-from-flow-card") { page ->
                    page.navigate("http://127.0.0.1:8080/index.html")

                    page.getByTestId("flow-incomplete-order-confirmation").click()
                    assertThat(page.getByTestId("instances-search")).hasValue("order-confirmation")
                }
            }
        }
    }
})