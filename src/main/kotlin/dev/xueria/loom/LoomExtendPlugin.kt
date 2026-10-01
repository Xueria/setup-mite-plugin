package dev.xueria.loom

import dev.xueria.loom.internal.ArtifactResolveException
import dev.xueria.loom.internal.MinecraftArtifactResolver
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import java.net.URI

class LoomExtendPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create(
            Constants.EXTENSION_NAME,
            LoomExtendExtension::class.java
        )

        extension.url.convention(Constants.DEFAULT_URL)

        project.pluginManager.withPlugin(Constants.LOOM_PLUGIN_ID) {
            attachConfiguration(project, extension)
        }
    }

    private fun attachConfiguration(project: Project, extension: LoomExtendExtension) {
        project.configurations.named(Constants.MINECRAFT_CONFIGURATION).configure { configuration ->
            configuration.dependencies.configureEach { dependency ->
                attachDependency(project, extension, dependency)
            }
        }
    }

    private fun attachDependency(project: Project, extension: LoomExtendExtension, dependency: Dependency) {
        val mcVersion = dependency.version
            ?.takeIf { it.isNotBlank() }
            ?: throw GradleException("minecraft version is invalid!")

        // link
        // Built by joining the segments: URI.resolve() replaces the last path segment of the base
        // unless it ends with '/', so chaining resolves would drop "mojang", "minecraft" and the
        // version one after another.
        val repository = extension.url.get().trimEnd('/')

        val url = URI.create(
            "$repository/" + listOf(
                Constants.DEPENDENCY_GROUP.replace(".", "/"),
                Constants.DEPENDENCY_NAME,
                mcVersion,
                "${Constants.DEPENDENCY_NAME}-$mcVersion.jar"
            ).joinToString("/")
        ).toASCIIString()

        val resolver = MinecraftArtifactResolver(
            url = url,
            gradleUserHome = project.gradle.gradleUserHomeDir.toPath(),
            version = mcVersion,
            log = { message -> project.logger.info("[${Constants.EXTENSION_NAME}] $message") }
        )

        val artifact = try {
            resolver.resolve()
        } catch (exception: ArtifactResolveException) {
            val hint = if (exception.recoverable) {
                " (the repository could not be reached, check the network or '${Constants.EXTENSION_NAME}.url' and try again)"
            } else {
                ""
            }

            throw GradleException(
                "failed to resolve minecraft $mcVersion from $url: ${exception.message}$hint",
                exception
            )
        }

        project.logger.lifecycle("[${Constants.EXTENSION_NAME}] using ${artifact.description} at ${artifact.path}")
    }
}
