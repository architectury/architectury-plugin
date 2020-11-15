@file:Suppress("UnstableApiUsage")

package me.shedaniel.architect.plugin

import me.shedaniel.architect.plugin.utils.GradleSupport
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.util.TinyRemapperMappingsHelper
import net.fabricmc.mapping.tree.TinyTree
import net.fabricmc.tinyremapper.IMappingProvider
import net.fabricmc.tinyremapper.NonClassCopyMode
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.collections.LinkedHashSet


open class RemapMCPTask : Jar() {
    private val fromM: String = "named"
    private val toM: String = "official"
    var fakeMod = false
    val input: RegularFileProperty = GradleSupport.getFileProperty(project)
    private val environmentClass = "net/fabricmc/api/Environment"

    @TaskAction
    fun doTask() {
        val input: Path = this.input.asFile.get().toPath()
        val intermediate: Path = input.parent.resolve(input.toFile().nameWithoutExtension + "-intermediate.jar")
        val output: Path = this.archiveFile.get().asFile.toPath()

        intermediate.toFile().delete()
        output.toFile().delete()

        if (!Files.exists(input)) {
            throw FileNotFoundException(input.toString())
        }

        run {
            val zipOutputStream = ZipOutputStream(intermediate.toFile().outputStream())
            zipOutputStream.use {
                ZipInputStream(Files.newInputStream(input)).use {
                    while (true) {
                        val entry = it.nextEntry ?: break
                        zipOutputStream.putNextEntry(ZipEntry(entry.name))
                        var allBytes = it.readBytes()
                        if (entry.name.toString().endsWith(".class")) {
                            val reader = ClassReader(allBytes)
                            if ((reader.access and Opcodes.ACC_MODULE) == 0) {
                                val node = ClassNode(Opcodes.ASM8)
                                reader.accept(node, ClassReader.EXPAND_FRAMES)
                                val writer = ClassWriter(0)
                                transform(node).accept(writer)
                                allBytes = writer.toByteArray()
                            }
                        }
                        zipOutputStream.write(allBytes)
                        zipOutputStream.closeEntry()
                    }
                }
            }
        }

        val remapperBuilder: TinyRemapper.Builder = TinyRemapper.newRemapper()

        val classpathFiles: Set<File> = LinkedHashSet(
            project.configurations.getByName("compileClasspath").files
        )
        val classpath = classpathFiles.asSequence().map { obj: File -> obj.toPath() }
            .filter { p: Path -> input != p && Files.exists(p) }.toList().toTypedArray()

        val mappings = getMappings()
        val mojmapToMcpClass = createMojmapToMcpClass(mappings)
        remapperBuilder.withMappings(
            remapToMcp(
                TinyRemapperMappingsHelper.create(mappings, fromM, fromM, false),
                mojmapToMcpClass
            )
        )
        remapperBuilder.ignoreFieldDesc(true)
        remapperBuilder.skipLocalVariableMapping(true)

        project.logger.lifecycle(":remapping " + input.fileName)

        val architectFolder = project.rootProject.buildDir.resolve("tmp/architect")
        architectFolder.deleteRecursively()
        architectFolder.mkdirs()
        val fakeModId = "generated_" + UUID.randomUUID().toString().filterNot { it == '-' }.take(7)
        if (fakeMod) {
            val modsToml = architectFolder.resolve("META-INF/mods.toml")
            modsToml.parentFile.mkdirs()
            modsToml.writeText(
                """
modLoader = "javafml"
loaderVersion = "[33,)"
license = "Generated"
[[mods]]
modId = "$fakeModId"
        """.trimIndent()
            )
            val mcmeta = architectFolder.resolve("pack.mcmeta")
            mcmeta.parentFile.mkdirs()
            mcmeta.writeText(
                """
{"pack":{"description":"Generated","pack_format":4}}
        """.trimIndent()
            )
        }

        val remapper = remapperBuilder.build()

        try {
            OutputConsumerPath.Builder(output).build().use { outputConsumer ->
                outputConsumer.addNonClassFiles(input, NonClassCopyMode.SKIP_META_INF, null)
                outputConsumer.addNonClassFiles(architectFolder.toPath(), NonClassCopyMode.UNCHANGED, null)
                remapper.readClassPath(*classpath)
                remapper.readInputs(intermediate)
                remapper.apply(outputConsumer)

                if (fakeMod) {
                    val className = "generated/$fakeModId"
                    val classWriter = ClassWriter(0)
                    classWriter.visit(52, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
                    val modAnnotation = classWriter.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", false)
                    modAnnotation.visit("value", fakeModId)
                    modAnnotation.visitEnd()
                    classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, arrayOf()).also {
                        it.visitVarInsn(Opcodes.ALOAD, 0)
                        it.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                        it.visitInsn(Opcodes.RETURN)
                        it.visitMaxs(1, 1)
                        it.visitEnd()
                    }
                    classWriter.visitEnd()
                    outputConsumer.accept(className, classWriter.toByteArray())
                }
            }
        } catch (e: Exception) {
            remapper.finish()
            throw RuntimeException("Failed to remap $input to $output", e)
        }

        architectFolder.deleteRecursively()
        remapper.finish()

//        intermediate.toFile().delete()

        if (!Files.exists(output)) {
            throw RuntimeException("Failed to remap $input to $output - file missing!")
        }
    }

