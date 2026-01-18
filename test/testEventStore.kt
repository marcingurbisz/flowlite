package io.flowlite.test

import io.flowlite.api.Event
import io.flowlite.api.EventStore
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
    fun deleteByIdAndFlowIdAndFlowInstanceIdAndEventTypeAndEventValue(
        id: UUID,
        flowId: String,
        flowInstanceId: UUID,
        eventType: String,
        eventValue: String,
    ): Long
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

    override fun poll(flowId: String, flowInstanceId: UUID, candidates: Collection<Event>): Event? {
        if (candidates.isEmpty()) return null
        val rows = repo.findByFlowIdAndFlowInstanceId(flowId, flowInstanceId)
        val candidateLookup = candidates.associateBy {
            val type = it::class.qualifiedName ?: it::class.java.name
            val value = (it as? Enum<*>)?.name ?: it.toString()
            type to value
        }
        for (row in rows) {
            val key = row.eventType to row.eventValue
            val candidate = candidateLookup[key] ?: continue
            val id = row.id ?: continue
            val deleted = repo.deleteByIdAndFlowIdAndFlowInstanceIdAndEventTypeAndEventValue(
                id = id,
                flowId = flowId,
                flowInstanceId = flowInstanceId,
                eventType = row.eventType,
                eventValue = row.eventValue,
            )
            if (deleted == 1L) {
                return candidate
            }
        }
        return null
    }
}
