@file:Suppress("UnstableApiUsage")

package me.shedaniel.architect.plugin

import me.shedaniel.architect.plugin.utils.GradleSupport
import me.shedaniel.architect.plugin.utils.Transform
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.util.LoggerFilter
import net.fabricmc.loom.util.MixinRefmapHelper
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import net.fabricmc.tinyremapper.TinyUtils
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

open class TransformTask : Jar() {
    val input: RegularFileProperty = GradleSupport.getFileProperty(project)
    var addRefmap = true

    @TaskAction
    fun doTask() {
        val input: Path = this.input.asFile.get().toPath()
        val intermediate: Path = input.parent.resolve(input.toFile().nameWithoutExtension + "-intermediate.jar")
        val output: Path = this.archiveFile.get().asFile.toPath()

        if (addRefmap) {
            val loomExtension = project.extensions.getByType(LoomGradleExtension::class.java)
            var remapperBuilder = TinyRemapper.newRemapper()
            for (mixinMapFile in loomExtension.allMixinMappings) {
                if (mixinMapFile.exists()) {
                    remapperBuilder = remapperBuilder.withMappings(
                        TinyUtils.createTinyMappingProvider(
                            mixinMapFile.toPath(),
                            "named",
                            "intermediary"
                        )
                    )
                }
            }

            val remapper = remapperBuilder.build()

            val classpathFiles: Set<File> = LinkedHashSet(
                project.configurations.getByName("compileClasspath").files
            )
            val classpath = classpathFiles.asSequence().map { obj: File -> obj.toPath() }.filter { p: Path ->
                input != p && Files.exists(p)
            }.toList().toTypedArray()

            LoggerFilter.replaceSystemOut()
            try {
                OutputConsumerPath.Builder(intermediate).build().use { outputConsumer ->
                    outputConsumer.addNonClassFiles(input)
                    remapper.readClassPath(*classpath)
                    remapper.readInputs(input)
                    remapper.apply(outputConsumer)
                }
            } catch (e: Exception) {
                remapper.finish()
                throw RuntimeException("Failed to remap $input to $intermediate", e)
            }

            remapper.finish()
        } else {
            Files.copy(input, intermediate)
        }

        project.logger.lifecycle(":transforming " + input.fileName + " => " + intermediate.fileName)
        Transform.transform(intermediate, output, transformExpectPlatform())

        Files.deleteIfExists(intermediate)

        if (addRefmap) {
            val loomExtension = project.extensions.getByType(LoomGradleExtension::class.java)
            if (MixinRefmapHelper.addRefmapName(
                    loomExtension.getRefmapName(),
                    loomExtension.mixinJsonVersion,
                    output
                )
            ) {
                project.logger.debug("Transformed mixin reference maps in output JAR!")
            }
        }
    }
}