package io.flowlite.test

import io.flowlite.api.FlowEngine
import io.flowlite.api.ProcessData
import io.flowlite.api.StageStatus
import io.flowlite.api.StatePersister
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FlowEngineE2ETest : BehaviorSpec({
    given("order confirmation flow") {
        val engine = FlowEngine()
        val persister = InMemoryStatePersister<OrderConfirmation>()
        engine.registerFlow("order-confirmation", createOrderConfirmationFlow(), persister)

        `when`("processing digital confirmation path") {
            val processId = engine.startProcess(
                flowId = "order-confirmation",
                initialState = OrderConfirmation(
                    processId = "p-1",
                    stage = OrderConfirmationStage.WaitingForConfirmation,
                    orderNumber = "ORD-1",
                    confirmationType = ConfirmationType.DIGITAL,
                    customerName = "Alice",
                ),
            )

            then("it waits for confirmation event") {
                engine.getStatus("order-confirmation", processId) shouldBe
                    (OrderConfirmationStage.WaitingForConfirmation to StageStatus.PENDING)
            }

            then("it completes after digital confirmation event") {
                engine.sendEvent("order-confirmation", processId, OrderConfirmationEvent.ConfirmedDigitally)
                engine.getStatus("order-confirmation", processId) shouldBe
                    (OrderConfirmationStage.InformingCustomer to StageStatus.COMPLETED)
            }
        }

        `when`("processing physical confirmation path") {
            val processId = engine.startProcess(
                flowId = "order-confirmation",
                initialState = OrderConfirmation(
                    processId = "p-2",
                    stage = OrderConfirmationStage.WaitingForConfirmation,
                    orderNumber = "ORD-2",
                    confirmationType = ConfirmationType.PHYSICAL,
                    customerName = "Bob",
                ),
            )

            engine.sendEvent("order-confirmation", processId, OrderConfirmationEvent.ConfirmedPhysically)

            then("it informs customer and completes") {
                engine.getStatus("order-confirmation", processId) shouldBe
                    (OrderConfirmationStage.InformingCustomer to StageStatus.COMPLETED)
            }
        }
    }

    given("employee onboarding flow - manual path") {
        val engine = FlowEngine()
        val persister = InMemoryStatePersister<EmployeeOnboarding>()
        engine.registerFlow("employee-onboarding", createEmployeeOnboardingFlow(), persister)

        val processId = engine.startProcess(
            flowId = "employee-onboarding",
            initialState = EmployeeOnboarding(
                processId = "emp-1",
                isOnboardingAutomated = false,
                isExecutiveRole = false,
                isSecurityClearanceRequired = false,
                isFullOnboardingRequired = false,
            ),
        )

        then("it starts at waiting for contract signature") {
            engine.getStatus("employee-onboarding", processId) shouldBe
                (EmployeeStage.WaitingForContractSigned to StageStatus.PENDING)
        }

        `when`("contract is signed and onboarding completes") {
            engine.sendEvent("employee-onboarding", processId, EmployeeEvent.ContractSigned)
            engine.sendEvent("employee-onboarding", processId, EmployeeEvent.OnboardingComplete)

            then("it finishes in HR system update stage") {
                engine.getStatus("employee-onboarding", processId) shouldBe
                    (EmployeeStage.UpdateStatusInHRSystem to StageStatus.COMPLETED)
            }
        }
    }
})

class InMemoryStatePersister<T : Any> : StatePersister<T> {
    private val data = mutableMapOf<java.util.UUID, ProcessData<T>>()

    override fun save(processData: ProcessData<T>): Boolean {
        data[processData.flowInstanceId] = processData
        return true
    }

    override fun load(flowInstanceId: java.util.UUID): ProcessData<T>? = data[flowInstanceId]
}