@file:Suppress("UnstableApiUsage")

package me.shedaniel.architect.plugin

import me.shedaniel.architect.plugin.utils.GradleSupport
import me.shedaniel.architect.plugin.utils.Transform
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.util.LoggerFilter
import net.fabricmc.tinyremapper.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import org.zeroturnaround.zip.ByteSource
import org.zeroturnaround.zip.ZipUtil
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.collections.LinkedHashSet

open class TransformTask : Jar() {
    val input: RegularFileProperty = GradleSupport.getFileProperty(project)
    var runtime = false

    @TaskAction
    fun doTask() {
        val input: Path = this.input.asFile.get().toPath()
        val intermediate: Path = input.parent.resolve(input.toFile().nameWithoutExtension + "-intermediate.jar")
        val intermediate2: Path = input.parent.resolve(input.toFile().nameWithoutExtension + "-intermediate2.jar")
        val output: Path = this.archiveFile.get().asFile.toPath()

        if (!runtime) {
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
            val fakeModId = "generated_" + UUID.randomUUID().toString().filterNot { it == '-' }.take(7)
            ZipUtil.addOrReplaceEntries(
                intermediate.toFile(), arrayOf(
                    ByteSource(
                        "fabric.mod.json", """
{
  "schemaVersion": 1,
  "id": "$fakeModId",
  "name": "Generated Mod (Please Ignore)",
  "version": "1.0.0",
  "custom": {
    "fabric-loom:generated": true
  }
}
            """.toByteArray()
                    )
                )
            )
        }

        Files.deleteIfExists(intermediate2)
        project.logger.lifecycle(":transforming " + input.fileName + " => " + intermediate.fileName)
        Transform.transform(intermediate, intermediate2, transformExpectPlatform(project))

        Files.deleteIfExists(intermediate)

        if (project.extensions.getByType(ArchitectPluginExtension::class.java).injectInjectables) {
            transformArchitecturyInjectables(intermediate2, output)
        } else {
            Files.copy(intermediate2, output)
        }

        Files.deleteIfExists(intermediate2)

        if (!runtime) {
            val loomExtension = project.extensions.getByType(LoomGradleExtension::class.java)
            var refmapHelperClass: Class<*>? = null
            runCatching {
                refmapHelperClass = Class.forName("net.fabricmc.loom.util.MixinRefmapHelper")
            }.onFailure {
                runCatching {
                    refmapHelperClass = Class.forName("net.fabricmc.loom.build.MixinRefmapHelper")
                }.onFailure {
                    throw ClassNotFoundException("Failed to find MixinRefmapHelper!")
                }
            }

            val method = refmapHelperClass!!.getDeclaredMethod(
                "addRefmapName",
                String::class.java,
                String::class.java,
                Path::class.java
            )
            if (
                method.invoke(
                    null,
                    loomExtension.getRefmapName(),
                    loomExtension.mixinJsonVersion,
                    output
                ) as Boolean
            ) {
                project.logger.debug("Transformed mixin reference maps in output JAR!")
            }
        }
    }

    private fun transformArchitecturyInjectables(intermediate2: Path, output: Path) {
        val remapper = TinyRemapper.newRemapper()
            .withMappings { sink ->
                sink.acceptClass(
                    "me/shedaniel/architectury/targets/ArchitecturyTarget",
                    project.projectUniqueIdentifier() + "/PlatformMethods"
                )
                sink.acceptMethod(
                    IMappingProvider.Member(
                        "me/shedaniel/architectury/targets/ArchitecturyTarget",
                        "getCurrentTarget",
                        "()Ljava/lang/String;"
                    ), "getModLoader"
                )
            }
            .build()

        val classpathFiles: Set<File> = LinkedHashSet(
            project.configurations.getByName("compileClasspath").files
        )
        val classpath = classpathFiles.asSequence().map { obj: File -> obj.toPath() }
            .filter { p: Path -> this.input.asFile.get().toPath() != p && Files.exists(p) }.toList().toTypedArray()

        try {
            OutputConsumerPath.Builder(output).build().use { outputConsumer ->
                outputConsumer.addNonClassFiles(intermediate2, NonClassCopyMode.UNCHANGED, null)
                remapper.readClassPath(*classpath)
                remapper.readInputs(intermediate2)
                remapper.apply(outputConsumer)
            }
        } catch (e: Exception) {
            remapper.finish()
            throw RuntimeException("Failed to remap $intermediate2 to $output", e)
        }
    }
}