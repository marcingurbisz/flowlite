package io.flowlite.cockpit

import java.nio.file.Path
import java.nio.file.Paths
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver

class CockpitUiStaticConfig(
    private val classpathDistLocation: String = "classpath:/cockpit-ui/dist/",
    private val distPath: Path = Paths.get("cockpit-ui", "dist"),
) : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val distLocation = resolveDistLocation()

        registry.addResourceHandler("/cockpit", "/cockpit/**")
            .addResourceLocations(distLocation)

        registry.addResourceHandler("/", "/index.html", "/vite.svg")
            .addResourceLocations(distLocation)

        registry.addResourceHandler("/assets/**")
            .addResourceLocations(distLocation)
            .resourceChain(false)
            .addResolver(AssetsPathResourceResolver())
    }

    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addViewController("/").setViewName("forward:/cockpit/index.html")
        registry.addViewController("/cockpit").setViewName("forward:/cockpit/index.html")
        registry.addViewController("/cockpit/").setViewName("forward:/cockpit/index.html")
    }

    private fun resolveDistLocation(): String {
        val normalizedClasspathDistLocation = ensureTrailingSlash(classpathDistLocation)
        if (javaClass.getResource("/cockpit-ui/dist/index.html") != null) {
            return normalizedClasspathDistLocation
        }

        val normalizedDistPath = distPath.toAbsolutePath().normalize()
        return ensureTrailingSlash(normalizedDistPath.toUri().toString())
    }

    private fun ensureTrailingSlash(location: String) = if (location.endsWith("/")) location else "$location/"

    private class AssetsPathResourceResolver : PathResourceResolver() {
        override fun getResource(resourcePath: String, location: Resource): Resource? =
            super.getResource("assets/${resourcePath.trimStart('/')}", location)
    }
}