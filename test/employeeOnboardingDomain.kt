package io.flowlite.test

import io.flowlite.ActionContext
import io.flowlite.Event
import io.flowlite.InstanceData
import io.flowlite.Stage
import io.flowlite.StageStatus
import io.flowlite.StatePersister
import io.flowlite.flow
import io.flowlite.test.EmployeeEvent.ComplianceComplete
import io.flowlite.test.EmployeeEvent.ContractSigned
import io.flowlite.test.EmployeeEvent.ManualApproval
import io.flowlite.test.EmployeeEvent.OnboardingAgreementSigned
import io.flowlite.test.EmployeeStage.ActivateSpecializedAccess
import io.flowlite.test.EmployeeStage.ActivateSystemAccess
import io.flowlite.test.EmployeeStage.CompleteOnboarding
import io.flowlite.test.EmployeeStage.CreateAccountsInExternalSystems
import io.flowlite.test.EmployeeStage.CreateEmployeeProfile
import io.flowlite.test.EmployeeStage.Delay5Min
import io.flowlite.test.EmployeeStage.DelayAfterHRUpdate
import io.flowlite.test.EmployeeStage.FetchEmployeeRecords
import io.flowlite.test.EmployeeStage.GenerateOnboardingDocuments
import io.flowlite.test.EmployeeStage.LinkToOrganizationChart
import io.flowlite.test.EmployeeStage.RemoveFromSigningQueue
import io.flowlite.test.EmployeeStage.SendContractForSigning
import io.flowlite.test.EmployeeStage.SetSecurityClearanceLevels
import io.flowlite.test.EmployeeStage.UpdateBenefitsEnrollment
import io.flowlite.test.EmployeeStage.UpdateDepartmentAssignment
import io.flowlite.test.EmployeeStage.UpdateHRSystem
import io.flowlite.test.EmployeeStage.UpdateStatusInPayroll
import io.flowlite.test.EmployeeStage.WaitForITBusinessHours
import io.flowlite.test.EmployeeStage.WaitingForComplianceComplete
import io.flowlite.test.EmployeeStage.WaitingForContractSigned
import io.flowlite.test.EmployeeStage.WaitingForManualApproval
import io.flowlite.test.EmployeeStage.WaitingForOnboardingAgreementSigned
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.repository.CrudRepository

const val EMPLOYEE_ONBOARDING_FLOW_ID = "employee-onboarding"

enum class EmployeeStage : Stage {
    CreateEmployeeProfile,
    ActivateSystemAccess,
    WaitForITBusinessHours,
    CreateAccountsInExternalSystems,
    UpdateBenefitsEnrollment,
    SetSecurityClearanceLevels,
    GenerateOnboardingDocuments,
    SendContractForSigning,
    WaitingForContractSigned,
    RemoveFromSigningQueue,
    WaitingForOnboardingAgreementSigned,
    Delay5Min,
    ActivateSpecializedAccess,
    WaitingForComplianceComplete,
    UpdateHRSystem,
    DelayAfterHRUpdate,
    WaitingForManualApproval,
    FetchEmployeeRecords,
    UpdateDepartmentAssignment,
    LinkToOrganizationChart,
    UpdateStatusInPayroll,
    CompleteOnboarding,
}

enum class EmployeeEvent : Event {
    ContractSigned,
    OnboardingAgreementSigned,
    ComplianceComplete,
    ManualApproval,
}

data class EmployeeOnboarding(
    @Id
    val id: UUID? = null,
    @Version
    val version: Long = 0,
    val stage: EmployeeStage,
    val stageStatus: StageStatus = StageStatus.Pending,
    val isOnboardingAutomated: Boolean = false,
    val needsTrainingProgram: Boolean = false,
    val isEngineeringRole: Boolean = false,
    val isFullSecuritySetup: Boolean = false,
    val wereDocumentsSignedPhysically: Boolean = false,
    val isNotManualPath: Boolean = true,
    val isExecutiveOrManagement: Boolean = false,
    val hasComplianceChecks: Boolean = false,
    val isNotContractor: Boolean = true,
    val isRemoteEmployee: Boolean = false,
    val isManagerOrDirectorRole: Boolean = false,
    val isShowcaseInstance: Boolean = false,
    val employeeProfileCreated: Boolean = false,
    val systemAccessActivated: Boolean = false,
    val itBusinessHoursResolved: Boolean = false,
    val externalAccountsCreated: Boolean = false,
    val benefitsEnrollmentUpdated: Boolean = false,
    val securityClearanceLevelsSet: Boolean = false,
    val documentsGenerated: Boolean = false,
    val contractSentForSigning: Boolean = false,
    val removedFromSigningQueue: Boolean = false,
    val delay5MinCompleted: Boolean = false,
    val specializedAccessActivated: Boolean = false,
    val hrUpdated: Boolean = false,
    val delayAfterHrUpdateCompleted: Boolean = false,
    val employeeRecordsFetched: Boolean = false,
    val departmentAssignmentUpdated: Boolean = false,
    val organizationChartLinked: Boolean = false,
    val payrollStatusUpdated: Boolean = false,
    val onboardingCompleted: Boolean = false,
)

