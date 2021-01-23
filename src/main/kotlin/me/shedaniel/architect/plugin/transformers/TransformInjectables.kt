package me.shedaniel.architect.plugin.transformers

import me.shedaniel.architect.plugin.ArchitectPluginExtension
import me.shedaniel.architect.plugin.Transformer
import me.shedaniel.architect.plugin.projectUniqueIdentifier
import me.shedaniel.architect.plugin.utils.validateJarFs
import net.fabricmc.loom.util.LoggerFilter
import net.fabricmc.tinyremapper.IMappingProvider
import net.fabricmc.tinyremapper.NonClassCopyMode
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import org.gradle.api.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

object TransformInjectables : Transformer {
    const val expectPlatform = "Lme/shedaniel/architectury/ExpectPlatform;"
    const val expectPlatformNew = "Lme/shedaniel/architectury/annotations/ExpectPlatform;"

    override fun invoke(project: Project, input: Path, output: Path) {
        if (project.extensions.getByType(ArchitectPluginExtension::class.java).injectInjectables) {
            transformArchitecturyInjectables(project, input, output)
        } else {
            Files.copy(input, output)
        }
    }

    private fun transformArchitecturyInjectables(project: Project, input: Path, output: Path) {
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
            .filter { p: Path -> Files.exists(p) }.toList().toTypedArray()

        LoggerFilter.replaceSystemOut()
        try {
            project.validateJarFs(output)
            OutputConsumerPath.Builder(output).build().use { outputConsumer ->
                outputConsumer.addNonClassFiles(input, NonClassCopyMode.UNCHANGED, null)
                remapper.readClassPath(*classpath)
                remapper.readInputs(input)
                remapper.apply(outputConsumer)
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to remap $input to $output", e)
        } finally {
            remapper.finish()
        }
    }
}