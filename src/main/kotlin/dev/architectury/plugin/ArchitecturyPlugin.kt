package dev.architectury.plugin

import dev.architectury.transformer.util.LoggerFilter
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.gradle.ext.ActionDelegationConfig
import java.net.URI

class ArchitecturyPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val version = ArchitecturyPlugin::class.java.getPackage().implementationVersion
        val loggedVersions = System.getProperty("architectury.printed.logged", "").split(",").toMutableSet()

        if (!loggedVersions.contains(version)) {
            loggedVersions.add(version)
            System.setProperty("architectury.printed.logged", loggedVersions.joinToString(","))
            project.logger.lifecycle("Architect Plugin: $version")
        }

        LoggerFilter.replaceSystemOut()

        project.pluginManager.apply( "java" )
        project.pluginManager.apply( "eclipse" )
        project.pluginManager.apply( "idea" )
        project.pluginManager.apply( "org.jetbrains.gradle.plugin.idea-ext" )

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

        project.repositories.apply {
            mavenCentral()
            maven { it.url = URI("https://maven.architectury.dev/") }
        }
    }
}
