package io.flowlite.test

import io.flowlite.*
import io.flowlite.test.EmployeeEvent.*
import io.flowlite.test.EmployeeStage.*
import io.kotest.core.spec.style.BehaviorSpec
import org.springframework.dao.OptimisticLockingFailureException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.repository.CrudRepository
import java.util.UUID

const val EMPLOYEE_ONBOARDING_FLOW_ID = "employee-onboarding"

class EmployeeOnboardingFlowTest : BehaviorSpec({
    extension(TestApplicationExtension)

    val engine = TestApplicationExtension.engine
    val repo = TestApplicationExtension.employeeOnboardingRepository

    given("an employee onboarding flow") {
        `when`("generating a mermaid diagram") {
            val flow = createEmployeeOnboardingFlow(TestApplicationExtension.employeeOnboardingActions)
            val generator = MermaidGenerator()
            val diagram = generator.generateDiagram("employee-onboarding-flow", flow)

            then("should generate diagram successfully") {
                assert(diagram.isNotEmpty())
                assert(diagram.contains("stateDiagram-v2"))
            }
        }
    }

    given("employee onboarding flow - manual path") {
        val flowInstanceId = engine.startInstance(
            flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
            initialState = EmployeeOnboarding(
                stage = WaitingForContractSigned,
                isOnboardingAutomated = false,
                isExecutiveRole = false,
                isSecurityClearanceRequired = false,
                isFullOnboardingRequired = false,
            ),
        )

        then("it starts at waiting for contract signature") {
            awaitStatus(
                fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, flowInstanceId) },
                expected = WaitingForContractSigned to StageStatus.PENDING,
            )
        }

        `when`("contract is signed and onboarding completes") {
            engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, flowInstanceId, ContractSigned)
            engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, flowInstanceId, OnboardingComplete)

            then("it finishes in HR system update stage") {
                awaitStatus(
                    fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, flowInstanceId) },
                    expected = UpdateStatusInHRSystem to StageStatus.COMPLETED,
                )
            }
        }
    }

    given("employee onboarding persister - optimistic locking with external updates") {
        fun awaitLatch(latch: CountDownLatch, name: String) {
            require(latch.await(2, TimeUnit.SECONDS)) { "Timed out waiting for $name" }
        }

        `when`("an external update happens during action execution") {
            val id = UUID.randomUUID()

            val entered = CountDownLatch(1)
            val allowProceedToSave = CountDownLatch(1)
            val saved = CountDownLatch(1)
            val allowReturnAfterSave = CountDownLatch(0)
            EmployeeOnboardingTestHooks.set(
                id,
                EmployeeOnboardingActionHooks().apply {
                    createUserInSystemHooks = EmployeeOnboardingActionHooks.CreateUserInSystemHooks(
                        entered = entered,
                        allowProceedToSave = allowProceedToSave,
                        saved = saved,
                        allowReturnAfterSave = allowReturnAfterSave,
                    )
                },
            )

            repo.save(
                EmployeeOnboarding(
                    id = id,
                    stage = CreateUserInSystem,
                    stageStatus = StageStatus.PENDING,
                    isOnboardingAutomated = true,
                    isExecutiveRole = true,
                    isSecurityClearanceRequired = false,
                ),
            )

            // Can be sent early; mailbox semantics will deliver later.
            engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, id, ContractSigned)
            engine.startInstance(EMPLOYEE_ONBOARDING_FLOW_ID, id)

            then("persister merges engine progress with external business updates") {
                try {
                    awaitLatch(entered, "entered")

                    val external = repo.findById(id).orElseThrow()
                    repo.save(external.copy(isRemoteEmployee = true))

                    allowProceedToSave.countDown()
                    awaitLatch(saved, "saved")

                    awaitStatus(
                        fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, id) },
                        expected = UpdateStatusInHRSystem to StageStatus.COMPLETED,
                    )

                    val final = repo.findById(id).orElseThrow()
                    require(final.userCreatedInSystem)
                    require(final.isRemoteEmployee)
                } finally {
                    EmployeeOnboardingTestHooks.clear(id)
                }
            }
        }

        `when`("an external update happens after action saves but before it returns") {
            val id = UUID.randomUUID()

            val entered = CountDownLatch(1)
            val allowProceedToSave = CountDownLatch(0)
            val saved = CountDownLatch(1)
            val allowReturnAfterSave = CountDownLatch(1)
            EmployeeOnboardingTestHooks.set(
                id,
                EmployeeOnboardingActionHooks().apply {
                    createUserInSystemHooks = EmployeeOnboardingActionHooks.CreateUserInSystemHooks(
                        entered = entered,
                        allowProceedToSave = allowProceedToSave,
                        saved = saved,
                        allowReturnAfterSave = allowReturnAfterSave,
                    )
                },
            )

            repo.save(
                EmployeeOnboarding(
                    id = id,
                    stage = CreateUserInSystem,
                    stageStatus = StageStatus.PENDING,
                    isOnboardingAutomated = true,
                    isExecutiveRole = true,
                    isSecurityClearanceRequired = false,
                ),
            )

            engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, id, ContractSigned)
            engine.startInstance(EMPLOYEE_ONBOARDING_FLOW_ID, id)

            then("persister merges without losing the external update") {
                try {
                    awaitLatch(entered, "entered")
                    awaitLatch(saved, "saved")

                    val external = repo.findById(id).orElseThrow()
                    repo.save(external.copy(isManagerOrDirectorRole = true))

                    allowReturnAfterSave.countDown()

                    awaitStatus(
                        fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, id) },
                        expected = UpdateStatusInHRSystem to StageStatus.COMPLETED,
                    )

                    val final = repo.findById(id).orElseThrow()
                    require(final.userCreatedInSystem)
                    require(final.isManagerOrDirectorRole)
                } finally {
                    EmployeeOnboardingTestHooks.clear(id)
                }
            }
        }
    }
})

