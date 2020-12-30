package me.shedaniel.architect.plugin

import net.fabricmc.loom.util.LoggerFilter
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import java.net.URI

class ArchitectPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        LoggerFilter.replaceSystemOut()

        project.apply(
            mapOf(
                "plugin" to "java",
                "plugin" to "eclipse",
                "plugin" to "idea"
            )
        )

        project.extensions.create("architect", ArchitectPluginExtension::class.java, project)
        project.extensions.add("architectury", project.extensions.getByName("architect"))

        project.afterEvaluate {
            project.extensions.getByType(JavaPluginExtension::class.java).apply {
                sourceCompatibility = JavaVersion.VERSION_1_8
                targetCompatibility = JavaVersion.VERSION_1_8
            }
        }

        project.tasks.register("transformForge", RemapMCPTask::class.java) {
            it.fakeMod = false
            it.remapMcp = false
            it.group = "Architectury"
        }

        project.tasks.register("transformForgeFakeMod", RemapMCPTask::class.java) {
            it.fakeMod = true
            it.remapMcp = false
            it.group = "Architectury"
        }

        project.tasks.register("transformArchitectJar", TransformTask::class.java) {
            it.group = "Architectury"
        }
        
        project.tasks.register("transformArchitectJarRuntime", TransformTask::class.java) {
            it.group = "Architectury"
            it.addRefmap = false
        }

        project.repositories.apply {
            mavenCentral()
            jcenter()
            maven { it.url = URI("https://dl.bintray.com/shedaniel/cloth") }
        }
    }
}