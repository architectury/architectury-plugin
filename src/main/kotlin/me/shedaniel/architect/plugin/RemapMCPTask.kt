@file:Suppress("UnstableApiUsage")

package me.shedaniel.architect.plugin

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.shedaniel.architect.plugin.utils.GradleSupport
import me.shedaniel.architect.plugin.utils.validateJarFs
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.util.LoggerFilter
import net.fabricmc.loom.util.TinyRemapperMappingsHelper
import net.fabricmc.mapping.tree.TinyTree
import net.fabricmc.tinyremapper.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.zeroturnaround.zip.ZipUtil
import java.io.*
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.collections.LinkedHashSet

open class RemapMCPTask : Jar() {
    private val fromM: String = "named"
    var remapMcp = true
    var fakeMod = false
    val input: RegularFileProperty = GradleSupport.getFileProperty(project)
    private val environmentClass = "net/fabricmc/api/Environment"

    @TaskAction
    fun doTask() {
        LoggerFilter.replaceSystemOut()
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

            if (ZipUtil.containsEntry(intermediate.toFile(), "fabric.mod.json")) {
                ZipUtil.removeEntry(intermediate.toFile(), "fabric.mod.json")
            }
        }


        val classpathFiles: Set<File> = LinkedHashSet(
            project.configurations.getByName("compileClasspath").files
        )
        val classpath = classpathFiles.asSequence().map { obj: File -> obj.toPath() }
            .filter { p: Path -> input != p && Files.exists(p) }.toList().toTypedArray()

        val remapperBuilder: TinyRemapper.Builder = TinyRemapper.newRemapper()
        if (remapMcp) {
            val mappings = getMappings()
            val mojmapToMcpClass: Map<String, String> = createMojmapToMcpClass(mappings)
            remapperBuilder.withMappings(
                remapToMcp(
                    TinyRemapperMappingsHelper.create(mappings, fromM, fromM, false),
                    mojmapToMcpClass
                )
            )
            remapperBuilder.skipLocalVariableMapping(true)
        } else {
            remapperBuilder.withMappings(remapToMcp(null, null))
            remapperBuilder.skipLocalVariableMapping(true)
        }

        mapMixin(remapperBuilder)

