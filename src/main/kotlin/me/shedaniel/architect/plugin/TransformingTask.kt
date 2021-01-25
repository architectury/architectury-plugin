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

        transformers.forEachIndexed { index, transformer ->
            val i = if (index == 0) input else taskOutputs[index - 1]
            val o = taskOutputs[index]

            Files.deleteIfExists(o)
            Files.createDirectories(o.parent)
            runCatching {
                var skipped = false
                measureTime {
                    try {
                        transformer(project, i, o)
                    } catch (ignored: TransformerStepSkipped) {
                        skipped = true
                    }
                    if (index != 0) {
                        Files.deleteIfExists(i)
                    }
                }.let { duration ->
                    if (skipped) {
                        project.logger.lifecycle(":skipped transforming step ${index + 1}/${transformers.size} [${transformer::class.simpleName}] in $duration")
                    } else {
                        project.logger.lifecycle(":finished transforming step ${index + 1}/${transformers.size} [${transformer::class.simpleName}] in $duration")
                    }
                }
            }.onFailure {
                throw RuntimeException(
                    "Failed transformer step ${index + 1}/${transformers.size} [${transformer::class.simpleName}]",
                    it
                )
            }

            runCatching {
                o.toFile().also { it.renameTo(it) }
            }.onFailure {
                throw RuntimeException(
                    "Transformer step ${index + 1}/${transformers.size} [${transformer::class.simpleName}] did not properly close the output file!",
                    it
                )
            }
        }
        
        var tries = 0
        var exception: FileSystemException? = null
        while (tries < 10) {
            try {
                Files.move(taskOutputs.last(), output, StandardCopyOption.REPLACE_EXISTING)
            } catch (ignored: FileSystemException) {
                tries++
                if (exception == null) {
                    exception = ignored
                } else {
                    exception.addSuppressed(ignored)
                }
                Thread.sleep(1000)
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

object TransformerStepSkipped : Throwable()