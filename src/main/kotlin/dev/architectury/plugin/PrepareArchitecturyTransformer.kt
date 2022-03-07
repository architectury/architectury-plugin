package dev.architectury.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

open class PrepareArchitecturyTransformer : DefaultTask() {
    @TaskAction
    fun run() {
        project.extensions.getByType(ArchitectPluginExtension::class.java).prepareTransformer()
    }
}