// --- Domain Classes ---

enum class EmployeeStage : Stage {
    CreateUserInSystem,
    ActivateStandardEmployee,
    ActivateSpecializedEmployee,
    UpdateSecurityClearanceLevels,
    SetDepartmentAccess,
    GenerateEmployeeDocuments,
    SendContractForSigning,
    WaitingForContractSigned,
    WaitingForOnboardingCompletion,
    UpdateStatusInHRSystem,
    WaitingForEmployeeDocumentsSigned,
}

enum class EmployeeEvent : Event {
    ContractSigned,
    OnboardingComplete,
    EmployeeDocumentsSigned,
}

data class EmployeeOnboarding(
    @Id
    val id: UUID? = null,
    @Version
    val version: Long = 0,
    val stage: EmployeeStage,
    val stageStatus: StageStatus = StageStatus.PENDING,
    val isOnboardingAutomated: Boolean = false,
    val isContractSigned: Boolean = false,
    val isExecutiveRole: Boolean = false,
    val isSecurityClearanceRequired: Boolean = false,
    val isFullOnboardingRequired: Boolean = false,
    val isManagerOrDirectorRole: Boolean = false,
    val isRemoteEmployee: Boolean = false,
    val userCreatedInSystem: Boolean = false,
    val employeeActivated: Boolean = false,
    val securityClearanceUpdated: Boolean = false,
    val departmentAccessSet: Boolean = false,
    val documentsGenerated: Boolean = false,
    val contractSentForSigning: Boolean = false,
    val statusUpdatedInHR: Boolean = false,
)

interface EmployeeOnboardingRepository : CrudRepository<EmployeeOnboarding, UUID>

