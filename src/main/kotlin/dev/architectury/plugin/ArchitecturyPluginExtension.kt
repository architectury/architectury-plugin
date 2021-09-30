@file:Suppress("UnstableApiUsage")

package dev.architectury.plugin

import dev.architectury.plugin.loom.LoomInterface
import dev.architectury.plugin.transformers.AddRefmapName
import dev.architectury.transformer.Transformer
import dev.architectury.transformer.input.OpenedFileAccess
import dev.architectury.transformer.shadowed.impl.com.google.common.hash.Hashing
import dev.architectury.transformer.shadowed.impl.com.google.gson.Gson
import dev.architectury.transformer.shadowed.impl.com.google.gson.JsonObject
import dev.architectury.transformer.transformers.*
import dev.architectury.transformer.transformers.properties.TransformersWriter
import dev.architectury.transformer.util.TransformerPair
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.*
import java.util.function.BiConsumer
import java.util.function.Function
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

open class ArchitectPluginExtension(val project: Project) {
    var transformerVersion = "5.1.57"
    var injectablesVersion = "1.0.10"
    var minecraft = ""
    var injectInjectables = true
    var addCommonMarker = true
    private val transforms = mutableMapOf<String, Transform>()
    private var transformedLoom = false
    private val agentFile by lazy {
        project.gradle.rootProject.file(".gradle/architectury/architectury-transformer-agent.jar").also {
            it.parentFile.mkdirs()
        }
    }
    private val mainClassTransformerFile by lazy {
        project.file(".gradle/architectury/.main_class").also {
            it.parentFile.mkdirs()
        }
    }
    private val runtimeTransformerFile by lazy {
        project.file(".gradle/architectury/.transforms").also {
            it.parentFile.mkdirs()
        }
    }
    private val propertiesTransformerFile by lazy {
        project.file(".gradle/architectury/.properties").also {
            it.parentFile.mkdirs()
        }
    }

    private val loom: LoomInterface by lazy {
        try {
            Class.forName("net.fabricmc.loom.api.MixinExtensionAPI")
            return@lazy Class.forName("dev.architectury.plugin.loom.LoomInterface010")
                .getDeclaredConstructor(Project::class.java)
                .newInstance(project) as LoomInterface
        } catch (ignored: ClassNotFoundException) {
            try {
                Class.forName("net.fabricmc.loom.api.LoomGradleExtensionAPI")
                return@lazy Class.forName("dev.architectury.plugin.loom.LoomInterface09")
                    .getDeclaredConstructor(Project::class.java)
                    .newInstance(project) as LoomInterface
            } catch (ignored: ClassNotFoundException) {
                return@lazy Class.forName("dev.architectury.plugin.loom.LoomInterface06")
                    .getDeclaredConstructor(Project::class.java)
                    .newInstance(project) as LoomInterface
            }
        }
    }

