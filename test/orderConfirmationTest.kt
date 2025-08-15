package io.flowlite.test

import io.flowlite.api.Event
import io.flowlite.api.Flow
import io.flowlite.api.FlowBuilder
import io.flowlite.api.Stage
import io.flowlite.test.OrderConfirmationEvent.ConfirmedPhysically
import io.flowlite.test.OrderConfirmationStage.*

enum class OrderConfirmationStage : Stage {
    InitializingConfirmation,
    WaitingForConfirmation,
    RemovingFromConfirmationQueue,
    InformingCustomer,
}

enum class OrderConfirmationEvent : Event {
    ConfirmedPhysically,
    ConfirmedDigitally,
}

enum class ConfirmationType {
    DIGITAL, // Online payment, app confirmation, e-signature
    PHYSICAL, // Phone confirmation, in-person payment, paper signature
}

data class OrderConfirmation(
    val processId: String = "",
    val stage: OrderConfirmationStage,
    val orderNumber: String,
    val confirmationType: ConfirmationType,
    val customerName: String,
    val customerPhone: String = "",
    val isRemovedFromQueue: Boolean = false,
    val isCustomerInformed: Boolean = false,
    val confirmationTimestamp: String = "",
)

fun initializeOrderConfirmation(confirmation: OrderConfirmation): OrderConfirmation {
    println("[Action] Initializing order confirmation for order ${confirmation.orderNumber}")
    val timestamp = System.currentTimeMillis().toString()
    return confirmation.copy(stage = InitializingConfirmation, confirmationTimestamp = timestamp)
}

fun removeFromConfirmationQueue(confirmation: OrderConfirmation): OrderConfirmation {
    println("[Action] Removing order ${confirmation.orderNumber} from confirmation queue (digital processing)")
    return confirmation.copy(stage = RemovingFromConfirmationQueue, isRemovedFromQueue = true)
}

fun informCustomer(confirmation: OrderConfirmation): OrderConfirmation {
    val method =
        when (confirmation.confirmationType) {
            ConfirmationType.DIGITAL -> "app notification/email"
            ConfirmationType.PHYSICAL -> "phone call"
        }
    println(
        "[Action] Informing customer ${confirmation.customerName} via $method that order ${confirmation.orderNumber} is being prepared"
    )
    return confirmation.copy(stage = InformingCustomer, isCustomerInformed = true)
}

fun createOrderConfirmationFlow(): Flow<OrderConfirmation> {
    return FlowBuilder<OrderConfirmation>()
        .stage(InitializingConfirmation, ::initializeOrderConfirmation)
        .stage(WaitingForConfirmation)
        .apply {
            onEvent(OrderConfirmationEvent.ConfirmedDigitally)
                .stage(RemovingFromConfirmationQueue, ::removeFromConfirmationQueue)
                .stage(InformingCustomer, ::informCustomer)
            onEvent(ConfirmedPhysically).join(InformingCustomer)
        }
        .end()
        .build()
}