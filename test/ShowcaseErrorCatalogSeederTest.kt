package io.flowlite.test

import io.flowlite.FlowLiteInstanceSummaryRepository
import io.flowlite.RetryStateStore
import io.flowlite.SpringDataJdbcHistoryStore
import io.flowlite.cockpit.CockpitErrorFilter
import io.flowlite.cockpit.CockpitInstanceBucket
import io.flowlite.cockpit.CockpitService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.getBean

class ShowcaseErrorCatalogSeederTest : BehaviorSpec({
    val context = startTestApplication()
    val service = context.getBean<CockpitService>()
    val summaryRepo = context.getBean<FlowLiteInstanceSummaryRepository>()
    val historyStore = context.getBean<SpringDataJdbcHistoryStore>()
    val retryStateStore = context.getBean<RetryStateStore>()
    val orderRepo = context.getBean<OrderConfirmationRepository>()
    val employeeRepo = context.getBean<EmployeeOnboardingRepository>()
    val clock = context.getBean<AdjustableClock>()

    afterSpec {
        context.close()
    }

    given("showcase error catalog seeding") {
        `when`("the catalog seeder runs") {
            summaryRepo.deleteAll()
            context.getBean<io.flowlite.FlowLiteHistoryRepository>().deleteAll()

            ShowcaseErrorCatalogSeeder(
                historyStore = historyStore,
                retryStateStore = retryStateStore,
                orderRepo = orderRepo,
                employeeRepo = employeeRepo,
                enabled = true,
                clock = clock,
            )

            then("cockpit exposes one example for each error category") {
                service.listInstances(
                    bucket = CockpitInstanceBucket.Error,
                    errorFilter = CockpitErrorFilter.Final,
                ).map { it.flowInstanceId }.sortedBy { it.toString() } shouldContainExactly listOf(
                    java.util.UUID.nameUUIDFromBytes("showcase-error-final".toByteArray()),
                ).sortedBy { it.toString() }

                service.listInstances(
                    bucket = CockpitInstanceBucket.Error,
                    errorFilter = CockpitErrorFilter.ExternalRetry,
                ).map { it.flowInstanceId }.sortedBy { it.toString() } shouldContainExactly listOf(
                    java.util.UUID.nameUUIDFromBytes("showcase-error-external-retry".toByteArray()),
                    java.util.UUID.nameUUIDFromBytes("showcase-error-auto-retry".toByteArray()),
                ).sortedBy { it.toString() }

                service.listInstances(
                    bucket = CockpitInstanceBucket.Error,
                    errorFilter = CockpitErrorFilter.AutoRetryActive,
                ).map { it.flowInstanceId }.sortedBy { it.toString() } shouldContainExactly listOf(
                    java.util.UUID.nameUUIDFromBytes("showcase-error-auto-retry".toByteArray()),
                ).sortedBy { it.toString() }
            }

            then("flow cards still count only final errors") {
                val flows = service.listFlows()
                flows.first { it.flowId == EMPLOYEE_ONBOARDING_FLOW_ID }.errorCount shouldBe 1
                flows.first { it.flowId == ORDER_CONFIRMATION_FLOW_ID }.errorCount shouldBe 0
            }
        }
    }
})