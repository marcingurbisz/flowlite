package io.flowlite.test

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.flowlite.FlowLiteHistoryRepository
import io.flowlite.FlowLiteHistoryRow
import io.flowlite.FlowLiteTickRepository
import io.flowlite.HistoryEntryType
import io.flowlite.PendingEventRepository
import io.flowlite.StageStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.springframework.beans.factory.getBean
import org.springframework.context.ConfigurableApplicationContext

class CockpitPlaywrightTest : BehaviorSpec({
    val artifactsRoot = Path.of("build", "reports", "playwright")
    val screenshotDir = artifactsRoot.resolve("screenshots")
    val videoDir = artifactsRoot.resolve("videos")
    val artifactTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    val cockpitBaseUrl = "http://127.0.0.1:8080/index.html"

    fun testUuid(number: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-${number.toString().padStart(12, '0')}")

    data class CockpitFixture(
        val orderPendingId: UUID = testUuid(1001),
        val orderLongRunningId: UUID = testUuid(1002),
        val orderErrorRetryId: UUID = testUuid(1003),
        val orderErrorChangeStageId: UUID = testUuid(1004),
        val employeeLongRunningId: UUID = testUuid(2001),
        val employeePendingId: UUID = testUuid(2002),
        val employeeErrorCancelId: UUID = testUuid(2003),
        val employeeCompletedId: UUID = testUuid(2004),
        val employeeCancelledId: UUID = testUuid(2005),
    )

    val fixtureIds = CockpitFixture()

    lateinit var context: ConfigurableApplicationContext
    lateinit var playwright: Playwright
    lateinit var browser: Browser
    lateinit var historyRepo: FlowLiteHistoryRepository
    lateinit var tickRepo: FlowLiteTickRepository
    lateinit var pendingEventRepo: PendingEventRepository
    lateinit var orderRepo: OrderConfirmationRepository
    lateinit var employeeRepo: EmployeeOnboardingRepository

    beforeSpec {
        Files.createDirectories(screenshotDir)
        Files.createDirectories(videoDir)

        context = startTestWebApplication(showcaseEnabled = false)
        historyRepo = context.getBean()
        tickRepo = context.getBean()
        pendingEventRepo = context.getBean()
        orderRepo = context.getBean()
        employeeRepo = context.getBean()

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
        val timestamp = Instant.now().atOffset(ZoneOffset.UTC).format(artifactTimestampFormatter)
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

    fun cockpitUrl(query: String = ""): String =
        when {
            query.isBlank() -> cockpitBaseUrl
            query.startsWith("?") -> cockpitBaseUrl + query
            else -> "$cockpitBaseUrl?$query"
        }

    fun navigateToCockpit(page: Page, query: String = "") {
        page.navigate(cockpitUrl(query))
        assertThat(page.getByTestId("cockpit-title")).isVisible()
    }

    fun instanceRow(page: Page, instanceId: UUID): Locator =
        page.locator("[data-testid='instances-row'][data-instance-id='$instanceId']")

    fun historyRow(
        occurredAt: Instant,
        flowId: String,
        flowInstanceId: UUID,
        type: HistoryEntryType,
        stage: String? = null,
        fromStage: String? = null,
        toStage: String? = null,
        fromStatus: StageStatus? = null,
        toStatus: StageStatus? = null,
        event: String? = null,
        errorType: String? = null,
        errorMessage: String? = null,
        errorStackTrace: String? = null,
    ) = FlowLiteHistoryRow(
        occurredAt = occurredAt,
        flowId = flowId,
        flowInstanceId = flowInstanceId,
        type = type,
        stage = stage,
        fromStage = fromStage,
        toStage = toStage,
        fromStatus = fromStatus?.name,
        toStatus = toStatus?.name,
        event = event,
        errorType = errorType,
        errorMessage = errorMessage,
        errorStackTrace = errorStackTrace,
    )

    fun resetCockpitData() {
        tickRepo.deleteAll()
        pendingEventRepo.deleteAll()
        historyRepo.deleteAll()
        orderRepo.deleteAll()
        employeeRepo.deleteAll()
    }

    fun saveOrderInstance(
        id: UUID,
        stage: OrderConfirmationStage,
        status: StageStatus,
        orderNumber: String,
        confirmationType: ConfirmationType = ConfirmationType.Digital,
        customerName: String = orderNumber,
    ) {
        orderRepo.save(
            OrderConfirmation(
                id = id,
                stage = stage,
                stageStatus = status,
                orderNumber = orderNumber,
                confirmationType = confirmationType,
                customerName = customerName,
            ),
        )
    }

    fun saveEmployeeInstance(
        id: UUID,
        stage: EmployeeStage,
        status: StageStatus,
        isNotManualPath: Boolean = true,
        isNotContractor: Boolean = true,
    ) {
        employeeRepo.save(
            EmployeeOnboarding(
                id = id,
                stage = stage,
                stageStatus = status,
                isOnboardingAutomated = true,
                isNotManualPath = isNotManualPath,
                isNotContractor = isNotContractor,
            ),
        )
    }

    fun seedEmptyFixture() {
        resetCockpitData()
    }

    fun seedRichFixture(): CockpitFixture {
        resetCockpitData()
        val now = Instant.now()

        saveOrderInstance(
            id = fixtureIds.orderPendingId,
            stage = OrderConfirmationStage.WaitingForConfirmation,
            status = StageStatus.Pending,
            orderNumber = "ORD-PENDING",
        )
        historyRepo.save(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(20)),
                flowId = ORDER_CONFIRMATION_FLOW_ID,
                flowInstanceId = fixtureIds.orderPendingId,
                type = HistoryEntryType.Started,
                stage = OrderConfirmationStage.WaitingForConfirmation.name,
                toStatus = StageStatus.Pending,
            ),
        )

        saveOrderInstance(
            id = fixtureIds.orderLongRunningId,
            stage = OrderConfirmationStage.WaitingForConfirmation,
            status = StageStatus.Running,
            orderNumber = "ORD-LONG-RUNNING",
        )
        historyRepo.save(
            historyRow(
                occurredAt = now.minus(Duration.ofHours(3)),
                flowId = ORDER_CONFIRMATION_FLOW_ID,
                flowInstanceId = fixtureIds.orderLongRunningId,
                type = HistoryEntryType.Started,
                stage = OrderConfirmationStage.WaitingForConfirmation.name,
                toStatus = StageStatus.Pending,
            ),
        )
        historyRepo.save(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(150)),
                flowId = ORDER_CONFIRMATION_FLOW_ID,
                flowInstanceId = fixtureIds.orderLongRunningId,
                type = HistoryEntryType.StatusChanged,
                stage = OrderConfirmationStage.WaitingForConfirmation.name,
                fromStatus = StageStatus.Pending,
                toStatus = StageStatus.Running,
            ),
        )

        saveOrderInstance(
            id = fixtureIds.orderErrorRetryId,
            stage = OrderConfirmationStage.InformingCustomer,
            status = StageStatus.Error,
            orderNumber = "ORD-ERROR-RETRY",
        )
        historyRepo.save(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(50)),
                flowId = ORDER_CONFIRMATION_FLOW_ID,
                flowInstanceId = fixtureIds.orderErrorRetryId,
                type = HistoryEntryType.Started,
                stage = OrderConfirmationStage.InformingCustomer.name,
                toStatus = StageStatus.Pending,
            ),
        )
        historyRepo.save(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(15)),
                flowId = ORDER_CONFIRMATION_FLOW_ID,
                flowInstanceId = fixtureIds.orderErrorRetryId,
                type = HistoryEntryType.Error,
                stage = OrderConfirmationStage.InformingCustomer.name,
                fromStatus = StageStatus.Running,
                toStatus = StageStatus.Error,
                errorType = IllegalStateException::class.qualifiedName,
                errorMessage = "notification retry needed",
                errorStackTrace = "java.lang.IllegalStateException: notification retry needed\n\tat io.flowlite.test.Notify.retry(Notify.kt:42)",
            ),
        )

        saveOrderInstance(
            id = fixtureIds.orderErrorChangeStageId,
            stage = OrderConfirmationStage.InformingCustomer,
            status = StageStatus.Error,
            orderNumber = "ORD-ERROR-CHANGE-STAGE",
            confirmationType = ConfirmationType.Physical,
        )
        historyRepo.save(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(45)),
                flowId = ORDER_CONFIRMATION_FLOW_ID,
                flowInstanceId = fixtureIds.orderErrorChangeStageId,
                type = HistoryEntryType.Started,
                stage = OrderConfirmationStage.InformingCustomer.name,
                toStatus = StageStatus.Pending,
            ),
        )
        historyRepo.save(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(14)),
                flowId = ORDER_CONFIRMATION_FLOW_ID,
                flowInstanceId = fixtureIds.orderErrorChangeStageId,
                type = HistoryEntryType.Error,
                stage = OrderConfirmationStage.InformingCustomer.name,
                fromStatus = StageStatus.Running,
                toStatus = StageStatus.Error,
                errorType = IllegalArgumentException::class.qualifiedName,
                errorMessage = "manual notification staging required",
                errorStackTrace = "java.lang.IllegalArgumentException: manual notification staging required\n\tat io.flowlite.test.Notify.stage(Notify.kt:71)",
            ),
        )

        saveEmployeeInstance(
            id = fixtureIds.employeeLongRunningId,
            stage = EmployeeStage.UpdateHRSystem,
            status = StageStatus.Running,
        )
        historyRepo.save(
            historyRow(
                occurredAt = now.minus(Duration.ofHours(5)),
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = fixtureIds.employeeLongRunningId,
                type = HistoryEntryType.Started,
                stage = EmployeeStage.UpdateHRSystem.name,
                toStatus = StageStatus.Pending,
            ),
        )
        historyRepo.save(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(270)),
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = fixtureIds.employeeLongRunningId,
                type = HistoryEntryType.StatusChanged,
                stage = EmployeeStage.UpdateHRSystem.name,
                fromStatus = StageStatus.Pending,
                toStatus = StageStatus.Running,
            ),
        )

        saveEmployeeInstance(
            id = fixtureIds.employeePendingId,
            stage = EmployeeStage.WaitingForOnboardingAgreementSigned,
            status = StageStatus.Pending,
        )
        historyRepo.save(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(10)),
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = fixtureIds.employeePendingId,
                type = HistoryEntryType.Started,
                stage = EmployeeStage.WaitingForOnboardingAgreementSigned.name,
                toStatus = StageStatus.Pending,
            ),
        )

        saveEmployeeInstance(
            id = fixtureIds.employeeErrorCancelId,
            stage = EmployeeStage.UpdateHRSystem,
            status = StageStatus.Error,
        )
        historyRepo.save(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(40)),
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = fixtureIds.employeeErrorCancelId,
                type = HistoryEntryType.Started,
                stage = EmployeeStage.UpdateHRSystem.name,
                toStatus = StageStatus.Pending,
            ),
        )
        historyRepo.save(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(12)),
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = fixtureIds.employeeErrorCancelId,
                type = HistoryEntryType.Error,
                stage = EmployeeStage.UpdateHRSystem.name,
                fromStatus = StageStatus.Running,
                toStatus = StageStatus.Error,
                errorType = RuntimeException::class.qualifiedName,
                errorMessage = "hr sync timeout",
                errorStackTrace = "java.lang.RuntimeException: hr sync timeout\n\tat io.flowlite.test.HrSync.update(HrSync.kt:19)",
            ),
        )

        saveEmployeeInstance(
            id = fixtureIds.employeeCompletedId,
            stage = EmployeeStage.CompleteOnboarding,
            status = StageStatus.Completed,
        )
        historyRepo.save(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(90)),
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = fixtureIds.employeeCompletedId,
                type = HistoryEntryType.Started,
                stage = EmployeeStage.CompleteOnboarding.name,
                toStatus = StageStatus.Pending,
            ),
        )
        historyRepo.save(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(89)),
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = fixtureIds.employeeCompletedId,
                type = HistoryEntryType.StatusChanged,
                stage = EmployeeStage.CompleteOnboarding.name,
                fromStatus = StageStatus.Running,
                toStatus = StageStatus.Completed,
            ),
        )

        saveEmployeeInstance(
            id = fixtureIds.employeeCancelledId,
            stage = EmployeeStage.WaitingForManualApproval,
            status = StageStatus.Cancelled,
            isNotManualPath = false,
        )
        historyRepo.save(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(100)),
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = fixtureIds.employeeCancelledId,
                type = HistoryEntryType.Started,
                stage = EmployeeStage.WaitingForManualApproval.name,
                toStatus = StageStatus.Pending,
            ),
        )
        historyRepo.save(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(98)),
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = fixtureIds.employeeCancelledId,
                type = HistoryEntryType.Cancelled,
                stage = EmployeeStage.WaitingForManualApproval.name,
                fromStatus = StageStatus.Running,
                toStatus = StageStatus.Cancelled,
            ),
        )

        return fixtureIds
    }

    given("cockpit UI with Playwright") {
        `when`("loading cockpit and opening an instance") {
            then("it renders flow list and instance details") {
                val fixture = seedRichFixture()

                withRecordedContext("it-renders-flow-list-and-instance-details") { page ->
                    navigateToCockpit(page)

                    assertThat(page.getByTestId("flows-heading")).isVisible()
                    assertThat(page.getByTestId("flow-card-order-confirmation")).isVisible()
                    assertThat(page.getByTestId("flow-card-employee-onboarding")).isVisible()

                    page.getByTestId("tab-instances").click()
                    assertThat(page.getByTestId("instances-search")).isVisible()

                    val pendingRow = instanceRow(page, fixture.orderPendingId)
                    assertThat(pendingRow).isVisible()
                    pendingRow.click()

                    assertThat(page.getByTestId("instance-details-title")).isVisible()
                    assertThat(page.getByTestId("instance-event-history-title")).isVisible()
                }
            }
        }

        `when`("using flow definition shortcuts and browser navigation") {
            then("it supports long running, incomplete, stage and error jumps with bookmarkable URLs") {
                seedRichFixture()

                withRecordedContext("it-supports-flow-shortcuts-and-bookmarks") { page ->
                    navigateToCockpit(page)

                    page.getByTestId("flow-long-running-order-confirmation").click()
                    assertThat(page.getByTestId("long-running-heading")).isVisible()
                    assertThat(page.getByTestId("long-running-flow-filter")).hasValue(ORDER_CONFIRMATION_FLOW_ID)

                    page.goBack()
                    assertThat(page.getByTestId("flows-heading")).isVisible()

                    page.getByTestId("flow-incomplete-order-confirmation").click()
                    assertThat(page.getByTestId("instances-search")).hasValue(ORDER_CONFIRMATION_FLOW_ID)
                    page.url().shouldContain("tab=instances")
                    page.url().shouldContain("q=order-confirmation")
                    page.url().shouldContain("incomplete=1")

                    page.goBack()
                    assertThat(page.getByTestId("flows-heading")).isVisible()

                    page.getByTestId("flow-stage-order-confirmation-WaitingForConfirmation").click()
                    assertThat(page.getByTestId("instances-stage-filter")).hasValue(OrderConfirmationStage.WaitingForConfirmation.name)

                    page.goBack()
                    assertThat(page.getByTestId("flows-heading")).isVisible()

                    page.getByTestId("flow-stage-errors-order-confirmation-InformingCustomer").click()
                    assertThat(page.getByTestId("errors-flow-filter")).hasValue(ORDER_CONFIRMATION_FLOW_ID)
                    assertThat(page.getByTestId("errors-stage-filter")).hasValue(OrderConfirmationStage.InformingCustomer.name)

                    val bookmarkUrl = page.url()
                    bookmarkUrl.shouldContain("tab=errors")
                    bookmarkUrl.shouldContain("errorFlow=order-confirmation")
                    bookmarkUrl.shouldContain("errorStage=InformingCustomer")

                    page.navigate(bookmarkUrl)
                    assertThat(page.getByTestId("errors-flow-filter")).hasValue(ORDER_CONFIRMATION_FLOW_ID)
                    assertThat(page.getByTestId("errors-stage-filter")).hasValue(OrderConfirmationStage.InformingCustomer.name)
                }
            }
        }

        `when`("opening the errors view without failures") {
            then("it shows the empty state") {
                seedEmptyFixture()

                withRecordedContext("it-shows-empty-errors-state") { page ->
                    navigateToCockpit(page, "tab=errors")

                    assertThat(page.getByTestId("errors-empty")).isVisible()
                }
            }
        }

        `when`("filtering and inspecting errors") {
            then("it filters groups, expands stack traces, and supports selecting and deselecting") {
                val fixture = seedRichFixture()

                withRecordedContext("it-filters-errors-and-expands-stack-traces") { page ->
                    navigateToCockpit(page, "tab=errors")

                    assertThat(page.getByTestId("error-group-order-confirmation-InformingCustomer")).isVisible()
                    assertThat(page.getByTestId("error-group-employee-onboarding-UpdateHRSystem")).isVisible()

                    page.getByTestId("errors-flow-filter").selectOption(EMPLOYEE_ONBOARDING_FLOW_ID)
                    assertThat(page.getByTestId("error-group-employee-onboarding-UpdateHRSystem")).isVisible()
                    assertThat(page.getByTestId("error-group-order-confirmation-InformingCustomer")).hasCount(0)

                    page.getByTestId("errors-flow-filter").selectOption("all")
                    page.getByTestId("errors-stage-filter").fill(OrderConfirmationStage.InformingCustomer.name)
                    assertThat(page.getByTestId("error-group-order-confirmation-InformingCustomer")).isVisible()
                    assertThat(page.getByTestId("error-group-employee-onboarding-UpdateHRSystem")).hasCount(0)

                    page.getByTestId("errors-stage-filter").fill("")
                    page.getByTestId("errors-message-filter").fill("hr sync")
                    assertThat(page.getByTestId("error-group-employee-onboarding-UpdateHRSystem")).isVisible()
                    assertThat(page.getByTestId("error-group-order-confirmation-InformingCustomer")).hasCount(0)

                    page.getByTestId("errors-message-filter").fill("")
                    page.getByTestId("error-instance-checkbox-${fixture.orderErrorRetryId}").check()
                    page.getByTestId("error-instance-checkbox-${fixture.employeeErrorCancelId}").check()
                    assertThat(page.getByTestId("errors-selection-bar")).containsText("2 error(s) selected")
                    page.getByTestId("errors-deselect-selected").click()
                    assertThat(page.getByTestId("errors-selection-bar")).hasCount(0)

                    page.getByTestId("error-instance-${fixture.orderErrorRetryId}").click()
                    assertThat(page.getByTestId("instance-details-modal")).isVisible()

                    page.getByTestId("instance-error-stacktrace-toggle").click()
                    assertThat(page.getByTestId("instance-error-stacktrace")).containsText("notification retry needed")

                    page.getByTestId("instance-history-stacktrace-toggle-1").click()
                    assertThat(page.getByTestId("instance-history-stacktrace-1")).containsText("Notify.retry")

                    page.getByTestId("instance-details-close").click()
                    assertThat(page.getByTestId("instance-details-modal")).hasCount(0)
                }
            }
        }

        `when`("performing error actions") {
            then("it retries, changes stage, and cancels selected instances") {
                val fixture = seedRichFixture()

                withRecordedContext("it-performs-error-actions") { page ->
                    navigateToCockpit(page, "tab=errors")

                    page.getByTestId("error-instance-checkbox-${fixture.orderErrorRetryId}").check()
                    page.getByTestId("errors-retry-selected").click()
                    assertThat(page.getByTestId("error-instance-${fixture.orderErrorRetryId}")).hasCount(0)

                    page.getByTestId("error-instance-checkbox-${fixture.orderErrorChangeStageId}").check()
                    page.getByTestId("errors-change-stage-selected").click()
                    assertThat(page.getByTestId("change-stage-modal")).isVisible()
                    page.getByTestId("change-stage-select").selectOption(OrderConfirmationStage.WaitingForConfirmation.name)
                    page.getByTestId("change-stage-confirm").click()
                    assertThat(page.getByTestId("error-instance-${fixture.orderErrorChangeStageId}")).hasCount(0)

                    page.getByTestId("error-instance-checkbox-${fixture.employeeErrorCancelId}").check()
                    page.getByTestId("errors-cancel-selected").click()
                    assertThat(page.getByTestId("error-instance-${fixture.employeeErrorCancelId}")).hasCount(0)
                    assertThat(page.getByTestId("errors-empty")).isVisible()

                    page.getByTestId("tab-instances").click()
                    page.getByTestId("instances-search").fill(fixture.orderErrorChangeStageId.toString())
                    assertThat(instanceRow(page, fixture.orderErrorChangeStageId)).isVisible()
                    instanceRow(page, fixture.orderErrorChangeStageId).click()
                    assertThat(page.getByTestId("instance-details-stage")).containsText(OrderConfirmationStage.WaitingForConfirmation.name)
                    page.getByTestId("instance-details-close").click()

                    page.getByTestId("instances-search").fill(fixture.employeeErrorCancelId.toString())
                    assertThat(instanceRow(page, fixture.employeeErrorCancelId)).isVisible()
                    instanceRow(page, fixture.employeeErrorCancelId).click()
                    assertThat(page.getByTestId("instance-details-status")).containsText(StageStatus.Cancelled.name)
                }
            }
        }

        `when`("working with long running instances") {
            then("it filters, selects, deselects, and retries selected rows") {
                val fixture = seedRichFixture()

                withRecordedContext("it-filters-and-retries-long-running-instances") { page ->
                    navigateToCockpit(page, "tab=long-running")

                    assertThat(page.getByTestId("long-running-row-${fixture.orderLongRunningId}")).isVisible()
                    assertThat(page.getByTestId("long-running-row-${fixture.employeeLongRunningId}")).isVisible()

                    page.getByTestId("long-running-flow-filter").selectOption(ORDER_CONFIRMATION_FLOW_ID)
                    assertThat(page.getByTestId("long-running-row-${fixture.orderLongRunningId}")).isVisible()
                    assertThat(page.getByTestId("long-running-row-${fixture.employeeLongRunningId}")).hasCount(0)

                    page.getByTestId("long-running-flow-filter").selectOption("all")
                    page.getByTestId("long-running-threshold").fill("3")
                    assertThat(page.getByTestId("long-running-row-${fixture.employeeLongRunningId}")).isVisible()
                    assertThat(page.getByTestId("long-running-row-${fixture.orderLongRunningId}")).hasCount(0)

                    page.getByTestId("long-running-threshold").fill("1")
                    page.getByTestId("long-running-flow-filter").selectOption(ORDER_CONFIRMATION_FLOW_ID)
                    page.getByTestId("long-running-checkbox-${fixture.orderLongRunningId}").check()
                    assertThat(page.getByTestId("long-running-selection-bar")).containsText("1 long running instance(s) selected")

                    page.getByTestId("long-running-deselect-selected").click()
                    assertThat(page.getByTestId("long-running-selection-bar")).hasCount(0)

                    page.getByTestId("long-running-checkbox-${fixture.orderLongRunningId}").check()
                    page.getByTestId("long-running-retry-selected").click()
                    assertThat(page.getByTestId("long-running-row-${fixture.orderLongRunningId}")).hasCount(0)

                    page.getByTestId("tab-instances").click()
                    page.getByTestId("instances-search").fill(fixture.orderLongRunningId.toString())
                    assertThat(instanceRow(page, fixture.orderLongRunningId)).isVisible()
                    assertThat(page.getByTestId("instance-status-${fixture.orderLongRunningId}")).containsText(StageStatus.Pending.name)
                }
            }
        }

        `when`("filtering instances") {
            then("it supports search, stage, error, status, and clear filters") {
                val fixture = seedRichFixture()

                withRecordedContext("it-filters-instances-and-clears-filters") { page ->
                    navigateToCockpit(page, "tab=instances")

                    page.getByTestId("instances-search").fill(EMPLOYEE_ONBOARDING_FLOW_ID)
                    assertThat(instanceRow(page, fixture.employeePendingId)).isVisible()
                    assertThat(instanceRow(page, fixture.orderPendingId)).hasCount(0)

                    page.getByTestId("instances-search").fill(fixture.orderPendingId.toString())
                    assertThat(instanceRow(page, fixture.orderPendingId)).isVisible()
                    assertThat(instanceRow(page, fixture.employeePendingId)).hasCount(0)

                    page.getByTestId("instances-clear-filters").click()
                    page.getByTestId("instances-stage-filter").fill(OrderConfirmationStage.WaitingForConfirmation.name)
                    assertThat(instanceRow(page, fixture.orderPendingId)).isVisible()
                    assertThat(instanceRow(page, fixture.orderLongRunningId)).isVisible()
                    assertThat(instanceRow(page, fixture.employeePendingId)).hasCount(0)

                    page.getByTestId("instances-clear-filters").click()
                    page.getByTestId("instances-error-filter").fill("hr sync")
                    assertThat(instanceRow(page, fixture.employeeErrorCancelId)).isVisible()
                    assertThat(instanceRow(page, fixture.orderErrorRetryId)).hasCount(0)

                    page.getByTestId("instances-clear-filters").click()
                    page.getByTestId("instances-status-filter").selectOption(StageStatus.Cancelled.name)
                    assertThat(instanceRow(page, fixture.employeeCancelledId)).isVisible()
                    assertThat(instanceRow(page, fixture.employeeCompletedId)).hasCount(0)

                    page.getByTestId("instances-clear-filters").click()
                    assertThat(page.getByTestId("instances-search")).hasValue("")
                    assertThat(page.getByTestId("instances-status-filter")).hasValue("all")
                    assertThat(page.getByTestId("instances-stage-filter")).hasValue("")
                    assertThat(page.getByTestId("instances-error-filter")).hasValue("")
                    page.url().shouldContain("tab=instances")
                    assertThat(instanceRow(page, fixture.orderPendingId)).isVisible()
                    assertThat(instanceRow(page, fixture.employeeCompletedId)).isVisible()
                }
            }
        }
    }
})