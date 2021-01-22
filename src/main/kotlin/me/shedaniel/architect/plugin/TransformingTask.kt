package me.shedaniel.architect.plugin

import me.shedaniel.architect.plugin.utils.GradleSupport
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import kotlin.properties.Delegates
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.nanoseconds

open class TransformingTask : Jar() {
    val input: RegularFileProperty = GradleSupport.getFileProperty(project)
    val transformers = mutableListOf<Transformer>()

    @ExperimentalTime
    @TaskAction
    fun doTask() {
        val input: Path = this.input.asFile.get().toPath()
        val taskOutputs = transformers.mapIndexed { index, _ ->
            project.file("build")
                .resolve("architectury-plugin/" + input.toFile().nameWithoutExtension + "-intermediate-${index}.jar")
                .toPath()
        }
        val output: Path = this.archiveFile.get().asFile.toPath()
        Files.deleteIfExists(output)
        transformers.forEachIndexed { index, transformer ->
            val i = if (index == 0) input else taskOutputs[index - 1]
            val o = if (index == taskOutputs.lastIndex) output else taskOutputs[index]
            Files.deleteIfExists(o)
            Files.createDirectories(o.parent)
            runCatching {
                measureTime {
                    transformer(project, i, o)
                    if (index != 0) {
                        Files.deleteIfExists(i)
                    }
                }.let { duration ->
                    project.logger.lifecycle(":finished transforming step ${index + 1}/${transformers.size} [${transformer::class.simpleName}] in $duration")
                }
            }.onFailure {
                throw RuntimeException(
                    "Failed transformer step ${index + 1}/${transformers.size} [${transformer::class.simpleName}]",
                    it
                )
            }

            runCatching {
                Files.move(o, o, StandardCopyOption.COPY_ATTRIBUTES)
            }.onFailure {
                throw RuntimeException(
                    "Transformer step ${index + 1}/${transformers.size} [${transformer::class.simpleName}] did not properly close the output file!",
                    it
                )
            }
        }
    }

    operator fun invoke(transformer: Transformer) {
        transformers.add(transformer)
    }
}

@ExperimentalTime
private inline fun measureTime(block: () -> Unit): Duration {
    val current = System.nanoTime()
    block()
    val finished = System.nanoTime()
    return (finished - current).nanoseconds
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

typealias Transformer = (project: Project, input: Path, output: Path) -> Unit