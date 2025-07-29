package dev.architectury.plugin

import dev.architectury.plugin.ModLoader.Companion.applyNeoForgeForgeLikeProd
import dev.architectury.plugin.loom.LoomInterface
import dev.architectury.plugin.utils.gradle8
import dev.architectury.transformer.input.OpenedFileAccess
import dev.architectury.transformer.transformers.BuiltinProperties
import dev.architectury.transformer.util.LoggerFilter
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.dependencies
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.gradle.ext.ActionDelegationConfig
import java.io.File
import java.net.URI

class ArchitecturyPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.applyPlugin()
    }
}

private fun Project.applyPlugin() {
    val version = ArchitecturyPlugin::class.java.getPackage().implementationVersion
    val loggedVersions = System.getProperty("architectury.printed.logged", "").split(",").toMutableSet()

    if (!loggedVersions.contains(version)) {
        loggedVersions.add(version)
        System.setProperty("architectury.printed.logged", loggedVersions.joinToString(","))
        logger.lifecycle("Architect Plugin: $version")
    }

    LoggerFilter.replaceSystemOut()

    listOf(
        "java", "eclipse", "idea", "org.jetbrains.gradle.plugin.idea-ext",
    ).forEach {
        pluginManager.apply(it)
    }

    afterEvaluate {
        val ideaModel = extensions.getByName("idea") as IdeaModel
        val idea = ideaModel.project as? ExtensionAware
        val settings = idea?.extensions?.getByName("settings") as? ExtensionAware
        (settings?.extensions?.getByName("delegateActions") as? ActionDelegationConfig)?.apply {
            delegateBuildRunToGradle = true
            testRunner = ActionDelegationConfig.TestRunner.GRADLE
        }
    }

    val architectury = extensions.create("architectury", ArchitectPluginExtension::class.java, project)
    val loom = LoomInterface.get(project)
    val agentFile = gradle.rootProject.file(".gradle/architectury/architectury-transformer-agent.jar").also {
        it.parentFile.mkdirs()
    }

    val mainClassTransformerFile = file(".gradle/architectury/.main_class").also {
        it.parentFile.mkdirs()
    }
    val runtimeTransformerFile = file(".gradle/architectury/.transforms").also {
        it.parentFile.mkdirs()
    }

    val propertiesTransformerFile = file(".gradle/architectury/.properties").also {
        it.parentFile.mkdirs()
    }


    fun properties(platform: String): Map<String, String> = with(architectury) {
        val map = mutableMapOf(
            BuiltinProperties.MIXIN_MAPPINGS to loom.allMixinMappings.joinToString(File.pathSeparator),
            BuiltinProperties.INJECT_INJECTABLES to injectInjectables.toString(),
            BuiltinProperties.UNIQUE_IDENTIFIER to projectUniqueIdentifier(),
            BuiltinProperties.COMPILE_CLASSPATH to getCompileClasspath().joinToString(File.pathSeparator),
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


    fun configurationsSetup(): Unit = with(architectury) {
        transform?.let { transform ->
            if (!compileOnly) {
                configurations.maybeCreate(transform.devConfigName)

                if (name == "neoforge") {
                    configurations.maybeCreate("developmentForgeLike")
                }
            }

            if (!transformedLoom && !compileOnly) {
                var plsAddInjectables = false
                configurations.findByName("architecturyTransformerClasspath")
                    ?: configurations.create("architecturyTransformerClasspath") {
                        extendsFrom(configurations.getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME))
                        plsAddInjectables = true
                    }
                val architecturyJavaAgents = configurations.create("architecturyJavaAgents") {
                    configurations.getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME).extendsFrom(this)
                }
                transformedLoom = true

                dependencies {
                    // We are trying to not leak to consumers that we are using architectury-transformer
                    // We use compileOnly on Gradle 8+, I am not sure of the consequences of using compileOnly on Gradle 7
                    if (gradle8) {
                        val customRuntimeClasspath =
                            configurations.findByName("architecturyTransformerRuntimeClasspath")
                                ?: configurations.create("architecturyTransformerRuntimeClasspath") {
                                    configurations.getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME)
                                        .extendsFrom(this)
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

    fun commonSetUp() = with(architectury) {

        val settings = settings
        if (settings == null) return
        if (injectInjectables && !compileOnly) {
            var plsAddInjectables = false
            configurations.findByName("architecturyTransformerClasspath")
                ?: configurations.create("architecturyTransformerClasspath") {
                    extendsFrom(configurations.getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME))
                    plsAddInjectables = true
                }

            with(dependencies) {
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

        val buildTask = tasks.getByName("build")
        val jarTask = tasks.getByName("jar") {
            this as AbstractArchiveTask
            archiveClassifier.set("dev")
        } as AbstractArchiveTask
        logger.lifecycle("loaders {}", settings.loaders.map { it.titledId })

        for (loader in settings.loaders) {
            configurations.maybeCreate("transformProduction${loader.titledId}")
            tasks.register("transformProduction${loader.titledId}", TransformingTask::class.java) {
                group = "Architectury"
                platform = loader.id
                transformerProperties.set(properties(loader.id))
                loader.transformProduction(this, loom, settings)

                if (settings.isForgeLike && loader.id == "neoforge") {
                    addPost(applyNeoForgeForgeLikeProd(settings))
                }

                archiveClassifier.set("transformProduction${loader.titledId}")
                input.set(jarTask.archiveFile)

                artifacts.add("transformProduction${loader.titledId}", this)
                dependsOn(jarTask)
                buildTask.dependsOn(this)
            }

            tasks.getByName("remapJar") {
                this as Jar

                archiveClassifier.set("")
                loom.setRemapJarInput(this, jarTask.archiveFile)
                dependsOn(jarTask)
                doLast {
                    if (addCommonMarker) {
                        val output = archiveFile.get().asFile

                        try {
                            OpenedFileAccess.ofJar(output.toPath()).use { inter ->
                                inter.addFile("archctury.common.marker", "")
                            }
                        } catch (_: Throwable) {
                            logger.warn("Failed to add architectury.common.marker to ${output.absolutePath}")
                        }
                    }
                }
            }
        }
    }

    fun prepareTransformersSetup() = with(architectury) {
        val transform = this.transform ?: return@with
        if (compileOnly) return
        val forgeLikeConfiguration = configurations.findByName("developmentForgeLike")
        if (loom.generateTransformerPropertiesInTask) {
            // Only apply if this project has the configureLaunch task.
            // This is needed because arch plugin can also apply to the root project
            // where the task doesn't exist and our task isn't needed either.
            if ("configureLaunch" in tasks.names) {
                val task = tasks.register(
                    "prepareArchitecturyTransformer", PrepareArchitecturyTransformer::class.java
                ) {
                    this.transform.set(transform)
                    fileTransformerProperties.set(properties(transform.name))
                    forgeLikeDevelopmentConfiguration.from(
                        forgeLikeConfiguration
                    )
                    developmentConfiguration.from(configurations.getByName(transform.devConfigName))
                    this.runtimeTransformerFile.set(runtimeTransformerFile)
                    this.propertiesTransformerFile.set(propertiesTransformerFile)
                }
                tasks.named("configureLaunch") {
                    dependsOn(task)
                }
            }
        } else {
            prepareTransformer(
                transform,
                properties(transform.name),
                forgeLikeConfiguration ?: emptyList(),
                configurations.getByName(transform.devConfigName),
                propertiesTransformerFile,
                runtimeTransformerFile
            )
        }

    }


    afterEvaluate {
        if (architectury.platformSetupLoomIde) {
            loom.setIdeConfigGenerated()
        }
        configurationsSetup()
        commonSetUp()
        prepareTransformersSetup()
    }

    repositories.apply {
        mavenCentral()
        maven { url = URI("https://maven.architectury.dev/") }
    }

}

private fun Project.getCompileClasspath(): Iterable<File> {
    return configurations.findByName("architecturyTransformerClasspath")
        ?: configurations.getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME)
}

