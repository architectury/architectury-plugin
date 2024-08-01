@file:Suppress("UnstableApiUsage")

package dev.architectury.plugin

import dev.architectury.plugin.ModLoader.Companion.applyNeoForgeForgeLikeProd
import dev.architectury.plugin.loom.LoomInterface
import dev.architectury.plugin.utils.GradleSupport
import dev.architectury.transformer.Transformer
import dev.architectury.transformer.input.OpenedFileAccess
import dev.architectury.transformer.shadowed.impl.com.google.common.hash.Hashing
import dev.architectury.transformer.shadowed.impl.com.google.gson.Gson
import dev.architectury.transformer.shadowed.impl.com.google.gson.JsonObject
import dev.architectury.transformer.transformers.BuiltinProperties
import dev.architectury.transformer.transformers.properties.TransformersWriter
import dev.architectury.transformer.util.TransformerPair
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.plugins.JavaPlugin
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
    var transformerVersion = "5.2.86"
    var injectablesVersion = "1.0.10"
    var minecraft = ""
    internal var forgeUsesMojangMappings = false
    private var compileOnly = false
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
    private val gradle8: Boolean by lazy {
        // We use compileOnly on Gradle 8+, I am not sure of the consequences of using compileOnly on Gradle 7
        GradleSupport.isGradle8(project)
    }
    private val loom: LoomInterface by lazy {
        LoomInterface.get(project)
    }

    init {
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

    fun compileOnly() {
        if (compileOnly) {
            throw IllegalStateException("compileOnly() can only be called once for project ${project.path}!")
        }
        compileOnly = true
        injectInjectables = false
        project.logger.debug("Compile only mode enabled for ${project.path}. Injectables will not be injected.")
    }

    fun properties(platform: String): Map<String, String> {
        val map = mutableMapOf(
            BuiltinProperties.MIXIN_MAPPINGS to loom.allMixinMappings.joinToString(File.pathSeparator),
            BuiltinProperties.INJECT_INJECTABLES to injectInjectables.toString(),
            BuiltinProperties.UNIQUE_IDENTIFIER to project.projectUniqueIdentifier(),
            BuiltinProperties.COMPILE_CLASSPATH to getCompileClasspath().joinToString(File.pathSeparator),
            BuiltinProperties.PLATFORM_NAME to platform,
            BuiltinProperties.MCMETA_VERSION to "4"
        )

        if (platform != "neoforge") {
            if (loom.legacyMixinApEnabled) {
                map[BuiltinProperties.REFMAP_NAME] = loom.refmapName
            }

            map[BuiltinProperties.MAPPINGS_WITH_SRG] = loom.tinyMappingsWithSrg.toString()
        }

        map[BuiltinProperties.FORGE_FIX_MIXINS] = (!this.forgeUsesMojangMappings).toString()

        return map
    }

    fun prepareTransformer() {
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
                                (transform.transformers.map { it.apply(file.toPath()) } + ModLoader.applyNeoForgeForgeLikeDev(loom, transform))
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

    private fun getCompileClasspath(): Iterable<File> {
        return project.configurations.findByName("architecturyTransformerClasspath")
            ?: project.configurations.getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME)
    }

    fun transform(name: String, action: Action<Transform>) {
        transforms.getOrPut(name) {
            Transform(project, name, "development" + (if (name == "neoforge") "NeoForge" else name.capitalize())).also { transform ->
                if (!compileOnly) {
                    project.configurations.maybeCreate(transform.devConfigName)

                    if (name == "neoforge") {
                        project.configurations.maybeCreate("developmentForgeLike")
                    }
                }
                action.execute(transform)

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
                            val customRuntimeClasspath = project.configurations.findByName("architecturyTransformerRuntimeClasspath")
                                ?: project.configurations.create("architecturyTransformerRuntimeClasspath") {
                                    project.configurations.getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME).extendsFrom(it)
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
    }

    @JvmOverloads
    fun fabric(action: Action<Transform> = Action {}) {
        loader(ModLoader.FABRIC, action)
    }

    @JvmOverloads
    fun forge(action: Action<Transform> = Action {}) {
        loader(ModLoader.FORGE, action)
    }

    @JvmOverloads
    fun neoForge(action: Action<Transform> = Action {}) {
        loader(ModLoader.NEOFORGE, action)
    }

    @JvmOverloads
    fun loader(id: String, action: Action<Transform> = Action {}) {
        loader(ModLoader.valueOf(id), action)
    }

    @JvmOverloads
    fun loader(loader: ModLoader, action: Action<Transform> = Action {}) {
        transform(loader.id, Action {
            if (!compileOnly) {
                loader.transformDevelopment(it)
            }
            action.execute(it)
        })
    }

    fun common() {
        project.logger.warn("architectury's common() is deprecated, use common(String... platforms) instead")
        common {}
    }

    data class CommonSettings(
        val loaders: MutableSet<ModLoader> = LinkedHashSet(),
        val platformPackages: MutableMap<ModLoader, String> = mutableMapOf(),
        var isForgeLike: Boolean = false,
        var forgeUsesMojangMappings : Boolean = false,
        val extraForgeLikeToNeoForgeRemaps: MutableMap<String, String> = mutableMapOf(),
    ) {
        constructor(loaders: Array<String>) : this() {
            this.loaders.addAll(loaders.map { ModLoader.valueOf(it) })
        }

        fun remapForgeLike(remap: String, to: String) {
            extraForgeLikeToNeoForgeRemaps[remap] = to
        }

        @Deprecated("Use add and remove directly")
        var forgeEnabled: Boolean
            get() = loaders.any { it.id == "forge" }
            set(value) {
                if (value) {
                    loaders.add(ModLoader.FORGE)
                } else {
                    loaders.removeAll { it.id == "forge" }
                }
            }

        fun add(id: String) {
            loaders.add(ModLoader.valueOf(id))
        }

        fun remove(id: String) {
            loaders.removeAll { it.id == id }
        }

        fun add(vararg id: String) {
            loaders.addAll(id.map { ModLoader.valueOf(it) })
        }

        fun remove(vararg id: String) {
            loaders.removeAll { id.contains(it.id) }
        }

        fun add(id: Iterable<String>) {
            loaders.addAll(id.map { ModLoader.valueOf(it) })
        }

        fun remove(id: Iterable<String>) {
            loaders.removeAll { id.contains(it.id) }
        }

        fun clear() {
            loaders.clear()
        }

        fun platformPackage(loader: String, packageName: String) {
            platformPackages[ModLoader.valueOf(loader)] = packageName
        }

        fun platformPackage(loader: ModLoader, packageName: String) {
            platformPackages[loader] = packageName
        }
    }

    fun platformSetupLoomIde() {
        loom.setIdeConfigGenerated()
    }

    fun common(forgeEnabled: Boolean) {
        project.logger.warn("architectury's common(Boolean forgeEnabled) is deprecated, use common(String... platforms) instead")
        common {
            if (!forgeEnabled) {
                remove("forge")
            }
        }
    }

    fun common(action: CommonSettings.() -> Unit) {
        common(Action { it.action() })
    }

    @JvmOverloads
    fun common(vararg platforms: String, action: CommonSettings.() -> Unit = {}) {
        common {
            clear()
            add(*platforms)
            action(this)
        }
    }

    @JvmOverloads
    fun common(platforms: Iterable<String>, action: CommonSettings.() -> Unit = {}) {
        common {
            clear()
            add(platforms)
            action(this)
        }
    }

    fun common(action: Action<CommonSettings>) {
        val settings = CommonSettings().also {
            it.loaders += ModLoader.FABRIC
            it.loaders += ModLoader.FORGE
            action.execute(it)
        }
        this.forgeUsesMojangMappings = settings.forgeUsesMojangMappings

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

        val remapJarTask = project.tasks.getByName("remapJar") {
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
                        } catch (t: Throwable) {
                            project.logger.warn("Failed to add architectury.common.marker to ${output.absolutePath}")
                        }
                    }
                }
            })
        } as Jar
    }

    fun forgeLike(action: Action<CommonSettings>) {
        common {
            clear()
            isForgeLike = true
            action.execute(this)
        }
    }

    @JvmOverloads
    fun forgeLike(platforms: Iterable<String>, action: CommonSettings.() -> Unit = {}) {
        forgeLike {
            it.add(platforms)
            action(it)
        }
    }
}

private fun File.createEmptyJar() {
    parentFile.mkdirs()
    JarOutputStream(outputStream(), Manifest()).close()
}

data class Transform(
    val project: Project,
    val name: String,
    val devConfigName: String,
    val transformers: MutableList<Function<Path, TransformerPair>> = mutableListOf(),
    var envAnnotationProvider: String = "net.fabricmc:fabric-loader:+",
    var platformPackage: String? = null,
    val extraForgeLikeToNeoForgeRemaps: MutableMap<String, String> = mutableMapOf(),
) {
    fun remapForgeLike(remap: String, to: String) {
        extraForgeLikeToNeoForgeRemaps[remap] = to
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
        add(transformer, BiConsumer<Path, MutableMap<String, Any>> { file, map ->
            config(map, file)
        })
    }
}

fun projectGeneratedPackage(project: Project, file: Path): String =
    (project.projectUniqueIdentifier() + "_" + file.toString()
        .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName()

fun String.legalizePackageName(): String =
    filter { Character.isJavaIdentifierPart(it) }

internal val ByteArray.sha256: String
    get() = Hashing.sha256().hashBytes(this).toString()