    init {
        project.afterEvaluate {
            if (transforms.isNotEmpty()) {
                StringWriter().also { strWriter ->
                    TransformersWriter(strWriter).use { writer ->
                        for (transform in transforms.values) {
                            project.configurations.getByName(transform.configName).forEach { file ->
                                transform.transformers.map { it.apply(file.toPath()) }
                                    .forEach { pair ->
                                        writer.write(file.toPath(), pair.clazz, pair.properties)
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
                propertiesTransformerFile.writer().use {
                    properties.store(it, "Architectury Runtime Transformer Properties")
                }
            }
        }
    }

    fun properties(platform: String): Map<String, String> {
        return mutableMapOf(
            BuiltinProperties.MIXIN_MAPPINGS to loom.allMixinMappings.joinToString(File.pathSeparator),
            BuiltinProperties.INJECT_INJECTABLES to injectInjectables.toString(),
            BuiltinProperties.UNIQUE_IDENTIFIER to project.projectUniqueIdentifier(),
            BuiltinProperties.COMPILE_CLASSPATH to getCompileClasspath().joinToString(File.pathSeparator),
            BuiltinProperties.MAPPINGS_WITH_SRG to loom.tinyMappingsWithSrg.toString(),
            "architectury.platform.name" to platform,
            BuiltinProperties.REFMAP_NAME to loom.refmapName,
            BuiltinProperties.MCMETA_VERSION to "4"
        )
    }

    private fun getCompileClasspath(): Iterable<File> {
        return project.configurations.findByName("architecturyTransformerClasspath")
            ?: project.configurations.getByName("compileClasspath")
    }

    fun transform(name: String, action: Action<Transform>) {
        transforms.getOrPut(name) {
            Transform(project, "development" + name.capitalize()).also { transform ->
                project.configurations.create(transform.configName)

                if (!transformedLoom) {
                    var plsAddInjectables = false
                    project.configurations.findByName("architecturyTransformerClasspath")
                        ?: project.configurations.create("architecturyTransformerClasspath") {
                            it.extendsFrom(project.configurations.getByName("compileClasspath"))
                            plsAddInjectables = true
                        }
                    val architecturyJavaAgents = project.configurations.create("architecturyJavaAgents") {
                        project.configurations.getByName("runtimeOnly").extendsFrom(it)
                    }
                    transformedLoom = true

                    with(project.dependencies) {
                        add("runtimeOnly", "dev.architectury:architectury-transformer:$transformerVersion:runtime")
                        add(
                            "architecturyJavaAgents",
                            "dev.architectury:architectury-transformer:$transformerVersion:agent"
                        )
                        if (plsAddInjectables && injectInjectables) {
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

                    loom.settingsPostEdit { config ->
                        val s = config.mainClass
                        config.mainClass = "dev.architectury.transformer.TransformerRuntime"
                        mainClassTransformerFile.writeText(s)
                        config.vmArgs += " -Darchitectury.main.class=${mainClassTransformerFile.absolutePath.escapeSpaces()}"
                        config.vmArgs += " -Darchitectury.runtime.transformer=${runtimeTransformerFile.absolutePath.escapeSpaces()}"
                        config.vmArgs += " -Darchitectury.properties=${propertiesTransformerFile.absolutePath.escapeSpaces()}"
                        config.vmArgs += " -Djdk.attach.allowAttachSelf=true"
                        if (architecturyJavaAgents.toList().size == 1) {
                            if (!agentFile.exists() || agentFile.delete()) {
                                architecturyJavaAgents.first().copyTo(agentFile, overwrite = true)
                            }
                            config.vmArgs += " -javaagent:${agentFile.absolutePath.escapeSpaces()}"
                        } else {
                            throw IllegalStateException(
                                "Illegal Count of Architectury Java Agents! " + architecturyJavaAgents.toList()
                                    .joinToString(", ")
                            )
                        }
                    }
                }
            }
        }.also {
            action.execute(it)
        }
    }

    private fun String.escapeSpaces(): String {
        if (any(Char::isWhitespace)) {
            return "\"$this\""
        }
        return this
    }

    @JvmOverloads
    fun fabric(action: Action<Transform> = Action {}) {
        transform("fabric", Action {
            it.setupFabricTransforms()
            action.execute(it)
        })
    }

    @JvmOverloads
    fun forge(action: Action<Transform> = Action {}) {
        transform("forge", Action {
            it.setupForgeTransforms()
            action.execute(it)
        })
    }

    fun common() {
        common {}
    }

    data class CommonSettings(
        var forgeEnabled: Boolean = true
    )

    fun platformSetupLoomIde() {
        loom.setIdeConfigGenerated()
    }

    fun common(forgeEnabled: Boolean) {
        common {
            this.forgeEnabled = forgeEnabled
        }
    }

    fun common(action: CommonSettings.() -> Unit) {
        common(Action { it.action() })
    }

    fun common(action: Action<CommonSettings>) {
        val settings = CommonSettings().also { action.execute(it) }
        if (injectInjectables) {
            var plsAddInjectables = false
            project.configurations.findByName("architecturyTransformerClasspath")
                ?: project.configurations.create("architecturyTransformerClasspath") {
                    it.extendsFrom(project.configurations.getByName("compileClasspath"))
                    plsAddInjectables = true
                }

            with(project.dependencies) {
                add("compileOnly", "dev.architectury:architectury-injectables:$injectablesVersion")

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

        addTasks()
        if (settings.forgeEnabled) {
            project.configurations.create("transformProductionForge")
        }
        project.configurations.create("transformProductionFabric")

        val buildTask = project.tasks.getByName("build")
        val jarTask = project.tasks.getByName("jar") {
            it as AbstractArchiveTask
            it.archiveClassifier.set("dev")
        } as AbstractArchiveTask

        val transformProductionFabricTask = project.tasks.getByName("transformProductionFabric") {
            it as TransformingTask

            it.archiveClassifier.set("transformProductionFabric")
            it.input.set(jarTask.archiveFile)

            project.artifacts.add("transformProductionFabric", it)
            it.dependsOn(jarTask)
            buildTask.dependsOn(it)
        } as TransformingTask

        val remapJarTask = project.tasks.getByName("remapJar") {
            it as Jar

            it.archiveClassifier.set("")
            loom.setRemapJarInput(it, jarTask.archiveFile)
            it.dependsOn(jarTask)
            it.doLast { _ ->
                if (addCommonMarker) {
                    val output = it.archiveFile.get().asFile

                    try {
                        OpenedFileAccess.ofJar(output.toPath()).use { inter ->
                            inter.addFile("architectury.common.marker", "")
                        }
                    } catch (t: Throwable) {
                        project.logger.warn("Failed to add architectury.common.marker to ${output.absolutePath}")
                    }
                }
            }
        } as Jar

        if (settings.forgeEnabled) {
            val transformProductionForgeTask = project.tasks.getByName("transformProductionForge") {
                it as TransformingTask

                it.input.set(jarTask.archiveFile)
                it.archiveClassifier.set("transformProductionForge")

                project.artifacts.add("transformProductionForge", it)
                it.dependsOn(jarTask)
                buildTask.dependsOn(it)
            } as TransformingTask

            transformProductionForgeTask.archiveFile.get().asFile.takeUnless { it.exists() }?.createEmptyJar()

            loom.generateSrgTiny = true
        }

        transformProductionFabricTask.archiveFile.get().asFile.takeUnless { it.exists() }?.createEmptyJar()
    }

    private fun addTasks() {
        project.tasks.register("transformProductionFabric", TransformingTask::class.java) {
            it.group = "Architectury"
            it.platform = "fabric"
            it += RemapMixinVariables()
            it.add(TransformExpectPlatform()) { file ->
                this[BuiltinProperties.UNIQUE_IDENTIFIER] =
                    (project.projectUniqueIdentifier() + "_" + file.toString()
                        .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName();
            }
            it.add(RemapInjectables()) { file ->
                this[BuiltinProperties.UNIQUE_IDENTIFIER] =
                    (project.projectUniqueIdentifier() + "_" + file.toString()
                        .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName();
            }
            it += AddRefmapName()
            it += TransformPlatformOnly()
        }

        project.tasks.register("transformProductionForge", TransformingTask::class.java) {
            it.group = "Architectury"
            it.platform = "forge"
            it.add(TransformExpectPlatform()) { file ->
                this[BuiltinProperties.UNIQUE_IDENTIFIER] =
                    (project.projectUniqueIdentifier() + "_" + file.toString()
                        .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName();
            }
            it.add(RemapInjectables()) { file ->
                this[BuiltinProperties.UNIQUE_IDENTIFIER] =
                    (project.projectUniqueIdentifier() + "_" + file.toString()
                        .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName();
            }
            it += AddRefmapName()
            it += TransformPlatformOnly()

            it += TransformForgeAnnotations()
            it += TransformForgeEnvironment()
            it += FixForgeMixin()
        }
    }
}

private fun File.createEmptyJar() {
    parentFile.mkdirs()
    JarOutputStream(outputStream(), Manifest()).close()
}

data class Transform(
    val project: Project,
    val configName: String,
    val transformers: MutableList<Function<Path, TransformerPair>> = mutableListOf()
) {
    fun setupFabricTransforms() {
        this += RuntimeMixinRefmapDetector::class.java
        this += GenerateFakeFabricMod::class.java
        add(TransformExpectPlatform::class.java) { file ->
            this[BuiltinProperties.UNIQUE_IDENTIFIER] =
                (project.projectUniqueIdentifier() + "_" + file.toString()
                    .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName();
        }
        add(RemapInjectables::class.java) { file ->
            this[BuiltinProperties.UNIQUE_IDENTIFIER] =
                (project.projectUniqueIdentifier() + "_" + file.toString()
                    .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName();
        }
        this += TransformPlatformOnly::class.java
    }

    fun setupForgeTransforms() {
        this += RuntimeMixinRefmapDetector::class.java
        add(TransformExpectPlatform::class.java) { file ->
            this[BuiltinProperties.UNIQUE_IDENTIFIER] =
                (project.projectUniqueIdentifier() + "_" + file.toString()
                    .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName();
        }
        add(RemapInjectables::class.java) { file ->
            this[BuiltinProperties.UNIQUE_IDENTIFIER] =
                (project.projectUniqueIdentifier() + "_" + file.toString()
                    .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName();
        }
        this += TransformPlatformOnly::class.java

        this += TransformForgeAnnotations::class.java
        this += TransformForgeEnvironment::class.java
        this += GenerateFakeForgeMod::class.java
        this += FixForgeMixin::class.java
    }

    operator fun plusAssign(transformer: TransformerPair) {
        transformers.add(Function { transformer })
    }

    operator fun <T : Transformer> plusAssign(transformer: Class<T>) {
        this += TransformerPair(transformer, null)
    }

    fun <T : Transformer> add(transformer: Class<T>) {
        this += TransformerPair(transformer, null)
    }

    fun <T : Transformer> add(transformer: Class<T>, properties: JsonObject) =
        plusAssign(TransformerPair(transformer, properties))

    fun <T : Transformer> add(transformer: Class<T>, config: BiConsumer<Path, MutableMap<String, Any>>) {
        transformers.add(Function { file ->
            val properties = mutableMapOf<String, Any>()
            config.accept(file, properties)
            TransformerPair(transformer, Gson().toJsonTree(properties).asJsonObject)
        })
    }

    fun <T : Transformer> add(transformer: Class<T>, config: MutableMap<String, Any>.(file: Path) -> Unit) {
        add(transformer, BiConsumer { file, map ->
            config(map, file)
        })
    }
}

fun String.legalizePackageName(): String =
    filter { Character.isJavaIdentifierPart(it) }

private val ByteArray.sha256: String
    get() = Hashing.sha256().hashBytes(this).toString()
