package me.shedaniel.architect.plugin

import me.shedaniel.architect.plugin.transformers.AddRefmapName
import me.shedaniel.architect.plugin.transformers.RemapMixinVariables
import me.shedaniel.architectury.transformer.transformers.*
import net.fabricmc.loom.util.LoggerFilter
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.gradle.ext.ActionDelegationConfig
import java.net.URI

class ArchitectPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val version = ArchitectPlugin::class.java.getPackage().implementationVersion
        val loggedVersions = System.getProperty("architectury.printed.logged", "").split(",").toMutableSet()

        if (!loggedVersions.contains(version)) {
            loggedVersions.add(version)
            System.setProperty("architectury.printed.logged", loggedVersions.joinToString(","))
            project.logger.lifecycle("Architect Plugin: $version")
        }

        LoggerFilter.replaceSystemOut()

        project.apply(
            mapOf(
                "plugin" to "java",
                "plugin" to "eclipse",
                "plugin" to "idea",
                "plugin" to "org.jetbrains.gradle.plugin.idea-ext"
            )
        )

        project.afterEvaluate {
            val ideaModel = project.extensions.getByName("idea") as IdeaModel
            val idea = ideaModel.project as? ExtensionAware
            val settings = idea?.extensions?.getByName("settings") as? ExtensionAware
            (settings?.extensions?.getByName("delegateActions") as? ActionDelegationConfig)?.apply {
                delegateBuildRunToGradle = true
                testRunner = ActionDelegationConfig.TestRunner.GRADLE
            }
        }

        project.extensions.create("architectury", ArchitectPluginExtension::class.java, project)

        project.configurations.create("transformFabric")
        project.configurations.create("transformForge")

        project.tasks.register("transformProductionFabric", TransformingTask::class.java) {
            it.group = "Architectury"
            it += RemapMixinVariables(project)
            it += TransformExpectPlatform()
            it += RemapInjectables()
            it += AddRefmapName(project)
        }

        project.tasks.register("transformProductionForge", TransformingTask::class.java) {
            it.group = "Architectury"
            it += RemapMixinVariables(project)
            it += TransformExpectPlatform()
            it += RemapInjectables()
            it += AddRefmapName(project)

            it += TransformForgeAnnotations()
            it += TransformForgeEnvironment()
            it += FixForgeMixin()
        }

        project.repositories.apply {
            mavenCentral()
            maven { it.url = URI("https://maven.shedaniel.me/") }
        }
    }
}