        project.logger.lifecycle(
            ":${
                listOfNotNull(
                    "remapping".takeIf { remapMcp },
                    "transforming"
                ).joinToString(" and ")
            } " + input.fileName + " => " + output.fileName + if (fakeMod) " (with fake mod)" else ""
        )

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
            project.validateJarFs(output)
            OutputConsumerPath.Builder(output).build().use { outputConsumer ->
                outputConsumer.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, null)
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

        intermediate.toFile().delete()

        fixMixins(output.toFile())

        if (!Files.exists(output)) {
            throw RuntimeException("Failed to remap $input to $output - file missing!")
        }
    }

    private fun mapMixin(remapperBuilder: TinyRemapper.Builder) {
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
                        }

                        override fun acceptMethod(method: IMappingProvider.Member, dstName: String) {
                            sink.acceptMethod(
                                IMappingProvider.Member(method.owner, dstName, method.desc),
                                srg.classes
                                    .flatMap { it.methods }
                                    .firstOrNull { it.getName("intermediary") == dstName }
                                    ?.getName("srg") ?: dstName)
                        }

                        override fun acceptField(field: IMappingProvider.Member, dstName: String) {
                            sink.acceptField(
                                IMappingProvider.Member(field.owner, dstName, field.desc),
                                srg.classes
                                    .flatMap { it.fields }
                                    .firstOrNull { it.getName("intermediary") == dstName }
                                    ?.getName("srg") ?: dstName)
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
    }

    private fun fixMixins(output: File) {
        val loomExtension = project.extensions.getByType(LoomGradleExtension::class.java)
        val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        val mixinConfigs = mutableListOf<String>()
        val refmap = loomExtension.getRefmapName()
        ZipUtil.iterate(output) { stream, entry ->
            if (!entry.isDirectory && entry.name.endsWith(".json") &&
                !entry.name.contains("/") && !entry.name.contains("\\")
            ) {
                try {
                    InputStreamReader(stream).use { reader ->
                        val json: JsonObject? = gson.fromJson<JsonObject>(reader, JsonObject::class.java)
                        if (json != null) {
                            val hasMixins = json.has("mixins") && json["mixins"].isJsonArray
                            val hasClient = json.has("client") && json["client"].isJsonArray
                            val hasServer = json.has("server") && json["server"].isJsonArray
                            if (json.has("package") && (hasMixins || hasClient || hasServer)) {
                                mixinConfigs.add(entry.name)
                            }
                        }
                    }
                } catch (ignored: Exception) {
                }
            }
        }
        if (mixinConfigs.isNotEmpty()) {
            if (ZipUtil.containsEntry(output, "META-INF/MANIFEST.MF")) {
                ZipUtil.transformEntry(output, "META-INF/MANIFEST.MF") { input, zipEntry, out ->
                    val manifest = Manifest(input)
                    manifest.mainAttributes.putValue("MixinConfigs", mixinConfigs.joinToString(","))
                    out.putNextEntry(ZipEntry(zipEntry.name))
                    manifest.write(out)
                    out.closeEntry()
                }
            }
        }
        if (ZipUtil.containsEntry(output, refmap)) {
            ZipUtil.transformEntry(output, refmap) { input, zipEntry, out ->
                val refmapElement: JsonObject = JsonParser().parse(InputStreamReader(input)).asJsonObject.deepCopy()
                if (refmapElement.has("mappings")) {
                    refmapElement["mappings"].asJsonObject.entrySet().forEach { (_, value) ->
                        remapRefmap(value.asJsonObject)
                    }
                }
                if (refmapElement.has("data")) {
                    val data = refmapElement["data"].asJsonObject
                    if (data.has("named:intermediary")) {
                        data.add("searge", data["named:intermediary"].deepCopy().also {
                            it.asJsonObject.entrySet().forEach { (_, value) ->
                                remapRefmap(value.asJsonObject)
                            }
                        })
                        data.remove("named:intermediary")
                    }
                }
                out.putNextEntry(ZipEntry(zipEntry.name))
                out.write(gson.toJson(refmapElement).toByteArray())
                out.closeEntry()
            }
        } else {
            project.logger.warn("Failed to locate refmap: $refmap")
        }
    }

    private fun remapRefmap(obj: JsonObject) {
        val srg = project.extensions.getByType(LoomGradleExtension::class.java).mappingsProvider.mappingsWithSrg
        val methodPattern = "L(.*);(.*)(\\(.*)".toRegex()
        val methodPatternWithoutClass = "(.*)(\\(.*)".toRegex()
        val fieldPattern = "(.*):(.*)".toRegex()

        obj.keySet().forEach { key ->
            val originalRef = obj[key].asString

            val methodMatch = methodPattern.matchEntire(originalRef)
            val fieldMatch = fieldPattern.matchEntire(originalRef)
            val methodMatchWithoutClass = methodPatternWithoutClass.matchEntire(originalRef)

            when {
                methodMatch != null -> {
                    val matchedClass =
                        srg.classes.firstOrNull { it.getName("intermediary") == methodMatch.groups[1]!!.value }
                    val replacementName: String = srg.classes.asSequence()
                        .flatMap { it.methods.asSequence() }
                        .filter { it.getName("intermediary") == methodMatch.groups[2]!!.value }
                        .firstOrNull { it.getDescriptor("intermediary") == methodMatch.groups[3]!!.value }
                        ?.getName("srg") ?: methodMatch.groups[2]!!.value
                    obj.addProperty(
                        key, originalRef
                            .replaceFirst(
                                methodMatch.groups[1]!!.value,
                                matchedClass?.getName("srg") ?: methodMatch.groups[1]!!.value
                            )
                            .replaceFirst(methodMatch.groups[2]!!.value, replacementName)
                            .replaceFirst(methodMatch.groups[3]!!.value, methodMatch.groups[3]!!.value.remapDescriptor {
                                srg.classes.firstOrNull { def -> def.getName("intermediary") == it }?.getName("srg")
                                    ?: it
                            })
                    )
                }
                fieldMatch != null -> {
                    val replacementName: String = srg.classes.asSequence()
                        .flatMap { it.fields.asSequence() }
                        .filter { it.getName("intermediary") == fieldMatch.groups[1]!!.value }
                        .firstOrNull { it.getDescriptor("intermediary") == fieldMatch.groups[2]!!.value }
                        ?.getName("srg") ?: fieldMatch.groups[1]!!.value
                    obj.addProperty(
                        key, originalRef
                            .replaceFirst(fieldMatch.groups[1]!!.value, replacementName)
                            .replaceFirst(fieldMatch.groups[2]!!.value, fieldMatch.groups[2]!!.value.remapDescriptor {
                                srg.classes.firstOrNull { def -> def.getName("intermediary") == it }?.getName("srg")
                                    ?: it
                            })
                    )
                }
                methodMatchWithoutClass != null -> {
                    val replacementName: String = srg.classes.asSequence()
                        .flatMap { it.methods.asSequence() }
                        .filter { it.getName("intermediary") == methodMatchWithoutClass.groups[1]!!.value }
                        .firstOrNull { it.getDescriptor("intermediary") == methodMatchWithoutClass.groups[2]!!.value }
                        ?.getName("srg") ?: methodMatchWithoutClass.groups[1]!!.value
                    obj.addProperty(
                        key, originalRef
                            .replaceFirst(methodMatchWithoutClass.groups[1]!!.value, replacementName)
                            .replaceFirst(
                                methodMatchWithoutClass.groups[2]!!.value,
                                methodMatchWithoutClass.groups[2]!!.value.remapDescriptor {
                                    srg.classes.firstOrNull { def -> def.getName("intermediary") == it }?.getName("srg")
                                        ?: it
                                })
                    )
                }
                else -> logger.warn("Failed to remap refmap value: $originalRef")
            }
        }
    }

    private fun String.remapDescriptor(classMappings: (String) -> String): String {
        return try {
            val reader = StringReader(this)
            val result = StringBuilder()
            var insideClassName = false
            val className = StringBuilder()
            while (true) {
                val c: Int = reader.read()
                if (c == -1) {
                    break
                }
                if (c == ';'.toInt()) {
                    insideClassName = false
                    result.append(classMappings(className.toString()))
                }
                if (insideClassName) {
                    className.append(c.toChar())
                } else {
                    result.append(c.toChar())
                }
                if (!insideClassName && c == 'L'.toInt()) {
                    insideClassName = true
                    className.setLength(0)
                }
            }
            result.toString()
        } catch (e: IOException) {
            throw AssertionError(e)
        }
    }

    private fun remapToMcp(parent: IMappingProvider?, mojmapToMcpClass: Map<String, String>?): IMappingProvider =
        IMappingProvider { out ->
            out.acceptClass("net/fabricmc/api/Environment", "net/minecraftforge/api/distmarker/OnlyIn")
            out.acceptClass("net/fabricmc/api/EnvType", "net/minecraftforge/api/distmarker/Dist")
            out.acceptField(
                IMappingProvider.Member("net/fabricmc/api/EnvType", "SERVER", "Lnet/fabricmc/api/EnvType;"),
                "DEDICATED_SERVER"
            )

            parent?.load(object : IMappingProvider.MappingAcceptor {
                override fun acceptClass(srcName: String?, dstName: String?) {
                    out.acceptClass(srcName, mojmapToMcpClass!![srcName] ?: srcName)
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
    val forgeEventCancellable = "Lme/shedaniel/architectury/ForgeEventCancellable;"

    val cancellable = "Lnet/minecraftforge/eventbus/api/Cancelable;"

    private fun transform(node: ClassNode): ClassNode {
        if (node.access and Opcodes.ACC_INTERFACE == 0) {
            if (node.visibleAnnotations?.any { it.desc == forgeEvent || it.desc == forgeEventCancellable } == true) {
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
                // if @ForgeEventCancellable, add the cancellable annotation from forge
                node.visibleAnnotations.apply {
                    if (any { it.desc == forgeEventCancellable }) {
                        add(AnnotationNode(cancellable))
                    }
                }
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