    private fun remapToMcp(parent: IMappingProvider, mojmapToMcpClass: Map<String, String>): IMappingProvider =
        IMappingProvider {
            it.acceptClass("net/fabricmc/api/Environment", "net/minecraftforge/api/distmarker/OnlyIn")
            it.acceptClass("net/fabricmc/api/EnvType", "net/minecraftforge/api/distmarker/Dist")
            it.acceptField(
                IMappingProvider.Member("net/fabricmc/api/EnvType", "SERVER", "Lnet/fabricmc/api/EnvType;"),
                "DEDICATED_SERVER"
            )

            parent.load(object : IMappingProvider.MappingAcceptor {
                override fun acceptClass(srcName: String?, dstName: String?) {
                    it.acceptClass(srcName, mojmapToMcpClass[srcName] ?: srcName)
                }

                override fun acceptMethod(method: IMappingProvider.Member?, dstName: String?) {
                }

                override fun acceptMethodArg(method: IMappingProvider.Member?, lvIndex: Int, dstName: String?) {
                }

                override fun acceptMethodVar(
                    method: IMappingProvider.Member?,
                    lvIndex: Int,
                    startOpIdx: Int,
                    asmIndex: Int,
                    dstName: String?
                ) {
                }

                override fun acceptField(field: IMappingProvider.Member?, dstName: String?) {
                }
            })
        }

    private fun getMappings(): TinyTree {
        val loomExtension = project.extensions.getByType(LoomGradleExtension::class.java)
        return loomExtension.mappingsProvider.mappings
    }

    private fun getRootExtension(): ArchitectPluginExtension =
        project.rootProject.extensions.getByType(ArchitectPluginExtension::class.java)

    private fun createMojmapToMcpClass(mappings: TinyTree): Map<String, String> {
        val mcpMappings = readMCPMappings(getRootExtension().minecraft)
        val mutableMap = mutableMapOf<String, String>()
        mappings.classes.forEach { clazz ->
            val official = clazz.getName("official")
            val named = clazz.getName("named")
            val mcp = mcpMappings[official]
            if (mcp != null) {
                mutableMap[named] = mcp
            }
        }
        return mutableMap
    }

    private fun readMCPMappings(version: String): Map<String, String> {
        val file = project.rootProject.file(".gradle/mappings/mcp-$version.tsrg")
        if (file.exists().not()) {
            file.parentFile.mkdirs()
            file.writeText(URL("https://raw.githubusercontent.com/MinecraftForge/MCPConfig/master/versions/release/$version/joined.tsrg").readText())
        }
        return mutableMapOf<String, String>().also { readMappings(it, file.inputStream()) }
    }

    private fun readMappings(mutableMap: MutableMap<String, String>, inputStream: InputStream) {
        inputStream.bufferedReader().forEachLine {
            if (!it.startsWith("\t")) {
                val split = it.split(" ")
                val obf = split[0]
                val className = split[1]
                mutableMap[obf] = className
            }
        }
    }

    val forgeEvent = "Lme/shedaniel/architectury/ForgeEvent;"

    private fun transform(node: ClassNode): ClassNode {
        if (node.access and Opcodes.ACC_INTERFACE == 0 && node.visibleAnnotations?.any { it.desc == forgeEvent } == true) {
            node.superName = "net/minecraftforge/eventbus/api/Event"
            node.methods.forEach {
                if (it.name == "<init>") {
                    for (insnNode in it.instructions) {
                        if (insnNode.opcode == Opcodes.INVOKESPECIAL) {
                            insnNode as MethodInsnNode
                            if (insnNode.name == "<init>" && insnNode.owner == "java/lang/Object") {
                                insnNode.owner = "net/minecraftforge/eventbus/api/Event"
                                break
                            }
                        }
                    }
                }
            }
            node.signature?.let { 
                node.signature = it.substringBeforeLast('L') + "Lnet/minecraftforge/eventbus/api/Event;"
            }
        }
        node.visibleAnnotations = (node.visibleAnnotations ?: mutableListOf()).apply {
            val invisibleEnvironments =
                node.invisibleAnnotations?.filter { it.desc == "L${environmentClass};" } ?: emptyList()
            node.invisibleAnnotations?.removeAll(invisibleEnvironments)
            addAll(invisibleEnvironments)
        }
        node.fields.forEach { field ->
            field.visibleAnnotations = (field.visibleAnnotations ?: mutableListOf()).apply {
                val invisibleEnvironments =
                    field.invisibleAnnotations?.filter { it.desc == "L${environmentClass};" } ?: emptyList()
                field.invisibleAnnotations?.removeAll(invisibleEnvironments)
                addAll(invisibleEnvironments)
            }
        }
        node.methods.forEach { method ->
            method.visibleAnnotations = (method.visibleAnnotations ?: mutableListOf()).apply {
                val invisibleEnvironments =
                    method.invisibleAnnotations?.filter { it.desc == "L${environmentClass};" } ?: emptyList()
                method.invisibleAnnotations?.removeAll(invisibleEnvironments)
                addAll(invisibleEnvironments)
            }
        }
        return node
    }
}