package me.shedaniel.architect.plugin

import me.shedaniel.architect.plugin.utils.GradleSupport
import me.shedaniel.architectury.transformer.Transform
import me.shedaniel.architectury.transformer.Transformer
import me.shedaniel.architectury.transformer.transformers.BuiltinProperties
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.nio.file.Path
import java.util.*
import kotlin.properties.Delegates
import kotlin.time.ExperimentalTime

open class TransformingTask : Jar() {
    @InputFile
    val input: RegularFileProperty = GradleSupport.getFileProperty(project)

    @Internal
    val transformers = mutableListOf<Transformer>()

    @ExperimentalTime
    @TaskAction
    fun doTask() {
        val input: Path = this.input.asFile.get().toPath()
        val output: Path = this.archiveFile.get().asFile.toPath()

        project.extensions.getByType(ArchitectPluginExtension::class.java).properties().forEach { (key, value) ->
            System.setProperty(key, value)
        }
        System.setProperty(BuiltinProperties.LOCATION, project.file(".gradle").absolutePath)
        Transform.runTransformers(input, output, transformers)
    }

    operator fun invoke(transformer: Transformer) {
        transformers.add(transformer)
    }

    operator fun plusAssign(transformer: Transformer) {
        transformers.add(transformer)
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