fun createEmployeeOnboardingFlow(actions: EmployeeOnboardingActions) = // FLOW-DEFINITION-START
    flow<EmployeeOnboarding, EmployeeStage, EmployeeEvent> {
        _if(::isOnboardingAutomated) {
            stage(CreateEmployeeProfile, actions::createEmployeeProfile)

            _if(::needsTrainingProgram) {
                _if(::isEngineeringRole) {
                    stage(ActivateSystemAccess, actions::activateSystemAccess)
                    timer(WaitForITBusinessHours, actions::effectiveITWorkingDateTime)
                    stage(CreateAccountsInExternalSystems, actions::createAccountsInExternalSystems)
                    stage(UpdateBenefitsEnrollment, actions::updateBenefitsEnrollment)
                } _else {
                    _if(::isFullSecuritySetup) {
                        stage(SetSecurityClearanceLevels, actions::setSecurityClearanceLevels)
                    }
                }

                stage(GenerateOnboardingDocuments, actions::generateOnboardingDocuments)
                stage(SendContractForSigning, actions::sendContractForSigning)
                stage(WaitingForContractSigned, waitFor = ContractSigned)

                _if(::wereDocumentsSignedPhysically) {
                    stage(RemoveFromSigningQueue, actions::removeFromSigningQueue)
                }
            }
        }

        stage(WaitingForOnboardingAgreementSigned, waitFor = OnboardingAgreementSigned)
        timer(Delay5Min, actions::delay5Min)

        _if(::isNotManualPath) {
            _if(::isExecutiveOrManagement) {
                stage(ActivateSpecializedAccess, actions::activateSpecializedAccess)
            } _else {
                _if(::hasComplianceChecks) {
                    stage(WaitingForComplianceComplete, waitFor = ComplianceComplete)
                }
            }

            stage(UpdateHRSystem, actions::updateHRSystem)
            timer(DelayAfterHRUpdate, actions::delayAfterHRUpdate)
        } _else {
            stage(WaitingForManualApproval, waitFor = ManualApproval)
            stage(FetchEmployeeRecords, actions::fetchEmployeeRecords)
        }

        _if(::isNotContractor) {
            stage(UpdateDepartmentAssignment, actions::updateDepartmentAssignment)
            stage(LinkToOrganizationChart, actions::linkToOrganizationChart)
        }

        stage(UpdateStatusInPayroll, actions::updateStatusInPayroll)
        stage(CompleteOnboarding, actions::completeOnboarding)
    }
// FLOW-DEFINITION-END

private fun isOnboardingAutomated(employee: EmployeeOnboarding) = employee.isOnboardingAutomated

private fun needsTrainingProgram(employee: EmployeeOnboarding) = employee.needsTrainingProgram

private fun isEngineeringRole(employee: EmployeeOnboarding) = employee.isEngineeringRole

private fun isFullSecuritySetup(employee: EmployeeOnboarding) = employee.isFullSecuritySetup

private fun wereDocumentsSignedPhysically(employee: EmployeeOnboarding) = employee.wereDocumentsSignedPhysically

private fun isNotManualPath(employee: EmployeeOnboarding) = employee.isNotManualPath

private fun isExecutiveOrManagement(employee: EmployeeOnboarding) = employee.isExecutiveOrManagement

private fun hasComplianceChecks(employee: EmployeeOnboarding) = employee.hasComplianceChecks

private fun isNotContractor(employee: EmployeeOnboarding) = employee.isNotContractor

