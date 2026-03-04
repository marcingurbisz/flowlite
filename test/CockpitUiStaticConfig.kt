package io.flowlite.test

import java.nio.file.Paths
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration(proxyBeanMethods = false)
class CockpitUiStaticConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val distPath = Paths.get("cockpit-ui", "dist").toAbsolutePath().normalize()
        val distAssetsPath = distPath.resolve("assets")
        val distLocation = distPath.toUri().toString().let { if (it.endsWith("/")) it else "$it/" }
        val distAssetsLocation = distAssetsPath.toUri().toString().let { if (it.endsWith("/")) it else "$it/" }

        registry.addResourceHandler("/cockpit", "/cockpit/**")
            .addResourceLocations(distLocation)

        registry.addResourceHandler("/", "/index.html", "/vite.svg")
            .addResourceLocations(distLocation)

        registry.addResourceHandler("/assets/**")
            .addResourceLocations(distAssetsLocation)
    }
}
