@file:Suppress("UnstableApiUsage")

package me.shedaniel.architect.plugin

import me.shedaniel.architect.plugin.utils.GradleSupport
import me.shedaniel.architect.plugin.utils.Transform
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.util.MixinRefmapHelper
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

        val loomExtension = project.extensions.getByType(LoomGradleExtension::class.java)
        if (MixinRefmapHelper.addRefmapName(loomExtension.getRefmapName(), loomExtension.mixinJsonVersion, output)) {
            project.logger.debug("Transformed mixin reference maps in output JAR!")
        }
    }
}