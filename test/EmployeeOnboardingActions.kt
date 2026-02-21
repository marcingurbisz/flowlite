package io.flowlite.test

import java.util.concurrent.TimeUnit
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class EmployeeOnboardingActions(
    private val repo: EmployeeOnboardingRepository,
) {
    fun createUserInSystem(employee: EmployeeOnboarding): EmployeeOnboarding {
        log.info { "Creating user account in system" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }

        EmployeeOnboardingTestHooks.get(id)?.createUserInSystemHooks?.let { hooks ->
            hooks.entered.countDown()
            require(hooks.allowProceedToSave.await(2, TimeUnit.SECONDS)) { "Timed out waiting for allowProceedToSave" }
        }

        val savedEntity = repo.saveWithOptimisticLockRetry(
            id = id,
            initial = employee,
        ) { latest ->
            latest.copy(userCreatedInSystem = true)
        }

        EmployeeOnboardingTestHooks.get(id)?.createUserInSystemHooks?.let { hooks ->
            hooks.saved.countDown()
            require(hooks.allowReturnAfterSave.await(2, TimeUnit.SECONDS)) { "Timed out waiting for allowReturnAfterSave" }
        }

        return savedEntity
    }

    fun activateEmployee(employee: EmployeeOnboarding): EmployeeOnboarding {
        log.info { "Activating employee account" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            initial = employee,
        ) { latest ->
            latest.copy(employeeActivated = true)
        }
    }

    fun updateSecurityClearanceLevels(employee: EmployeeOnboarding): EmployeeOnboarding {
        log.info { "Updating security clearance levels" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            initial = employee,
        ) { latest ->
            latest.copy(securityClearanceUpdated = true)
        }
    }

    fun setDepartmentAccess(employee: EmployeeOnboarding): EmployeeOnboarding {
        log.info { "Setting department access permissions" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            initial = employee,
        ) { latest ->
            latest.copy(departmentAccessSet = true)
        }
    }

    fun generateEmployeeDocuments(employee: EmployeeOnboarding): EmployeeOnboarding {
        log.info { "Generating employee documents" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            initial = employee,
        ) { latest ->
            latest.copy(documentsGenerated = true)
        }
    }

    fun sendContractForSigning(employee: EmployeeOnboarding): EmployeeOnboarding {
        log.info { "Sending contract for signing" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            initial = employee,
        ) { latest ->
            latest.copy(contractSentForSigning = true)
        }
    }

    fun updateStatusInHRSystem(employee: EmployeeOnboarding): EmployeeOnboarding {
        log.info { "Updating status in HR system" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            initial = employee,
        ) { latest ->
            latest.copy(statusUpdatedInHR = true)
        }
    }
}

object EmployeeOnboardingTestHooks {
    private val actionHooksByInstanceId = ConcurrentHashMap<UUID, EmployeeOnboardingActionHooks>()

    fun set(instanceId: UUID, hooks: EmployeeOnboardingActionHooks) {
        actionHooksByInstanceId[instanceId] = hooks
    }

    fun get(instanceId: UUID): EmployeeOnboardingActionHooks? = actionHooksByInstanceId[instanceId]

    fun clear(instanceId: UUID) {
        actionHooksByInstanceId.remove(instanceId)
    }
}

class EmployeeOnboardingActionHooks {
    @Volatile
    var createUserInSystemHooks: CreateUserInSystemHooks? = null

    class CreateUserInSystemHooks(
        val entered: java.util.concurrent.CountDownLatch,
        val allowProceedToSave: java.util.concurrent.CountDownLatch,
        val saved: java.util.concurrent.CountDownLatch,
        val allowReturnAfterSave: java.util.concurrent.CountDownLatch,
    )
}

private val log = KotlinLogging.logger {}
