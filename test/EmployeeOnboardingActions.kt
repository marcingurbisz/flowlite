package io.flowlite.test

import java.util.UUID
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

class EmployeeOnboardingActions(
    private val repo: EmployeeOnboardingRepository? = null,
) {
    fun createUserInSystem(employee: EmployeeOnboarding): EmployeeOnboarding {
        onboardingLogger.info("Creating user account in system")
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }

        EmployeeOnboardingTestHooks.get(id)?.createUserInSystemHooks?.let { hooks ->
            hooks.entered.countDown()
            require(hooks.allowProceedToSave.await(2, TimeUnit.SECONDS)) { "Timed out waiting for allowProceedToSave" }
        }

        // For diagram generation / README updater (no Spring context), we don't execute actions.
        val repository = repo ?: return employee.copy(userCreatedInSystem = true)

        val savedEntity = repository.saveWithOptimisticLockRetry(
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
        onboardingLogger.info("Activating employee account")
        val repository = repo ?: return employee.copy(employeeActivated = true)
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repository.saveWithOptimisticLockRetry(
            id = id,
            candidate = employee.copy(employeeActivated = true),
        ) { latest ->
            latest.copy(employeeActivated = true)
        }
    }

    fun updateSecurityClearanceLevels(employee: EmployeeOnboarding): EmployeeOnboarding {
        onboardingLogger.info("Updating security clearance levels")
        val repository = repo ?: return employee.copy(securityClearanceUpdated = true)
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repository.saveWithOptimisticLockRetry(
            id = id,
            candidate = employee.copy(securityClearanceUpdated = true),
        ) { latest ->
            latest.copy(securityClearanceUpdated = true)
        }
    }

    fun setDepartmentAccess(employee: EmployeeOnboarding): EmployeeOnboarding {
        onboardingLogger.info("Setting department access permissions")
        val repository = repo ?: return employee.copy(departmentAccessSet = true)
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repository.saveWithOptimisticLockRetry(
            id = id,
            candidate = employee.copy(departmentAccessSet = true),
        ) { latest ->
            latest.copy(departmentAccessSet = true)
        }
    }

    fun generateEmployeeDocuments(employee: EmployeeOnboarding): EmployeeOnboarding {
        onboardingLogger.info("Generating employee documents")
        val repository = repo ?: return employee.copy(documentsGenerated = true)
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repository.saveWithOptimisticLockRetry(
            id = id,
            candidate = employee.copy(documentsGenerated = true),
        ) { latest ->
            latest.copy(documentsGenerated = true)
        }
    }

    fun sendContractForSigning(employee: EmployeeOnboarding): EmployeeOnboarding {
        onboardingLogger.info("Sending contract for signing")
        val repository = repo ?: return employee.copy(contractSentForSigning = true)
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repository.saveWithOptimisticLockRetry(
            id = id,
            candidate = employee.copy(contractSentForSigning = true),
        ) { latest ->
            latest.copy(contractSentForSigning = true)
        }
    }

    fun updateStatusInHRSystem(employee: EmployeeOnboarding): EmployeeOnboarding {
        onboardingLogger.info("Updating status in HR system")
        val repository = repo ?: return employee.copy(statusUpdatedInHR = true)
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repository.saveWithOptimisticLockRetry(
            id = id,
            candidate = employee.copy(statusUpdatedInHR = true),
        ) { latest ->
            latest.copy(statusUpdatedInHR = true)
        }
    }

    companion object {
        private val onboardingLogger = LoggerFactory.getLogger("EmployeeOnboardingActions")
    }
}
