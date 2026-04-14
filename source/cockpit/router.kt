package io.flowlite.cockpit

import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

fun cockpitRouter(service: CockpitService) =
    router {
        GET("/api/flows") { request ->
            val longRunningThresholdSeconds = request.param("longRunningThresholdSeconds").orElse(null)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: 3600L

            ServerResponse.ok().body(service.listFlows(longRunningThresholdSeconds = longRunningThresholdSeconds))
        }

        GET("/api/instances") { request ->
            val flowId = request.param("flowId").orElse(null)
            val bucket = request.param("bucket")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.replaceFirstChar { ch -> ch.uppercaseChar() } }
                .map { CockpitInstanceBucket.valueOf(it) }
                .orElse(null)
            val status = request.param("status")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { CockpitStatus.valueOf(it) }
                .orElse(null)
            val searchTerm = request.param("q").orElse(null)
            val stage = request.param("stage")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "all" }
                .orElse(null)
            val errorMessage = request.param("errorMessage")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .orElse(null)
            val showIncompleteOnly = request.param("incompleteOnly")
                .map { it.trim().lowercase() }
                .map { it == "1" || it == "true" || it == "yes" }
                .orElse(false)
            val cockpitStatusFilter = request.param("cockpitStatus")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .orElse(null)
            val longInactiveThresholdSeconds = request.param("longInactiveThresholdSeconds")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.toLongOrNull() }
                .orElse(null)

            ServerResponse.ok().body(
                service.listInstances(
                    flowId = flowId,
                    bucket = bucket,
                    status = status,
                    searchTerm = searchTerm,
                    stage = stage,
                    errorMessage = errorMessage,
                    showIncompleteOnly = showIncompleteOnly,
                    cockpitStatusFilter = cockpitStatusFilter,
                    longInactiveThresholdSeconds = longInactiveThresholdSeconds,
                ),
            )
        }

        GET("/api/errors") { request ->
            val flowId = request.param("flowId").orElse(null)
            val stage = request.param("stage")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "all" }
                .orElse(null)
            val errorMessage = request.param("errorMessage")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .orElse(null)
            ServerResponse.ok().body(service.listErrorGroups(flowId, stageContains = stage, errorMessage = errorMessage))
        }

        GET("/api/instances/{flowId}/{flowInstanceId}") { request ->
            val flowId = request.pathVariable("flowId")
            val flowInstanceId = UUID.fromString(request.pathVariable("flowInstanceId"))
            val instance = service.instance(flowId, flowInstanceId)
                ?: return@GET ServerResponse.notFound().build()

            ServerResponse.ok().body(instance)
        }

        GET("/api/instances/{flowId}/{flowInstanceId}/timeline") { request ->
            val flowId = request.pathVariable("flowId")
            val flowInstanceId = UUID.fromString(request.pathVariable("flowInstanceId"))
            ServerResponse.ok().body(service.timeline(flowId, flowInstanceId))
        }

        POST("/api/instances/{flowId}/{flowInstanceId}/retry") { request ->
            val flowId = request.pathVariable("flowId")
            val flowInstanceId = UUID.fromString(request.pathVariable("flowInstanceId"))
            service.retry(flowId, flowInstanceId)
            ServerResponse.status(HttpStatus.NO_CONTENT).build()
        }

        POST("/api/instances/{flowId}/{flowInstanceId}/cancel") { request ->
            val flowId = request.pathVariable("flowId")
            val flowInstanceId = UUID.fromString(request.pathVariable("flowInstanceId"))
            service.cancel(flowId, flowInstanceId)
            ServerResponse.status(HttpStatus.NO_CONTENT).build()
        }

        POST("/api/instances/{flowId}/{flowInstanceId}/change-stage") { request ->
            val flowId = request.pathVariable("flowId")
            val flowInstanceId = UUID.fromString(request.pathVariable("flowInstanceId"))
            val stage = request.param("stage").orElse(null)
                ?: return@POST ServerResponse.badRequest().body("Missing required query parameter: stage")

            service.changeStage(flowId, flowInstanceId, stage)
            ServerResponse.status(HttpStatus.NO_CONTENT).build()
        }
    }
