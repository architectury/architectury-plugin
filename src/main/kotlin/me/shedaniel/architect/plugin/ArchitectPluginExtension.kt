@file:Suppress("UnstableApiUsage")

package me.shedaniel.architect.plugin

import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.io.File
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

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
            project.configurations.create("transformProductionForge")
            project.configurations.create("transformDevelopmentForge")
        }
        project.configurations.create("transformProductionFabric")
        project.configurations.create("transformDevelopmentFabric")

        val buildTask = project.tasks.getByName("build")
        val jarTask = project.tasks.getByName("jar") {
            it as AbstractArchiveTask
            it.archiveClassifier.set("dev")
        } as AbstractArchiveTask

        val transformProductionFabricTask = project.tasks.getByName("transformProductionFabric") {
            it as TransformingTask

            it.archiveClassifier.set("transformProductionFabric")
            it.input.set(jarTask.archiveFile.get())

            project.artifacts.add("transformProductionFabric", it)
            it.dependsOn(jarTask)
            buildTask.dependsOn(it)
            it.outputs.upToDateWhen { false }
        } as TransformingTask
        val transformDevelopmentFabricTask = project.tasks.getByName("transformDevelopmentFabric") {
            it as TransformingTask

            it.archiveClassifier.set("transformDevelopmentFabric")
            it.input.set(jarTask.archiveFile.get())

            project.artifacts.add("transformDevelopmentFabric", it)
            it.dependsOn(jarTask)
            buildTask.dependsOn(it)
            it.outputs.upToDateWhen { false }
        } as TransformingTask

        val remapJarTask = project.tasks.getByName("remapJar") {
            it as RemapJarTask

            it.archiveClassifier.set("")
            it.input.set(transformProductionFabricTask.archiveFile.get())
            it.dependsOn(transformProductionFabricTask)
            it.mustRunAfter(transformProductionFabricTask)
        } as RemapJarTask

        if (forgeEnabled) {
            val transformProductionForgeTask = project.tasks.getByName("transformProductionForge") {
                it as TransformingTask

                it.input.set(jarTask.archiveFile.get())
                it.archiveClassifier.set("transformProductionForge")

                project.artifacts.add("transformProductionForge", it)
                it.dependsOn(jarTask)
                buildTask.dependsOn(it)
                it.outputs.upToDateWhen { false }
            } as TransformingTask

            val transformDevelopmentForgeTask = project.tasks.getByName("transformDevelopmentForge") {
                it as TransformingTask

                it.input.set(jarTask.archiveFile.get())
                it.archiveClassifier.set("transformDevelopmentForge")

                project.artifacts.add("transformDevelopmentForge", it) { artifact ->
                    artifact.builtBy(it)
                }
                it.dependsOn(jarTask)
                buildTask.dependsOn(it)
                it.outputs.upToDateWhen { false }
            } as TransformingTask

            transformProductionForgeTask.archiveFile.get().asFile.takeUnless { it.exists() }?.createEmptyJar()
            transformDevelopmentForgeTask.archiveFile.get().asFile.takeUnless { it.exists() }?.createEmptyJar()

            project.extensions.getByType(LoomGradleExtension::class.java).generateSrgTiny = true
        }

        transformProductionFabricTask.archiveFile.get().asFile.takeUnless { it.exists() }?.createEmptyJar()
        transformDevelopmentFabricTask.archiveFile.get().asFile.takeUnless { it.exists() }?.createEmptyJar()
    }
}

private fun File.createEmptyJar() {
    parentFile.mkdirs()
    JarOutputStream(outputStream(), Manifest()).close()
}
