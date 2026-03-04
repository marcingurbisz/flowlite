package io.flowlite.cockpit

import java.nio.file.Path
import java.nio.file.Paths
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

class CockpitUiStaticConfig(
    private val distPath: Path = Paths.get("cockpit-ui", "dist"),
) : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val normalizedDistPath = distPath.toAbsolutePath().normalize()
        val distAssetsPath = normalizedDistPath.resolve("assets")
        val distLocation = normalizedDistPath.toUri().toString().let { if (it.endsWith("/")) it else "$it/" }
        val distAssetsLocation = distAssetsPath.toUri().toString().let { if (it.endsWith("/")) it else "$it/" }

        registry.addResourceHandler("/cockpit", "/cockpit/**")
            .addResourceLocations(distLocation)

        registry.addResourceHandler("/", "/index.html", "/vite.svg")
            .addResourceLocations(distLocation)

        registry.addResourceHandler("/assets/**")
            .addResourceLocations(distAssetsLocation)
    }
}