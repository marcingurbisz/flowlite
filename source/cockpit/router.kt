package io.flowlite.cockpit

import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

fun cockpitRouter(service: CockpitService): RouterFunction<ServerResponse> =
    router {
        GET("/api/flows") {
            ServerResponse.ok().body(service.listFlows())
        }

        GET("/api/instances") { request ->
            val flowId = request.param("flowId").orElse(null)
            val bucket = request.param("bucket")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.replaceFirstChar { ch -> ch.uppercaseChar() } }
                .map { CockpitInstanceBucket.valueOf(it) }
                .orElse(null)

            ServerResponse.ok().body(service.listInstances(flowId = flowId, bucket = bucket))
        }

        GET("/api/errors") { request ->
            val flowId = request.param("flowId").orElse(null)
            ServerResponse.ok().body(service.listErrorGroups(flowId))
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