class SpringDataEmployeeOnboardingPersister(
    private val repo: EmployeeOnboardingRepository,
) : StatePersister<EmployeeOnboarding> {
    override fun tryTransitionStageStatus(
        flowInstanceId: UUID,
        expectedStage: Stage,
        expectedStageStatus: StageStatus,
        newStageStatus: StageStatus,
    ): Boolean {
        val current = load(flowInstanceId)
        if (current.stage != expectedStage) return false
        if (current.stageStatus != expectedStageStatus) return false

        return try {
            repo.save(
                current.state.copy(
                    stageStatus = newStageStatus,
                ),
            )
            true
        } catch (_: OptimisticLockingFailureException) {
            false
        }
    }

    override fun save(instanceData: InstanceData<EmployeeOnboarding>): InstanceData<EmployeeOnboarding> {
        val stage = instanceData.stage as? EmployeeStage
            ?: error("Unexpected stage ${instanceData.stage}")

        val saved = repo.saveWithOptimisticLockRetry(
            id = instanceData.flowInstanceId,
            candidate = instanceData.state.copy(
                id = instanceData.flowInstanceId,
                stage = stage,
                stageStatus = instanceData.stageStatus,
            )
        ) { latest -> latest.copy(stage = stage, stageStatus = instanceData.stageStatus) }
        return instanceData.copy(state = saved)
    }

    override fun load(flowInstanceId: UUID): InstanceData<EmployeeOnboarding> {
        val entity = repo.findById(flowInstanceId).orElse(null)
            ?: error("Flow instance '$flowInstanceId' not found")
        return InstanceData(
            flowInstanceId = flowInstanceId,
            state = entity,
            stage = entity.stage,
            stageStatus = entity.stageStatus,
        )
    }
}

fun createEmployeeOnboardingFlow(actions: EmployeeOnboardingActions): Flow<EmployeeOnboarding> {
    val flow = // FLOW-DEFINITION-START
        FlowBuilder<EmployeeOnboarding>()
            .condition(
                predicate = { it.isOnboardingAutomated },
                description = "isOnboardingAutomated",
                onTrue = {
                    // Automated path
                    stage(CreateUserInSystem, actions::createUserInSystem)
                        .condition(
                            { it.isExecutiveRole || it.isSecurityClearanceRequired },
                            description = "isExecutiveRole || isSecurityClearanceRequired",
                            onFalse = {
                                stage(ActivateStandardEmployee, actions::activateEmployee)
                                    .stage(GenerateEmployeeDocuments, actions::generateEmployeeDocuments)
                                    .stage(SendContractForSigning, actions::sendContractForSigning)
                                    .stage(WaitingForEmployeeDocumentsSigned)
                                    .waitFor(EmployeeDocumentsSigned)
                                    .stage(WaitingForContractSigned)
                                    .waitFor(ContractSigned)
                                    .condition(
                                        { it.isExecutiveRole || it.isSecurityClearanceRequired },
                                        description = "isExecutiveRole || isSecurityClearanceRequired",
                                        onTrue = {
                                            stage(ActivateSpecializedEmployee, actions::activateEmployee)
                                                .stage(UpdateStatusInHRSystem, actions::updateStatusInHRSystem)
                                        },
                                        onFalse = {
                                            stage(WaitingForOnboardingCompletion)
                                                .waitFor(OnboardingComplete)
                                                .join(UpdateStatusInHRSystem)
                                        },
                                    )
                            },
                            onTrue = {
                                stage(UpdateSecurityClearanceLevels, actions::updateSecurityClearanceLevels)
                                    .condition(
                                        { it.isSecurityClearanceRequired },
                                        description = "isSecurityClearanceRequired",
                                        onTrue = {
                                            condition(
                                                { it.isFullOnboardingRequired },
                                                description = "isFullOnboardingRequired",
                                                onTrue = {
                                                    stage(SetDepartmentAccess, actions::setDepartmentAccess)
                                                        .join(GenerateEmployeeDocuments)
                                                },
                                                onFalse = { join(GenerateEmployeeDocuments) },
                                            )
                                        },
                                        onFalse = { join(WaitingForContractSigned) },
                                    )
                            },
                        )
                },
                onFalse = {
                    // Manual path
                    join(WaitingForContractSigned)
                },
            )
            .build()
    // FLOW-DEFINITION-END
    return flow
}