class EmployeeOnboardingActions(
    private val repo: EmployeeOnboardingRepository,
) {
    fun createEmployeeProfile(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding {
        ShowcaseActionBehavior.apply("createEmployeeProfile", employee.isShowcaseInstance)
        employeeOnboardingLog.info { "Creating employee profile" }
        val id = context.flowInstanceId

        EmployeeOnboardingTestHooks.get(id)?.createEmployeeProfileHooks?.let { hooks ->
            hooks.entered.countDown()
            require(hooks.allowProceedToSave.await(2, TimeUnit.SECONDS)) { "Timed out waiting for allowProceedToSave" }
        }

        val savedEntity = repo.saveWithOptimisticLockRetry(
            id = id,
            initial = employee,
        ) { latest ->
            latest.copy(employeeProfileCreated = true)
        }

        EmployeeOnboardingTestHooks.get(id)?.createEmployeeProfileHooks?.let { hooks ->
            hooks.saved.countDown()
            require(hooks.allowReturnAfterSave.await(2, TimeUnit.SECONDS)) { "Timed out waiting for allowReturnAfterSave" }
        }

        return savedEntity
    }

    fun activateSystemAccess(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "activateSystemAccess", "Activating system access") {
            it.copy(systemAccessActivated = true)
        }

    fun effectiveITWorkingDateTime(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "effectiveITWorkingDateTime", "Resolving effective IT working date time") {
            it.copy(itBusinessHoursResolved = true)
        }

    fun createAccountsInExternalSystems(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "createAccountsInExternalSystems", "Creating accounts in external systems") {
            it.copy(externalAccountsCreated = true)
        }

    fun updateBenefitsEnrollment(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "updateBenefitsEnrollment", "Updating benefits enrollment") {
            it.copy(benefitsEnrollmentUpdated = true)
        }

    fun setSecurityClearanceLevels(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "setSecurityClearanceLevels", "Setting security clearance levels") {
            it.copy(securityClearanceLevelsSet = true)
        }

    fun generateOnboardingDocuments(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "generateOnboardingDocuments", "Generating onboarding documents") {
            it.copy(documentsGenerated = true)
        }

    fun sendContractForSigning(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "sendContractForSigning", "Sending contract for signing") {
            it.copy(contractSentForSigning = true)
        }

    fun removeFromSigningQueue(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "removeFromSigningQueue", "Removing employee from signing queue") {
            it.copy(removedFromSigningQueue = true)
        }

    fun delay5Min(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "delay5Min", "Recording five-minute delay marker") {
            it.copy(delay5MinCompleted = true)
        }

    fun activateSpecializedAccess(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "activateSpecializedAccess", "Activating specialized access") {
            it.copy(specializedAccessActivated = true)
        }

    fun updateHRSystem(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "updateHRSystem", "Updating HR system") {
            it.copy(hrUpdated = true)
        }

    fun delayAfterHRUpdate(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "delayAfterHRUpdate", "Recording post-HR-update delay marker") {
            it.copy(delayAfterHrUpdateCompleted = true)
        }

    fun fetchEmployeeRecords(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "fetchEmployeeRecords", "Fetching employee records") {
            it.copy(employeeRecordsFetched = true)
        }

    fun updateDepartmentAssignment(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "updateDepartmentAssignment", "Updating department assignment") {
            it.copy(departmentAssignmentUpdated = true)
        }

    fun linkToOrganizationChart(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "linkToOrganizationChart", "Linking employee to organization chart") {
            it.copy(organizationChartLinked = true)
        }

    fun updateStatusInPayroll(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "updateStatusInPayroll", "Updating status in payroll") {
            it.copy(payrollStatusUpdated = true)
        }

    fun completeOnboarding(context: ActionContext, employee: EmployeeOnboarding): EmployeeOnboarding =
        saveProgress(context, employee, "completeOnboarding", "Completing onboarding") {
            it.copy(onboardingCompleted = true)
        }

    private fun saveProgress(
        context: ActionContext,
        employee: EmployeeOnboarding,
        actionName: String,
        message: String,
        transform: (EmployeeOnboarding) -> EmployeeOnboarding,
    ): EmployeeOnboarding {
        ShowcaseActionBehavior.apply(actionName, employee.isShowcaseInstance)
        employeeOnboardingLog.info { message }
        val id = context.flowInstanceId
        return repo.saveWithOptimisticLockRetry(
            id = id,
            initial = employee,
        ) { latest ->
            transform(latest)
        }
    }
}

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
            initial = instanceData.state.copy(
                id = instanceData.flowInstanceId,
                stage = stage,
                stageStatus = instanceData.stageStatus,
            ),
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

object EmployeeOnboardingTestHooks {
    private val actionHooksByInstanceId = ConcurrentHashMap<UUID, EmployeeOnboardingActionHooks>()

    fun set(instanceId: UUID, hooks: EmployeeOnboardingActionHooks) {
        actionHooksByInstanceId[instanceId] = hooks
    }

    fun get(instanceId: UUID) = actionHooksByInstanceId[instanceId]

    fun clear(instanceId: UUID) {
        actionHooksByInstanceId.remove(instanceId)
    }
}

class EmployeeOnboardingActionHooks {
    @Volatile
    var createEmployeeProfileHooks: CreateEmployeeProfileHooks? = null

    class CreateEmployeeProfileHooks(
        val entered: java.util.concurrent.CountDownLatch,
        val allowProceedToSave: java.util.concurrent.CountDownLatch,
        val saved: java.util.concurrent.CountDownLatch,
        val allowReturnAfterSave: java.util.concurrent.CountDownLatch,
    )
}

private val employeeOnboardingLog = KotlinLogging.logger {}
