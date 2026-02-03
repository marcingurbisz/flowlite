package io.flowlite.test

import io.flowlite.api.FlowEngine
import io.flowlite.api.StatePersister
import io.kotest.core.listeners.ProjectListener
import org.springframework.beans.factory.getBean
import org.springframework.context.ConfigurableApplicationContext

object TestApplicationExtension : ProjectListener {
    @Volatile var context: ConfigurableApplicationContext? = null

    fun context(): ConfigurableApplicationContext {
        val existing = context
        if (existing != null) return existing

        return synchronized(this) {
            val recheck = context
            if (recheck != null) return recheck
            val started = startTestApplication()
            context = started
            started
        }
    }

    val engine: FlowEngine
        get() = context().getBean<FlowEngine>()

    val orderPersister: StatePersister<OrderConfirmation>
        get() = context().getBean<SpringDataOrderConfirmationPersister>()

    val employeeOnboardingRepository: EmployeeOnboardingRepository
        get() = context().getBean<EmployeeOnboardingRepository>()

    val employeeOnboardingActions: EmployeeOnboardingActions
        get() = context().getBean<EmployeeOnboardingActions>()

    override suspend fun beforeProject() {
        context()
    }

    override suspend fun afterProject() {
        synchronized(this) {
            context?.close()
            context = null
        }
    }
}
