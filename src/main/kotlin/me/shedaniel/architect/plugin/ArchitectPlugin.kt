package me.shedaniel.architect.plugin

import me.shedaniel.architect.plugin.transformers.*
import net.fabricmc.loom.util.LoggerFilter
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.net.URI

class ArchitectPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        LoggerFilter.replaceSystemOut()

        project.apply(
            mapOf(
                "plugin" to "java",
                "plugin" to "eclipse",
                "plugin" to "idea"
            )
        )

        project.extensions.create("architectury", ArchitectPluginExtension::class.java, project)

        project.tasks.register("transformProductionFabric", TransformingTask::class.java) {
            it.group = "Architectury"
            it(RemapMixinVariables)
            it(TransformExpectPlatform)
            it(TransformInjectables)
            it(AddRefmapName)
        }

        project.tasks.register("transformDevelopmentFabric", TransformingTask::class.java) {
            it.group = "Architectury"
            it(GenerateFakeFabricModJson)
            it(TransformExpectPlatform)
            it(TransformInjectables)
        }

        project.tasks.register("transformProductionForge", TransformingTask::class.java) {
            it.group = "Architectury"
            it(RemapMixinVariables)
            it(TransformExpectPlatform)
            it(TransformInjectables)
            it(AddRefmapName)

            it(TransformForgeBytecode)
            it(RemoveFabricModJson)
            it(TransformForgeEnvironment)
            it(FixForgeMixin)
        }

        project.tasks.register("transformDevelopmentForge", TransformingTask::class.java) {
            it.group = "Architectury"
            it(TransformExpectPlatform)
            it(TransformInjectables)

            it(TransformForgeBytecode)
            it(RemoveFabricModJson)
            it(TransformForgeEnvironment)
            it(GenerateFakeForgeMod)
            it(FixForgeMixin)
        }

        project.repositories.apply {
            mavenCentral()
            jcenter()
            maven { it.url = URI("https://dl.bintray.com/shedaniel/cloth") }
        }
    }
}
