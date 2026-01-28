package io.flowlite.test

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
