package io.flowlite.test

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import io.flowlite.FlowLiteHistoryRepository
import io.flowlite.FlowLiteHistoryRow
import io.flowlite.FlowLiteInstanceSummaryRepository
import io.flowlite.FlowLiteTickRepository
import io.flowlite.HistoryEntryType
import io.flowlite.PendingEventRepository
import io.flowlite.SpringDataJdbcHistoryStore
import io.flowlite.StageStatus
import io.flowlite.toHistoryEntry
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
    val frontendCoverageDir = artifactsRoot.resolve("frontend-coverage")
    val frontendCoverageRawDir = artifactsRoot.resolve("frontend-coverage-raw")
    val screenshotDir = artifactsRoot.resolve("screenshots")
    val videoDir = artifactsRoot.resolve("videos")
    val artifactTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    lateinit var cockpitBaseUrl: String
    val frontendCoverageStorageKey = "__flowlite_frontend_coverage_snapshots__"

    fun testUuid(number: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-${number.toString().padStart(12, '0')}")

    data class CockpitFixture(
        val orderPendingId: UUID = testUuid(1001),
        val orderLongRunningId: UUID = testUuid(1002),
        val orderErrorRetryId: UUID = testUuid(1003),
        val orderErrorChangeStageId: UUID = testUuid(1004),
        val employeeLongRunningId: UUID = testUuid(2001),
        val employeePendingId: UUID = testUuid(2002),
        val employeeTimerPendingId: UUID = testUuid(2006),
        val employeeErrorCancelId: UUID = testUuid(2003),
        val employeeCompletedId: UUID = testUuid(2004),
        val employeeCancelledId: UUID = testUuid(2005),
    )

    class RecordedPageSession(
        val page: Page,
        private val closeAction: (Throwable?) -> Unit,
    ) {
        fun close(failure: Throwable? = null) {
            closeAction(failure)
        }
    }

    val fixtureIds = CockpitFixture()

    lateinit var context: ConfigurableApplicationContext
    lateinit var playwright: Playwright
    lateinit var browser: Browser
    lateinit var historyRepo: FlowLiteHistoryRepository
    lateinit var historyStore: SpringDataJdbcHistoryStore
    lateinit var summaryRepo: FlowLiteInstanceSummaryRepository
    lateinit var tickRepo: FlowLiteTickRepository
    lateinit var pendingEventRepo: PendingEventRepository
    lateinit var orderRepo: OrderConfirmationRepository
    lateinit var employeeRepo: EmployeeOnboardingRepository

    beforeSpec {
        Files.createDirectories(frontendCoverageRawDir)
        Files.createDirectories(screenshotDir)
        Files.createDirectories(videoDir)

        context = startTestWebApplication(
            showcaseEnabled = false,
            extraArgs = arrayOf("--server.port=0"),
        )
        val port = requireNotNull(context.environment.getProperty("local.server.port"))
        cockpitBaseUrl = "http://127.0.0.1:$port/cockpit"
        historyRepo = context.getBean()
        historyStore = context.getBean()
        summaryRepo = context.getBean()
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

    fun openRecordedContext(testName: String): RecordedPageSession {
        val safeTestName = sanitizeArtifactName(testName)
        val timestamp = Instant.now().atOffset(ZoneOffset.UTC).format(artifactTimestampFormatter)
        val artifactPrefix = "$safeTestName-$timestamp"
        val browserContext = browser.newContext(
            Browser.NewContextOptions()
                .setRecordVideoDir(videoDir)
                .setTimezoneId("Europe/Warsaw")
                .setViewportSize(1440, 900),
        )
        val page = browserContext.newPage()
        val pageVideo = page.video()

                page.addInitScript(
                        """
                        (() => {
                            const storageKey = '$frontendCoverageStorageKey';
                            const persistCoverageSnapshot = () => {
                                try {
                                    const coverage = globalThis.__coverage__;
                                    if (!coverage) return;
                                    const snapshots = JSON.parse(sessionStorage.getItem(storageKey) || '[]');
                                    snapshots.push(JSON.stringify(coverage));
                                    sessionStorage.setItem(storageKey, JSON.stringify(snapshots));
                                } catch {
                                    // Best-effort test-only coverage capture.
                                }
                            };

                            addEventListener('beforeunload', persistCoverageSnapshot);
                        })();
                        """.trimIndent(),
                )

        return RecordedPageSession(page) { failure ->
            try {
                if (failure != null) {
                    val screenshotPath = screenshotDir.resolve("$artifactPrefix.png")
                    runCatching {
                        page.screenshot(
                            Page.ScreenshotOptions()
                                .setPath(screenshotPath)
                                .setFullPage(true),
                        )
                    }
                }

                val frontendCoverageSnapshots = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    page.evaluate(
                        """
                        storageKey => {
                          const snapshots = JSON.parse(sessionStorage.getItem(storageKey) || '[]');
                          const currentCoverage = globalThis.__coverage__ ? JSON.stringify(globalThis.__coverage__) : null;
                          return currentCoverage ? [...snapshots, currentCoverage] : snapshots;
                        }
                        """.trimIndent(),
                        frontendCoverageStorageKey,
                    ) as? List<String>
                }.getOrNull().orEmpty()

                frontendCoverageSnapshots.forEachIndexed { index, snapshot ->
                    if (snapshot.isBlank() || snapshot == "null") return@forEachIndexed
                    Files.writeString(
                        frontendCoverageRawDir.resolve("$artifactPrefix-${index + 1}.json"),
                        snapshot,
                    )
                }
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
    }

    fun verifyRecordedContext(session: RecordedPageSession, assertions: (Page) -> Unit) {
        var failure: Throwable? = null
        try {
            assertions(session.page)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            session.close(failure)
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
        summaryRepo.deleteAll()
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

    fun appendHistory(row: FlowLiteHistoryRow) {
        historyStore.append(row.toHistoryEntry())
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
        appendHistory(
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
        appendHistory(
            historyRow(
                occurredAt = now.minus(Duration.ofHours(3)),
                flowId = ORDER_CONFIRMATION_FLOW_ID,
                flowInstanceId = fixtureIds.orderLongRunningId,
                type = HistoryEntryType.Started,
                stage = OrderConfirmationStage.WaitingForConfirmation.name,
                toStatus = StageStatus.Pending,
            ),
        )
        appendHistory(
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
        appendHistory(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(50)),
                flowId = ORDER_CONFIRMATION_FLOW_ID,
                flowInstanceId = fixtureIds.orderErrorRetryId,
                type = HistoryEntryType.Started,
                stage = OrderConfirmationStage.InformingCustomer.name,
                toStatus = StageStatus.Pending,
            ),
        )
        appendHistory(
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
        appendHistory(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(45)),
                flowId = ORDER_CONFIRMATION_FLOW_ID,
                flowInstanceId = fixtureIds.orderErrorChangeStageId,
                type = HistoryEntryType.Started,
                stage = OrderConfirmationStage.InformingCustomer.name,
                toStatus = StageStatus.Pending,
            ),
        )
        appendHistory(
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
        appendHistory(
            historyRow(
                occurredAt = now.minus(Duration.ofHours(5)),
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = fixtureIds.employeeLongRunningId,
                type = HistoryEntryType.Started,
                stage = EmployeeStage.UpdateHRSystem.name,
                toStatus = StageStatus.Pending,
            ),
        )
        appendHistory(
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
        appendHistory(
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
            id = fixtureIds.employeeTimerPendingId,
            stage = EmployeeStage.DelayAfterHRUpdate,
            status = StageStatus.Pending,
        )
        appendHistory(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(125)),
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = fixtureIds.employeeTimerPendingId,
                type = HistoryEntryType.Started,
                stage = EmployeeStage.DelayAfterHRUpdate.name,
                toStatus = StageStatus.Pending,
            ),
        )

        saveEmployeeInstance(
            id = fixtureIds.employeeErrorCancelId,
            stage = EmployeeStage.UpdateHRSystem,
            status = StageStatus.Error,
        )
        appendHistory(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(40)),
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = fixtureIds.employeeErrorCancelId,
                type = HistoryEntryType.Started,
                stage = EmployeeStage.UpdateHRSystem.name,
                toStatus = StageStatus.Pending,
            ),
        )
        appendHistory(
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
        appendHistory(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(90)),
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = fixtureIds.employeeCompletedId,
                type = HistoryEntryType.Started,
                stage = EmployeeStage.CompleteOnboarding.name,
                toStatus = StageStatus.Pending,
            ),
        )
        appendHistory(
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
        appendHistory(
            historyRow(
                occurredAt = now.minus(Duration.ofMinutes(100)),
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = fixtureIds.employeeCancelledId,
                type = HistoryEntryType.Started,
                stage = EmployeeStage.WaitingForManualApproval.name,
                toStatus = StageStatus.Pending,
            ),
        )
        appendHistory(
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
            val fixture = seedRichFixture()
            val session = openRecordedContext("it-renders-flow-list-and-instance-details")
            val page = session.page
            var flowsHeadingLoaded = false
            var orderFlowCardLoaded = false
            var employeeFlowCardLoaded = false

            navigateToCockpit(page)

            assertThat(page.getByTestId("flows-heading")).isVisible()
            flowsHeadingLoaded = true
            assertThat(page.getByTestId("flow-card-order-confirmation")).isVisible()
            orderFlowCardLoaded = true
            assertThat(page.getByTestId("flow-card-employee-onboarding")).isVisible()
            employeeFlowCardLoaded = true

            page.getByTestId("tab-instances").click()
            page.getByTestId("instances-search").fill(fixture.orderPendingId.toString())

            val pendingRow = instanceRow(page, fixture.orderPendingId)
            pendingRow.click()

            then("it renders flow list and instance details") {
                verifyRecordedContext(session) { currentPage ->
                    flowsHeadingLoaded shouldBe true
                    orderFlowCardLoaded shouldBe true
                    employeeFlowCardLoaded shouldBe true
                    assertThat(currentPage.getByTestId("instances-search")).isVisible()
                    assertThat(instanceRow(currentPage, fixture.orderPendingId)).isVisible()
                    assertThat(currentPage.getByTestId("instance-details-title")).isVisible()
                    assertThat(currentPage.getByTestId("instance-event-history-title")).isVisible()
                }
            }
        }

        `when`("using bookmarkable detail modals and keyboard dismissal") {
            val fixture = seedRichFixture()
            val session = openRecordedContext("it-bookmarks-and-dismisses-modals-with-escape")
            val page = session.page
            var listCopyWorked = false
            var detailsCopyWorked = false
            var bookmarkedUrl = ""

            navigateToCockpit(page, "tab=instances")

            page.getByTestId("instances-search").fill(fixture.orderPendingId.toString())
            page.getByTestId("copy-instance-list-id-${fixture.orderPendingId}").click()
            assertThat(page.getByTestId("copy-instance-list-id-${fixture.orderPendingId}")).containsText("Copied")
            listCopyWorked = true
            instanceRow(page, fixture.orderPendingId).click()
            page.getByTestId("copy-instance-details-id").click()
            assertThat(page.getByTestId("copy-instance-details-id")).containsText("Copied")
            detailsCopyWorked = true

            bookmarkedUrl = page.url()
            page.navigate(bookmarkedUrl)
            assertThat(page.getByTestId("instance-details-modal")).isVisible()
            page.keyboard().press("Escape")
            assertThat(page.getByTestId("instance-details-modal")).hasCount(0)

            page.navigate(cockpitUrl())
            page.getByTestId("flow-view-diagram-order-confirmation").click()
            assertThat(page.getByTestId("flow-diagram-modal")).isVisible()
            page.keyboard().press("Escape")
            assertThat(page.getByTestId("flow-diagram-modal")).hasCount(0)

            page.navigate(cockpitUrl("tab=errors"))
            page.getByTestId("error-instance-${fixture.orderErrorChangeStageId}").click()
            page.getByTestId("instance-change-stage").click()
            assertThat(page.getByTestId("change-stage-modal")).isVisible()
            page.keyboard().press("Escape")

            then("it syncs the instance modal with the URL, supports copy buttons, and closes modals with escape") {
                verifyRecordedContext(session) { currentPage ->
                    listCopyWorked shouldBe true
                    detailsCopyWorked shouldBe true
                    bookmarkedUrl.shouldContain("instanceFlowId=$ORDER_CONFIRMATION_FLOW_ID")
                    bookmarkedUrl.shouldContain("instanceId=${fixture.orderPendingId}")
                    assertThat(currentPage.getByTestId("instance-details-modal")).isVisible()
                    assertThat(currentPage.getByTestId("flow-diagram-modal")).hasCount(0)
                    assertThat(currentPage.getByTestId("change-stage-modal")).hasCount(0)
                    assertThat(currentPage.getByTestId("instance-details-modal")).isVisible()
                }
            }
        }

        `when`("using flow definition shortcuts and browser navigation") {
            seedRichFixture()
            val session = openRecordedContext("it-supports-flow-shortcuts-and-bookmarks")
            val page = session.page

            navigateToCockpit(page)
            page.navigate(cockpitUrl("tab=long-running&lrStatus=WaitingForEvent"))
            page.goBack()

            page.getByTestId("flow-long-running-order-confirmation").click()
            val longRunningStatusAfterShortcut = page.getByTestId("long-running-status-filter").inputValue()
            page.goBack()
            page.getByTestId("flow-incomplete-order-confirmation").click()
            page.goBack()
            page.getByTestId("flow-stage-order-confirmation-WaitingForConfirmation").click()
            page.goBack()
            page.getByTestId("flow-stage-errors-order-confirmation-InformingCustomer").click()

            val bookmarkUrl = page.url()
            page.navigate(bookmarkUrl)

            then("it supports long inactive, incomplete, stage and error jumps with bookmarkable URLs") {
                verifyRecordedContext(session) { currentPage ->
                    longRunningStatusAfterShortcut shouldBe "default"
                    assertThat(currentPage.getByTestId("errors-flow-filter")).hasValue(ORDER_CONFIRMATION_FLOW_ID)
                    assertThat(currentPage.getByTestId("errors-stage-filter")).hasValue(OrderConfirmationStage.InformingCustomer.name)
                    bookmarkUrl.shouldContain("tab=errors")
                    bookmarkUrl.shouldContain("errorFlow=order-confirmation")
                    bookmarkUrl.shouldContain("errorStage=InformingCustomer")
                }
            }
        }

        `when`("opening the errors view without failures") {
            seedEmptyFixture()
            val session = openRecordedContext("it-shows-empty-errors-state")
            val page = session.page

            navigateToCockpit(page, "tab=errors")

            then("it shows the empty state") {
                verifyRecordedContext(session) { currentPage ->
                    assertThat(currentPage.getByTestId("errors-empty")).isVisible()
                }
            }
        }

        `when`("filtering and inspecting errors") {
            val fixture = seedRichFixture()
            val session = openRecordedContext("it-filters-errors-and-expands-stack-traces")
            val page = session.page
            var instanceErrorStackTrace = ""
            var historyErrorStackTrace = ""

            navigateToCockpit(page, "tab=errors")

            page.getByTestId("errors-flow-filter").selectOption(EMPLOYEE_ONBOARDING_FLOW_ID)
            page.getByTestId("errors-flow-filter").selectOption("all")
            page.getByTestId("errors-stage-filter").fill(OrderConfirmationStage.InformingCustomer.name)
            page.getByTestId("errors-stage-filter").fill("")
            page.getByTestId("errors-message-filter").fill("hr sync")
            page.getByTestId("errors-clear-filters").click()
            page.getByTestId("error-group-select-all-order-confirmation-InformingCustomer").click()
            page.getByTestId("error-group-select-all-employee-onboarding-UpdateHRSystem").click()
            page.getByTestId("error-group-deselect-all-order-confirmation-InformingCustomer").click()
            page.getByTestId("errors-deselect-selected").click()
            page.getByTestId("error-instance-${fixture.orderErrorRetryId}").click()
            page.getByTestId("instance-error-stacktrace-toggle").click()
            instanceErrorStackTrace = page.getByTestId("instance-error-stacktrace").textContent() ?: ""
            page.getByTestId("instance-history-stacktrace-toggle-1").click()
            historyErrorStackTrace = page.getByTestId("instance-history-stacktrace-1").textContent() ?: ""
            page.getByTestId("instance-details-close").click()

            then("it filters groups, expands stack traces, and supports selecting and deselecting") {
                verifyRecordedContext(session) { currentPage ->
                    assertThat(currentPage.getByTestId("errors-flow-filter")).hasValue("all")
                    assertThat(currentPage.getByTestId("errors-stage-filter")).hasValue("")
                    assertThat(currentPage.getByTestId("errors-message-filter")).hasValue("")
                    assertThat(currentPage.getByTestId("error-group-order-confirmation-InformingCustomer")).isVisible()
                    assertThat(currentPage.getByTestId("error-group-employee-onboarding-UpdateHRSystem")).isVisible()
                    instanceErrorStackTrace.shouldContain("notification retry needed")
                    historyErrorStackTrace.shouldContain("Notify.retry")
                    assertThat(currentPage.getByTestId("instance-details-modal")).hasCount(0)
                }
            }
        }

        `when`("performing error actions") {
            val fixture = seedRichFixture()
            val session = openRecordedContext("it-performs-error-actions")
            val page = session.page
            var changedStageText = ""

            navigateToCockpit(page, "tab=errors")

            page.getByTestId("error-instance-checkbox-${fixture.orderErrorRetryId}").check()
            page.getByTestId("errors-retry-selected").click()
            page.getByTestId("action-confirmation-confirm").click()

            page.getByTestId("error-instance-checkbox-${fixture.orderErrorChangeStageId}").check()
            page.getByTestId("errors-change-stage-selected").click()
            page.getByTestId("change-stage-select").selectOption(OrderConfirmationStage.WaitingForConfirmation.name)
            page.getByTestId("change-stage-confirm").click()
            page.getByTestId("action-confirmation-confirm").click()

            page.getByTestId("error-instance-checkbox-${fixture.employeeErrorCancelId}").check()
            page.getByTestId("errors-cancel-selected").click()
            page.getByTestId("action-confirmation-confirm").click()

            page.getByTestId("tab-instances").click()
            page.getByTestId("instances-search").fill(fixture.orderErrorChangeStageId.toString())
            instanceRow(page, fixture.orderErrorChangeStageId).click()
            changedStageText = page.getByTestId("instance-details-stage").textContent() ?: ""
            page.getByTestId("instance-details-close").click()

            page.getByTestId("instances-search").fill(fixture.employeeErrorCancelId.toString())
            instanceRow(page, fixture.employeeErrorCancelId).click()

            then("it retries, changes stage, and cancels selected instances") {
                verifyRecordedContext(session) { currentPage ->
                    changedStageText.shouldContain(OrderConfirmationStage.WaitingForConfirmation.name)
                    assertThat(currentPage.getByTestId("instance-details-status")).containsText(StageStatus.Cancelled.name)
                }
            }
        }

        `when`("working with long inactive instances") {
            val fixture = seedRichFixture()
            val session = openRecordedContext("it-filters-and-retries-long-running-instances")
            val page = session.page

            navigateToCockpit(page, "tab=long-running")

            page.getByTestId("long-running-flow-filter").selectOption(ORDER_CONFIRMATION_FLOW_ID)
            page.getByTestId("long-running-flow-filter").selectOption("all")
            page.getByTestId("long-running-threshold").fill("3h 1m")
            page.getByTestId("long-running-threshold").fill("30s")
            page.getByTestId("long-running-status-filter").selectOption("WaitingForEvent")
            page.getByTestId("long-running-status-filter").selectOption("Running")
            page.getByTestId("long-running-status-filter").selectOption("default")
            page.getByTestId("long-running-flow-filter").selectOption(ORDER_CONFIRMATION_FLOW_ID)
            page.getByTestId("long-running-checkbox-${fixture.orderLongRunningId}").check()
            page.getByTestId("long-running-deselect-selected").click()
            page.getByTestId("long-running-checkbox-${fixture.orderLongRunningId}").check()
            page.getByTestId("long-running-retry-selected").click()
            page.getByTestId("action-confirmation-confirm").click()
            page.getByTestId("tab-instances").click()
            page.getByTestId("instances-search").fill(fixture.orderLongRunningId.toString())

            then("it filters by flow, cockpit status, and threshold while excluding event and timer waits by default") {
                verifyRecordedContext(session) { currentPage ->
                    assertThat(currentPage.getByTestId("long-running-selection-bar")).hasCount(0)
                    assertThat(instanceRow(currentPage, fixture.orderLongRunningId)).isVisible()
                    assertThat(currentPage.getByTestId("instance-status-${fixture.orderLongRunningId}")).containsText("Waiting for event")
                }
            }
        }

        `when`("updating instances from the detail modal") {
            val fixture = seedRichFixture()
            val session = openRecordedContext("it-refreshes-detail-modal-after-actions")
            val page = session.page

            navigateToCockpit(page, "tab=errors")

            page.getByTestId("error-instance-${fixture.orderErrorChangeStageId}").click()
            page.getByTestId("instance-change-stage").click()
            page.getByTestId("change-stage-select").selectOption(OrderConfirmationStage.WaitingForConfirmation.name)
            page.getByTestId("change-stage-confirm").click()
            page.getByTestId("action-confirmation-confirm").click()
            page.getByTestId("instance-details-close").click()
            assertThat(page.getByTestId("instance-details-modal")).hasCount(0)

            assertThat(page.getByTestId("error-instance-${fixture.employeeErrorCancelId}")).isVisible()
            page.getByTestId("error-instance-${fixture.employeeErrorCancelId}").click()
            page.getByTestId("instance-cancel").click()
            page.getByTestId("action-confirmation-confirm").click()

            then("it refreshes the modal stage, status, and history after change-stage and cancel actions") {
                verifyRecordedContext(session) { currentPage ->
                    assertThat(currentPage.getByTestId("instance-details-modal")).isVisible()
                    assertThat(currentPage.getByTestId("instance-details-status")).containsText(StageStatus.Cancelled.name)
                    assertThat(currentPage.getByTestId("instance-history-type-2")).containsText("Cancelled")
                }
            }
        }

        `when`("filtering instances") {
            val fixture = seedRichFixture()
            val session = openRecordedContext("it-filters-instances-and-clears-filters")
            val page = session.page

            navigateToCockpit(page, "tab=instances")

            page.getByTestId("instances-search").fill(EMPLOYEE_ONBOARDING_FLOW_ID)
            page.getByTestId("instances-search").fill(fixture.orderPendingId.toString())
            page.getByTestId("instances-clear-filters").click()
            page.getByTestId("instances-stage-filter").fill(OrderConfirmationStage.WaitingForConfirmation.name)
            page.getByTestId("instances-clear-filters").click()
            page.getByTestId("instances-error-filter").fill("hr sync")
            page.getByTestId("instances-clear-filters").click()
            page.getByTestId("instances-status-filter").selectOption(StageStatus.Cancelled.name)
            page.getByTestId("instances-clear-filters").click()
            page.getByTestId("instances-search").fill(fixture.employeeCompletedId.toString())
            instanceRow(page, fixture.employeeCompletedId).click()

            then("it supports search, stage, error, status, and clear filters") {
                verifyRecordedContext(session) { currentPage ->
                    val timestampText = currentPage.getByTestId("instance-history-timestamp-1").textContent() ?: ""
                    timestampText.shouldContain("GMT")
                    timestampText.shouldNotContain("UTC")
                    assertThat(currentPage.getByTestId("instance-history-type-1")).containsText(HistoryEntryType.StatusChanged.name)
                    assertThat(currentPage.getByTestId("instance-history-stage-1")).containsText("—")
                }
            }
        }
    }
})