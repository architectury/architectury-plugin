package dev.architectury.plugin

import dev.architectury.plugin.utils.GradleSupport
import dev.architectury.transformer.Transform
import dev.architectury.transformer.Transformer
import dev.architectury.transformer.shadowed.impl.com.google.gson.Gson
import dev.architectury.transformer.transformers.BuiltinProperties
import dev.architectury.transformer.util.Logger
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.nio.file.Path
import java.util.*
import java.util.function.BiConsumer
import kotlin.properties.Delegates
import kotlin.time.ExperimentalTime

open class TransformingTask : Jar() {
    @InputFile
    val input: RegularFileProperty = GradleSupport.getFileProperty(project)

    @Internal
    val transformers: ListProperty<Transformer> = project.objects.listProperty(Transformer::class.java)

    @Internal
    var platform: String? = null

    @ExperimentalTime
    @TaskAction
    fun doTask() {
        val input: Path = this.input.asFile.get().toPath()
        val output: Path = this.archiveFile.get().asFile.toPath()

        val extension = project.extensions.getByType(ArchitectPluginExtension::class.java)
        extension.properties(platform ?: throw NullPointerException("No Platform specified")).forEach { (key, value) ->
            System.setProperty(key, value)
        }
        System.setProperty(BuiltinProperties.LOCATION, project.file(".gradle").absolutePath)
        Logger.debug("")
        Logger.debug("============================")
        Logger.debug("Transforming from $input to $output")
        Logger.debug("============================")
        Logger.debug("")
        Transform.runTransformers(input, output, transformers.get())
    }

    operator fun invoke(transformer: Transformer) {
        transformers.add(transformer)
    }

    operator fun plusAssign(transformer: Transformer) {
        transformers.add(transformer)
    }

    fun add(transformer: Transformer, config: BiConsumer<Path, MutableMap<String, Any>>) {
        transformers.add(project.provider {
            val properties = mutableMapOf<String, Any>()
            config.accept(input.asFile.get().toPath(), properties)
            transformer.supplyProperties(Gson().toJsonTree(properties).asJsonObject)
            transformer
        })
    }

    fun add(transformer: Transformer, config: MutableMap<String, Any>.(file: Path) -> Unit) {
        add(transformer, BiConsumer { file, map ->
            config(map, file)
        })
    }
}

fun Project.projectUniqueIdentifier(): String {
    val cache = File(project.file(".gradle"), "architectury-cache")
    cache.mkdirs()
    val uniqueIdFile = File(cache, "projectID")
    var id by Delegates.notNull<String>()
    if (uniqueIdFile.exists()) {
        id = uniqueIdFile.readText()
    } else {
        id = UUID.randomUUID().toString().filterNot { it == '-' }
        uniqueIdFile.writeText(id)
    }
    var name = project.name
    if (project.rootProject != project) name = project.rootProject.name + "_" + name
    return "architectury_inject_${name}_$id".filter { Character.isJavaIdentifierPart(it) }
}

class Epic : RuntimeException()
