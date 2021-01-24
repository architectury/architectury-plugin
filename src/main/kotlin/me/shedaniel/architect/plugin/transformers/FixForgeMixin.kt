package me.shedaniel.architect.plugin.transformers

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.shedaniel.architect.plugin.Transformer
import me.shedaniel.architect.plugin.TransformerStepSkipped
import net.fabricmc.loom.LoomGradleExtension
import org.gradle.api.Project
import org.zeroturnaround.zip.ZipUtil
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Manifest
import java.util.zip.ZipEntry

object FixForgeMixin : Transformer {
    override fun invoke(project: Project, input: Path, output: Path) {
        Files.copy(input, output)
        fixMixins(project, output.toFile())
    }

    private fun fixMixins(project: Project, output: File) {
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
                        remapRefmap(project, value.asJsonObject)
                    }
                }
                if (refmapElement.has("data")) {
                    val data = refmapElement["data"].asJsonObject
                    if (data.has("named:intermediary")) {
                        data.add("searge", data["named:intermediary"].deepCopy().also {
                            it.asJsonObject.entrySet().forEach { (_, value) ->
                                remapRefmap(project, value.asJsonObject)
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
            project.logger.info("Failed to locate refmap: $refmap")
            if (mixinConfigs.isEmpty()) {
                throw TransformerStepSkipped
            }
        }
    }

    private fun remapRefmap(project: Project, obj: JsonObject) {
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
                else -> project.logger.warn("Failed to remap refmap value: $originalRef")
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
}