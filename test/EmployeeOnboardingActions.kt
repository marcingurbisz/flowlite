package io.flowlite.test

import java.util.concurrent.TimeUnit
import io.github.oshai.kotlinlogging.KotlinLogging

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
            candidate = employee.copy(userCreatedInSystem = true),
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
            candidate = employee.copy(employeeActivated = true),
        ) { latest ->
            latest.copy(employeeActivated = true)
        }
    }

    fun updateSecurityClearanceLevels(employee: EmployeeOnboarding): EmployeeOnboarding {
        log.info { "Updating security clearance levels" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            candidate = employee.copy(securityClearanceUpdated = true),
        ) { latest ->
            latest.copy(securityClearanceUpdated = true)
        }
    }

    fun setDepartmentAccess(employee: EmployeeOnboarding): EmployeeOnboarding {
        log.info { "Setting department access permissions" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            candidate = employee.copy(departmentAccessSet = true),
        ) { latest ->
            latest.copy(departmentAccessSet = true)
        }
    }

    fun generateEmployeeDocuments(employee: EmployeeOnboarding): EmployeeOnboarding {
        log.info { "Generating employee documents" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            candidate = employee.copy(documentsGenerated = true),
        ) { latest ->
            latest.copy(documentsGenerated = true)
        }
    }

    fun sendContractForSigning(employee: EmployeeOnboarding): EmployeeOnboarding {
        log.info { "Sending contract for signing" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            candidate = employee.copy(contractSentForSigning = true),
        ) { latest ->
            latest.copy(contractSentForSigning = true)
        }
    }

    fun updateStatusInHRSystem(employee: EmployeeOnboarding): EmployeeOnboarding {
        log.info { "Updating status in HR system" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            candidate = employee.copy(statusUpdatedInHR = true),
        ) { latest ->
            latest.copy(statusUpdatedInHR = true)
        }
    }
}

private val log = KotlinLogging.logger {}
