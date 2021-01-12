@file:Suppress("UnstableApiUsage")

package me.shedaniel.architect.plugin

import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.AbstractArchiveTask

open class ArchitectPluginExtension(val project: Project) {
    var minecraft = ""
    var injectInjectables = true

    fun common() {
        common(true)
    }

    fun platformSetupLoomIde() {
        val loomExtension = project.extensions.getByType(LoomGradleExtension::class.java)
        loomExtension.autoGenIDERuns = true
        loomExtension.addTaskBeforeRun("\$PROJECT_DIR\$/${project.name}:build")
    }

    fun common(forgeEnabled: Boolean) {
        if (injectInjectables) {
            with(project.dependencies) {
                add("compileOnly", "me.shedaniel:architectury-injectables:1.0.4")
            }
        }
        
        if (forgeEnabled) {
            project.configurations.create("mcp")
            project.configurations.create("mcpGenerateMod")
            project.configurations.create("transformForge")
            project.configurations.create("transformForgeFakeMod")
        }
        project.configurations.create("transformed")
        project.configurations.create("transformedRuntime")

        val buildTask = project.tasks.getByName("build")
        val jarTask = project.tasks.getByName("jar") {
            it as AbstractArchiveTask
            it.archiveClassifier.set("dev")
        } as AbstractArchiveTask

        val transformArchitectJarTask = project.tasks.getByName("transformArchitectJar") {
            it as TransformTask

            it.archiveClassifier.set("transformed")
            it.input.set(jarTask.archiveFile.get())

            project.artifacts.add("archives", it)
            it.dependsOn(jarTask)
            buildTask.dependsOn(it)
            it.outputs.upToDateWhen { false }
        } as TransformTask  
        val transformArchitectRuntimeJarTask = project.tasks.getByName("transformArchitectJarRuntime") {
            it as TransformTask

            it.archiveClassifier.set("transformedRuntime")
            it.input.set(jarTask.archiveFile.get())

            project.artifacts.add("archives", it)
            it.dependsOn(jarTask)
            buildTask.dependsOn(it)
            it.outputs.upToDateWhen { false }
        } as TransformTask

        val remapJarTask = project.tasks.getByName("remapJar") {
            it as RemapJarTask

            it.archiveClassifier.set("")
            it.input.set(transformArchitectJarTask.archiveFile.get())
            it.dependsOn(transformArchitectJarTask)
            it.mustRunAfter(transformArchitectJarTask)
        } as RemapJarTask

        if (forgeEnabled) {
            val transformForgeTask = project.tasks.getByName("transformForge") {
                it as RemapMCPTask

                it.input.set(transformArchitectJarTask.archiveFile.get())
                it.archiveClassifier.set("transformedForge")
                it.dependsOn(transformArchitectJarTask)
                buildTask.dependsOn(it)
                it.outputs.upToDateWhen { false }
            } as RemapMCPTask

            val transformForgeFakeModTask = project.tasks.getByName("transformForgeFakeMod") {
                it as RemapMCPTask

                it.input.set(transformArchitectRuntimeJarTask.archiveFile.get())
                it.archiveClassifier.set("transformedForgeFakeMod")
                it.dependsOn(transformArchitectRuntimeJarTask)
                buildTask.dependsOn(it)
                it.outputs.upToDateWhen { false }
            } as RemapMCPTask

            project.artifacts {
                it.add(
                    "transformForge", mapOf(
                        "file" to transformForgeTask.archiveFile.get().asFile,
                        "type" to "jar",
                        "builtBy" to transformForgeTask
                    )
                )
                it.add(
                    "transformForgeFakeMod", mapOf(
                        "file" to transformForgeFakeModTask.archiveFile.get().asFile,
                        "type" to "jar",
                        "builtBy" to transformForgeFakeModTask
                    )
                )
            }

            project.extensions.getByType(LoomGradleExtension::class.java).generateSrgTiny = true
        }

        project.artifacts {
            it.add(
                "transformed", mapOf(
                    "file" to transformArchitectJarTask.archiveFile.get().asFile,
                    "type" to "jar",
                    "builtBy" to transformArchitectJarTask
                )
            )
            it.add(
                "transformedRuntime", mapOf(
                    "file" to transformArchitectRuntimeJarTask.archiveFile.get().asFile,
                    "type" to "jar",
                    "builtBy" to transformArchitectRuntimeJarTask
                )
            )
        }
    }
}