package me.shedaniel.architect.plugin.transformers

import me.shedaniel.architect.plugin.Transformer
import me.shedaniel.architect.plugin.utils.validateJarFs
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.util.LoggerFilter
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import net.fabricmc.tinyremapper.TinyUtils
import org.gradle.api.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

object RemapMixinVariables : Transformer {
    override fun invoke(project: Project, input: Path, output: Path) {
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
            project.validateJarFs(output)
            OutputConsumerPath.Builder(output).build().use { outputConsumer ->
                outputConsumer.addNonClassFiles(input)
                remapper.readClassPath(*classpath)
                remapper.readInputs(input)
                remapper.apply(outputConsumer)
            }
        } catch (e: Exception) {
            remapper.finish()
            throw RuntimeException("Failed to remap $input to $output", e)
        }

        remapper.finish()
    }
}