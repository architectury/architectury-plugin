package dev.architectury.plugin

import dev.architectury.plugin.ModLoader.Companion.applyNeoForgeForgeLikeProd
import dev.architectury.plugin.loom.LoomInterface
import dev.architectury.plugin.utils.GradleSupport
import dev.architectury.transformer.input.OpenedFileAccess
import dev.architectury.transformer.transformers.BuiltinProperties
import dev.architectury.transformer.transformers.properties.TransformersWriter
import dev.architectury.transformer.util.LoggerFilter
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.gradle.ext.ActionDelegationConfig
import java.io.File
import java.io.StringWriter
import java.net.URI
import java.util.*
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class ArchitecturyPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val version = ArchitecturyPlugin::class.java.getPackage().implementationVersion
        val loggedVersions = System.getProperty("architectury.printed.logged", "").split(",").toMutableSet()

        if (!loggedVersions.contains(version)) {
            loggedVersions.add(version)
            System.setProperty("architectury.printed.logged", loggedVersions.joinToString(","))
            project.logger.lifecycle("Architect Plugin: $version")
        }

        LoggerFilter.replaceSystemOut()

        project.apply(
            mapOf(
                "plugin" to "java",
                "plugin" to "eclipse",
                "plugin" to "idea",
                "plugin" to "org.jetbrains.gradle.plugin.idea-ext"
            )
        )

        project.afterEvaluate {
            val ideaModel = project.extensions.getByName("idea") as IdeaModel
            val idea = ideaModel.project as? ExtensionAware
            val settings = idea?.extensions?.getByName("settings") as? ExtensionAware
            (settings?.extensions?.getByName("delegateActions") as? ActionDelegationConfig)?.apply {
                delegateBuildRunToGradle = true
                testRunner = ActionDelegationConfig.TestRunner.GRADLE
            }
        }

        val architectury = project.extensions.create("architectury", ArchitectPluginExtension::class.java, project)
        val loom = LoomInterface.get(project)
        val agentFile by lazy {
            project.gradle.rootProject.file(".gradle/architectury/architectury-transformer-agent.jar").also {
                it.parentFile.mkdirs()
            }
        }
        val mainClassTransformerFile by lazy {
            project.file(".gradle/architectury/.main_class").also {
                it.parentFile.mkdirs()
            }
        }
        val runtimeTransformerFile by lazy {
            project.file(".gradle/architectury/.transforms").also {
                it.parentFile.mkdirs()
            }
        }
        val propertiesTransformerFile = project.file(".gradle/architectury/.properties").also {
            it.parentFile.mkdirs()
        }

        // We use compileOnly on Gradle 8+, I am not sure of the consequences of using compileOnly on Gradle 7
        val gradle8: Boolean = GradleSupport.isGradle8(project)

        fun properties(platform: String): Map<String, String> = with(architectury) {
            val map = mutableMapOf(
                BuiltinProperties.MIXIN_MAPPINGS to loom.allMixinMappings.joinToString(File.pathSeparator),
                BuiltinProperties.INJECT_INJECTABLES to injectInjectables.toString(),
                BuiltinProperties.UNIQUE_IDENTIFIER to project.projectUniqueIdentifier(),
                BuiltinProperties.COMPILE_CLASSPATH to project.getCompileClasspath().joinToString(File.pathSeparator),
                BuiltinProperties.PLATFORM_NAME to platform,
                BuiltinProperties.MCMETA_VERSION to "4"
            )

            if (platform != "neoforge") {
                if (platform == "forge" && !loom.addRefmapForForge) {
                    map[BuiltinProperties.FORGE_FIX_MIXINS] = "false"
                } else if (loom.legacyMixinApEnabled) {
                    map[BuiltinProperties.REFMAP_NAME] = loom.refmapName
                }

                map[BuiltinProperties.MAPPINGS_WITH_SRG] = loom.tinyMappingsWithSrg.toString()
            }

            return map
        }

        if (architectury.platformSetupLoomIde) {
            loom.setIdeConfigGenerated()
        }

        with(architectury) {
            transforms.forEach { name,transform->
                if (!compileOnly) {
                    project.configurations.maybeCreate(transform.devConfigName)

                    if (name == "neoforge") {
                        project.configurations.maybeCreate("developmentForgeLike")
                    }
                }

                if (!transformedLoom && !compileOnly) {
                    var plsAddInjectables = false
                    project.configurations.findByName("architecturyTransformerClasspath")
                        ?: project.configurations.create("architecturyTransformerClasspath") {
                            it.extendsFrom(project.configurations.getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME))
                            plsAddInjectables = true
                        }
                    val architecturyJavaAgents = project.configurations.create("architecturyJavaAgents") {
                        project.configurations.getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME)
                            .extendsFrom(it)
                    }
                    transformedLoom = true

                    with(project.dependencies) {
                        // We are trying to not leak to consumers that we are using architectury-transformer
                        if (gradle8) {
                            val customRuntimeClasspath =
                                project.configurations.findByName("architecturyTransformerRuntimeClasspath")
                                    ?: project.configurations.create("architecturyTransformerRuntimeClasspath") {
                                        project.configurations.getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME)
                                            .extendsFrom(it)
                                    }
                            add(
                                customRuntimeClasspath.name,
                                "dev.architectury:architectury-transformer:$transformerVersion:runtime"
                            )
                        } else {
                            add(
                                JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME,
                                "dev.architectury:architectury-transformer:$transformerVersion:runtime"
                            )
                        }
                        add(
                            "architecturyJavaAgents",
                            "dev.architectury:architectury-transformer:$transformerVersion:agent"
                        )
                        if (plsAddInjectables && injectInjectables) {
                            add(
                                "architecturyTransformerClasspath",
                                "dev.architectury:architectury-injectables:$injectablesVersion"
                            )
                            add("architecturyTransformerClasspath", transform.envAnnotationProvider)?.also {
                                it as ModuleDependency
                                it.isTransitive = false
                            }
                        }
                    }

                    loom.settingsPostEdit { config ->
                        fun String.escapeSpaces(): String = config.escape(this)
                        val s = config.mainClass
                        config.mainClass = "dev.architectury.transformer.TransformerRuntime"
                        mainClassTransformerFile.writeText(s)
                        config.addVmArg("-Darchitectury.main.class=${mainClassTransformerFile.absolutePath.escapeSpaces()}")
                        config.addVmArg("-Darchitectury.runtime.transformer=${runtimeTransformerFile.absolutePath.escapeSpaces()}")
                        config.addVmArg("-Darchitectury.properties=${propertiesTransformerFile.absolutePath.escapeSpaces()}")
                        config.addVmArg("-Djdk.attach.allowAttachSelf=true")
                        if (architecturyJavaAgents.toList().size == 1) {
                            if (!agentFile.exists() || agentFile.delete()) {
                                architecturyJavaAgents.first().copyTo(agentFile, overwrite = true)
                            }
                            config.addVmArg("-javaagent:${agentFile.absolutePath.escapeSpaces()}")
                        } else {
                            throw IllegalStateException(
                                "Illegal Count of Architectury Java Agents! " + architecturyJavaAgents.toList()
                                    .joinToString(", ")
                            )
                        }
                    }
                }
            }
        }

        with(architectury) {
            if (injectInjectables && !compileOnly) {
                var plsAddInjectables = false
                project.configurations.findByName("architecturyTransformerClasspath")
                    ?: project.configurations.create("architecturyTransformerClasspath") {
                        it.extendsFrom(project.configurations.getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME))
                        plsAddInjectables = true
                    }

                with(project.dependencies) {
                    add(
                        if (gradle8) JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME else JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME,
                        "dev.architectury:architectury-injectables:$injectablesVersion"
                    )

                    if (plsAddInjectables) {
                        add(
                            "architecturyTransformerClasspath",
                            "dev.architectury:architectury-injectables:$injectablesVersion"
                        )
                        add("architecturyTransformerClasspath", "net.fabricmc:fabric-loader:+")?.also {
                            it as ModuleDependency
                            it.isTransitive = false
                        }
                    }
                }
            }

            val buildTask = project.tasks.getByName("build")
            val jarTask = project.tasks.getByName("jar") {
                it as AbstractArchiveTask
                it.archiveClassifier.set("dev")
            } as AbstractArchiveTask

            val settings = settings
            if (settings != null)
                for (loader in settings.loaders) {
                    project.configurations.maybeCreate("transformProduction${loader.titledId}")
                    val transformProductionTask =
                        project.tasks.register("transformProduction${loader.titledId}", TransformingTask::class.java) {
                            it.group = "Architectury"
                            it.platform = loader.id
                            loader.transformProduction(it, loom, settings)

                            if (settings.isForgeLike && loader.id == "neoforge") {
                                it.addPost(applyNeoForgeForgeLikeProd(loom, settings))
                            }

                            it.archiveClassifier.set("transformProduction${loader.titledId}")
                            it.input.set(jarTask.archiveFile)

                            project.artifacts.add("transformProduction${loader.titledId}", it)
                            it.dependsOn(jarTask)
                            buildTask.dependsOn(it)
                        }

                    transformProductionTask.get().archiveFile.get().asFile.takeUnless { it.exists() }?.createEmptyJar()
                }

            project.tasks.getByName("remapJar") {
                it as Jar

                it.archiveClassifier.set("")
                loom.setRemapJarInput(it, jarTask.archiveFile)
                it.dependsOn(jarTask)
                @Suppress("ObjectLiteralToLambda")
                it.doLast(object : Action<Task> {
                    override fun execute(task: Task) {
                        if (addCommonMarker) {
                            val output = it.archiveFile.get().asFile

                            try {
                                OpenedFileAccess.ofJar(output.toPath()).use { inter ->
                                    inter.addFile("architectury.common.marker", "")
                                }
                            } catch (_: Throwable) {
                                project.logger.warn("Failed to add architectury.common.marker to ${output.absolutePath}")
                            }
                        }
                    }
                })
            } as Jar
        }

        fun prepareTransformer() = with(architectury) {
            if (transforms.isNotEmpty() && !compileOnly) {
                StringWriter().also { strWriter ->
                    TransformersWriter(strWriter).use { writer ->
                        for (transform in transforms.values) {
                            project.configurations.getByName(transform.devConfigName).forEach { file ->
                                transform.transformers.map { it.apply(file.toPath()) }
                                    .forEach { pair ->
                                        writer.write(file.toPath(), pair.clazz, pair.properties)
                                    }
                            }

                            if (transform.name == "neoforge") {
                                project.configurations.getByName("developmentForgeLike").forEach { file ->
                                    (transform.transformers.map { it.apply(file.toPath()) } + ModLoader.applyNeoForgeForgeLikeDev(
                                        loom,
                                        transform
                                    ))
                                        .forEach { pair ->
                                            writer.write(file.toPath(), pair.clazz, pair.properties)
                                        }
                                }
                            }
                        }
                    }

                    runtimeTransformerFile.writeText(strWriter.toString())
                }


                val properties = Properties()
                properties(transforms.keys.first()).forEach { (key, value) ->
                    properties.setProperty(key, value)
                }
                propertiesTransformerFile.writer(Charsets.UTF_8).use {
                    properties.store(it, "Architectury Runtime Transformer Properties")
                }
            }
        }

        with(architectury) {
            project.afterEvaluate {
                if (compileOnly) return@afterEvaluate
                if (loom.generateTransformerPropertiesInTask) {
                    // Only apply if this project has the configureLaunch task.
                    // This is needed because arch plugin can also apply to the root project
                    // where the task doesn't exist and our task isn't needed either.
                    if ("configureLaunch" in project.tasks.names) {
                        val task = project.tasks.register(
                            "prepareArchitecturyTransformer",
                            PrepareArchitecturyTransformer::class.java
                        )
                        project.tasks.named("configureLaunch") {
                            it.dependsOn(task)
                        }
                    }
                } else {
                    prepareTransformer()
                }
            }

        }

        project.repositories.apply {
            mavenCentral()
            maven { it.url = URI("https://maven.architectury.dev/") }
        }
    }
}

private fun Project.getCompileClasspath(): Iterable<File> {
    return configurations.findByName("architecturyTransformerClasspath")
        ?: configurations.getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME)
}

private fun File.createEmptyJar() {
    parentFile.mkdirs()
    JarOutputStream(outputStream(), Manifest()).close()
}
