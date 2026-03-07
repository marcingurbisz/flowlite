package io.flowlite.cockpit

import java.nio.file.Path
import java.nio.file.Paths
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

class CockpitUiStaticConfig(
    private val classpathDistLocation: String = "classpath:/cockpit-ui/dist/",
    private val distPath: Path = Paths.get("cockpit-ui", "dist"),
) : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val resourceLocations = resolveResourceLocations()

        registry.addResourceHandler("/cockpit", "/cockpit/**")
            .addResourceLocations(resourceLocations.dist)

        registry.addResourceHandler("/", "/index.html", "/vite.svg")
            .addResourceLocations(resourceLocations.dist)

        registry.addResourceHandler("/assets/**")
            .addResourceLocations(resourceLocations.assets)
    }

    private fun resolveResourceLocations(): ResourceLocations {
        val normalizedClasspathDistLocation = ensureTrailingSlash(classpathDistLocation)
        if (javaClass.getResource("/cockpit-ui/dist/index.html") != null) {
            return ResourceLocations(
                dist = normalizedClasspathDistLocation,
                assets = ensureTrailingSlash("${normalizedClasspathDistLocation}assets"),
            )
        }

        val normalizedDistPath = distPath.toAbsolutePath().normalize()
        val distAssetsPath = normalizedDistPath.resolve("assets")

        return ResourceLocations(
            dist = ensureTrailingSlash(normalizedDistPath.toUri().toString()),
            assets = ensureTrailingSlash(distAssetsPath.toUri().toString()),
        )
    }

    private fun ensureTrailingSlash(location: String) = if (location.endsWith("/")) location else "$location/"

    private data class ResourceLocations(
        val dist: String,
        val assets: String,
    )
}