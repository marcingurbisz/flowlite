package io.flowlite.test

import io.flowlite.api.ProcessData
import io.flowlite.api.StatePersister
import java.util.UUID

class InMemoryStatePersister<T : Any> : StatePersister<T> {
    private val data = mutableMapOf<UUID, ProcessData<T>>()

    override fun save(processData: ProcessData<T>): Boolean {
        data[processData.flowInstanceId] = processData
        return true
    }

    override fun load(flowInstanceId: UUID): ProcessData<T>? = data[flowInstanceId]
}
