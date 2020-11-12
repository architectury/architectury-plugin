@file:Suppress("UnstableApiUsage")

package me.shedaniel.architect.plugin

import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.AbstractArchiveTask

open class ArchitectPluginExtension(val project: Project) {
    var minecraft = ""

    fun common() {
        project.configurations.create("mcp")
        project.configurations.create("mcpGenerateMod")
        project.configurations.create("transformed")

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

        val remapJarTask = project.tasks.getByName("remapJar") {
            it as RemapJarTask

            it.archiveClassifier.set("")
            it.input.set(transformArchitectJarTask.archiveFile.get())
            it.dependsOn(transformArchitectJarTask)
            it.mustRunAfter(transformArchitectJarTask)
        } as RemapJarTask

        val remapMCPTask = project.tasks.getByName("remapMcp") {
            it as RemapMCPTask

            it.input.set(transformArchitectJarTask.archiveFile.get())
            it.archiveClassifier.set("mcp")
            it.dependsOn(transformArchitectJarTask)
            buildTask.dependsOn(it)
            it.outputs.upToDateWhen { false }
        } as RemapMCPTask

        val remapMCPFakeModTask = project.tasks.getByName("remapMcpFakeMod") {
            it as RemapMCPTask

            it.input.set(transformArchitectJarTask.archiveFile.get())
            it.archiveClassifier.set("mcpGenerateMod")
            it.dependsOn(transformArchitectJarTask)
            buildTask.dependsOn(it)
            it.outputs.upToDateWhen { false }
        } as RemapMCPTask

        project.artifacts {
            it.add(
                "mcp", mapOf(
                    "file" to remapMCPTask.archiveFile.get().asFile,
                    "type" to "jar",
                    "builtBy" to remapMCPTask
                )
            )
            it.add(
                "mcpGenerateMod", mapOf(
                    "file" to remapMCPFakeModTask.archiveFile.get().asFile,
                    "type" to "jar",
                    "builtBy" to remapMCPFakeModTask
                )
            )
            it.add(
                "transformed", mapOf(
                    "file" to transformArchitectJarTask.archiveFile.get().asFile,
                    "type" to "jar",
                    "builtBy" to transformArchitectJarTask
                )
            )
        }
    }
}