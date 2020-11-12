@file:Suppress("UnstableApiUsage")

package me.shedaniel.architect.plugin

import me.shedaniel.architect.plugin.utils.GradleSupport
import me.shedaniel.architect.plugin.utils.Transform
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import java.nio.file.Path

open class TransformTask : Jar() {
    val input: RegularFileProperty = GradleSupport.getFileProperty(project)

    @TaskAction
    fun doTask() {
        val input: Path = this.input.asFile.get().toPath()
        val output: Path = this.archiveFile.get().asFile.toPath()

        project.logger.lifecycle(":transforming " + input.fileName + " => " + output.fileName)
        Transform.transform(input, output, transformExpectPlatform())
    }
}