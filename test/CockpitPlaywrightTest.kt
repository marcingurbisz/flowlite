package io.flowlite.test

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.core.spec.style.BehaviorSpec
import java.nio.file.Files
import java.nio.file.Path
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

    fun withRecordedContext(testName: String, block: (Page) -> Unit) {
        val browserContext = browser.newContext(
            Browser.NewContextOptions()
                .setRecordVideoDir(videoDir)
                .setViewportSize(1440, 900),
        )
        val page = browserContext.newPage()
        try {
            block(page)
        } catch (error: Throwable) {
            val screenshotPath = screenshotDir.resolve("$testName-${Instant.now().toEpochMilli()}.png")
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
        }
    }

    given("cockpit UI with Playwright") {
        `when`("loading cockpit and opening an instance") {
            then("it renders flow list and instance details") {
                withRecordedContext("cockpit-smoke") { page ->
                    page.navigate("http://127.0.0.1:8080/index.html")

                    assertThat(
                        page.getByRole(
                            AriaRole.HEADING,
                            Page.GetByRoleOptions().setName("FlowLite Cockpit"),
                        ),
                    ).isVisible()
                    assertThat(
                        page.getByRole(
                            AriaRole.HEADING,
                            Page.GetByRoleOptions().setName("Flow Definitions"),
                        ),
                    ).isVisible()
                    assertThat(page.getByText("order-confirmation").first()).isVisible()
                    assertThat(page.getByText("employee-onboarding").first()).isVisible()

                    page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Instances")).click()
                    assertThat(page.getByPlaceholder("Search by instance ID or flow ID...")).isVisible()

                    val firstRow = page.locator("tbody tr").first()
                    firstRow.waitFor(
                        Locator.WaitForOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(30_000.0),
                    )
                    firstRow.click()

                    assertThat(
                        page.getByRole(
                            AriaRole.HEADING,
                            Page.GetByRoleOptions().setName("Instance Details"),
                        ),
                    ).isVisible()
                    assertThat(page.getByText("Event History")).isVisible()
                }
            }
        }
    }
})