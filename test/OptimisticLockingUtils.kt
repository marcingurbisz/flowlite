package io.flowlite.test

import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.repository.CrudRepository

inline fun <T : Any, ID : Any> CrudRepository<T, ID>.saveWithOptimisticLockRetry(
    id: ID,
    candidate: T? = null,
    maxAttempts: Int = 5,
    crossinline updateFromLatest: (T) -> T,
): T {
    require(maxAttempts >= 1) { "maxAttempts must be >= 1" }

    var attempt = 0
    var last: Throwable? = null

    if (candidate != null) {
        attempt++
        try {
            return save(candidate)
        } catch (ex: OptimisticLockingFailureException) {
            last = ex
        }
    }

    while (attempt++ < maxAttempts) {
        val latest = findById(id).orElseThrow()
        val updated = updateFromLatest(latest)
        try {
            return save(updated)
        } catch (ex: OptimisticLockingFailureException) {
            last = ex
        }
    }

    throw requireNotNull(last) { "saveWithOptimisticLockRetry exhausted retries" }
}
