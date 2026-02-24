package io.flowlite.test

import java.nio.file.Paths
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration(proxyBeanMethods = false)
class CockpitUiStaticConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val distPath = Paths.get("cockpit-ui", "dist").toAbsolutePath().normalize().toUri().toString()

        registry.addResourceHandler("/cockpit/**")
            .addResourceLocations(distPath)

        registry.addResourceHandler("/", "/index.html", "/assets/**", "/vite.svg")
            .addResourceLocations(distPath)
    }
}
