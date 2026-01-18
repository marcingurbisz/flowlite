package io.flowlite.test

import io.flowlite.api.Event
import io.flowlite.api.EventStore
import io.flowlite.api.StoredEvent
import org.springframework.data.annotation.Id
import java.util.UUID
import org.springframework.data.repository.CrudRepository

// --- Event store sample ---

data class PendingEvent(
    @Id val id: UUID? = null,
    val flowId: String,
    val flowInstanceId: UUID,
    val eventType: String,
    val eventValue: String,
)

interface PendingEventRepository : CrudRepository<PendingEvent, UUID> {
    fun findByFlowIdAndFlowInstanceId(flowId: String, flowInstanceId: UUID): List<PendingEvent>
}

class SpringDataEventStore(
    private val repo: PendingEventRepository,
) : EventStore {
    override fun append(flowId: String, flowInstanceId: UUID, event: Event) {
        val type = event::class.qualifiedName ?: event::class.java.name
        val value = (event as? Enum<*>)?.name ?: event.toString()
        repo.save(
            PendingEvent(
                id = null,
                flowId = flowId,
                flowInstanceId = flowInstanceId,
                eventType = type,
                eventValue = value,
            ),
        )
    }

    override fun peek(flowId: String, flowInstanceId: UUID, candidates: Collection<Event>): StoredEvent? {
        if (candidates.isEmpty()) return null
        val rows = repo.findByFlowIdAndFlowInstanceId(flowId, flowInstanceId)
        val candidateLookup = candidates.associateBy {
            val type = it::class.qualifiedName ?: it::class.java.name
            val value = (it as? Enum<*>)?.name ?: it.toString()
            type to value
        }
        val match = rows.firstOrNull { row -> candidateLookup.containsKey(row.eventType to row.eventValue) }
            ?: return null
        val id = match.id ?: return null
        val event = candidateLookup[match.eventType to match.eventValue] ?: return null
        return StoredEvent(id = id, event = event)
    }

    override fun delete(flowId: String, flowInstanceId: UUID, eventId: UUID): Boolean {
        val row = repo.findById(eventId).orElse(null) ?: return false
        if (row.flowId != flowId || row.flowInstanceId != flowInstanceId) return false
        repo.deleteById(eventId)
        return true
    }
}
