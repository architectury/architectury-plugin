package me.shedaniel.architect.plugin.transformers

import me.shedaniel.architect.plugin.Transformer
import me.shedaniel.architect.plugin.TransformerStepSkipped
import me.shedaniel.architect.plugin.utils.validateJarFs
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.util.LoggerFilter
import net.fabricmc.tinyremapper.*
import org.gradle.api.Project
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

object TransformForgeEnvironment : Transformer {
    override fun invoke(project: Project, input: Path, output: Path) {
        val remapperBuilder: TinyRemapper.Builder = TinyRemapper.newRemapper()
            .withMappings(remapEnvironment())
            .skipLocalVariableMapping(true)

        mapMixin(project, remapperBuilder, input, output)

        val classpathFiles: Set<File> = LinkedHashSet(
            project.configurations.getByName("compileClasspath").files
        )
        val classpath = classpathFiles.asSequence().map { obj: File -> obj.toPath() }
            .filter { p: Path -> input != p && Files.exists(p) }.toList().toTypedArray()
        val remapper = remapperBuilder.build()

        LoggerFilter.replaceSystemOut()
        try {
            project.validateJarFs(output)
            OutputConsumerPath.Builder(output).build().use { outputConsumer ->
                outputConsumer.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, null)
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

    private fun remapEnvironment(): IMappingProvider = IMappingProvider { out ->
        out.acceptClass("net/fabricmc/api/Environment", "net/minecraftforge/api/distmarker/OnlyIn")
        out.acceptClass("net/fabricmc/api/EnvType", "net/minecraftforge/api/distmarker/Dist")
        out.acceptField(
            IMappingProvider.Member("net/fabricmc/api/EnvType", "SERVER", "Lnet/fabricmc/api/EnvType;"),
            "DEDICATED_SERVER"
        )
    }

    private fun mapMixin(project: Project, remapperBuilder: TinyRemapper.Builder, input: Path, output: Path) {
        var remap = false
        val loomExtension = project.extensions.getByType(LoomGradleExtension::class.java)
        val srg = project.extensions.getByType(LoomGradleExtension::class.java).mappingsProvider.mappingsWithSrg
        for (mixinMapFile in loomExtension.allMixinMappings) {
            if (mixinMapFile.exists()) {
                remapperBuilder.withMappings { sink ->
                    TinyUtils.createTinyMappingProvider(mixinMapFile.toPath(), "named", "intermediary").load(object :
                        IMappingProvider.MappingAcceptor {
                        override fun acceptClass(srcName: String, dstName: String) {
                            sink.acceptClass(dstName, srg.classes
                                .firstOrNull { it.getName("intermediary") == dstName }
                                ?.getName("srg") ?: dstName
                            )
                            remap = true
                        }

                        override fun acceptMethod(method: IMappingProvider.Member, dstName: String) {
                            sink.acceptMethod(
                                IMappingProvider.Member(method.owner, dstName, method.desc),
                                srg.classes
                                    .flatMap { it.methods }
                                    .firstOrNull { it.getName("intermediary") == dstName }
                                    ?.getName("srg") ?: dstName)
                            remap = true
                        }

                        override fun acceptField(field: IMappingProvider.Member, dstName: String) {
                            sink.acceptField(
                                IMappingProvider.Member(field.owner, dstName, field.desc),
                                srg.classes
                                    .flatMap { it.fields }
                                    .firstOrNull { it.getName("intermediary") == dstName }
                                    ?.getName("srg") ?: dstName)
                            remap = true
                        }

                        override fun acceptMethodArg(method: IMappingProvider.Member, lvIndex: Int, dstName: String) {}

                        override fun acceptMethodVar(
                            method: IMappingProvider.Member,
                            lvIndex: Int,
                            startOpIdx: Int,
                            asmIndex: Int,
                            dstName: String
                        ) {
                        }
                    })
                }
            }
        }
        if (!remap) {
            Files.copy(input, output)
            throw TransformerStepSkipped
        }
    }
}