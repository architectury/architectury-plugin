@file:Suppress("UnstableApiUsage")

package dev.architectury.plugin

import dev.architectury.plugin.loom.LoomInterface
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
import java.nio.file.Path
import java.util.*
import java.util.function.BiConsumer
import java.util.function.Function
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

open class ArchitectPluginExtension(val project: Project) {
    var transformerVersion = "5.2.61"
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
        useIfFound(
            "net.fabricmc.loom.util.service.SharedServiceManager",
            "dev.architectury.plugin.loom.LoomInterface011" // 0.11.0
        ) ?: useIfFound(
            "net.fabricmc.loom.util.ZipUtils",
            "dev.architectury.plugin.loom.LoomInterface010" // >0.10.0.188
        ) ?: useIfFound(
            "net.fabricmc.loom.api.MixinExtensionAPI",
            "dev.architectury.plugin.loom.LoomInterface010Legacy" // 0.10.0
        ) ?: useIfFound(
            "net.fabricmc.loom.api.LoomGradleExtensionAPI",
            "dev.architectury.plugin.loom.LoomInterface09" // 0.9.0
        ) ?: use("dev.architectury.plugin.loom.LoomInterface06") // 0.6.0
    }

    fun useIfFound(className: String, interfaceName: String): LoomInterface? {
        return try {
            Class.forName(className)
            use(interfaceName)
        } catch (ignored: ClassNotFoundException) {
            null
        }
    }

    fun use(interfaceName: String): LoomInterface {
        return Class.forName(interfaceName)
            .getDeclaredConstructor(Project::class.java)
            .newInstance(project) as LoomInterface
    }

    init {
        project.afterEvaluate {
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

    fun prepareTransformer() {
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

    private fun getCompileClasspath(): Iterable<File> {
        return project.configurations.findByName("architecturyTransformerClasspath")
            ?: project.configurations.getByName("compileClasspath")
    }

    fun transform(name: String, action: Action<Transform>) {
        transforms.getOrPut(name) {
            Transform(project, "development" + name.capitalize()).also { transform ->
                project.configurations.maybeCreate(transform.configName)

                if (!transformedLoom) {
                    var plsAddInjectables = false
                    project.configurations.findByName("architecturyTransformerClasspath")
                        ?: project.configurations.create("architecturyTransformerClasspath") {
                            it.extendsFrom(project.configurations.getByName("compileClasspath"))
                            plsAddInjectables = true
                        }
                    val architecturyJavaAgents = project.configurations.create("architecturyJavaAgents") {
                        project.configurations.getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME)
                            .extendsFrom(it)
                    }
                    transformedLoom = true

                    with(project.dependencies) {
                        add(
                            JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME,
                            "dev.architectury:architectury-transformer:$transformerVersion:runtime"
                        )
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
        loader(ModLoader.FABRIC, action)
    }

    @JvmOverloads
    fun forge(action: Action<Transform> = Action {}) {
        loader(ModLoader.FORGE, action)
    }

    @JvmOverloads
    fun loader(id: String, action: Action<Transform> = Action {}) {
        loader(ModLoader.valueOf(id), action)
    }

    @JvmOverloads
    fun loader(loader: ModLoader, action: Action<Transform> = Action {}) {
        transform(loader.id, Action {
            loader.transformDevelopment(it)
            action.execute(it)
        })
    }

    fun common() {
        common {}
    }

    data class CommonSettings(
        val loaders: MutableSet<ModLoader> = LinkedHashSet(),
    ) {
        constructor(loaders: Array<String>) : this() {
            this.loaders.addAll(loaders.map { ModLoader.valueOf(it) })
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

        fun add(id: Array<String>) {
            loaders.addAll(id.map { ModLoader.valueOf(it) })
        }

        fun remove(id: Array<String>) {
            loaders.removeAll { id.contains(it.id) }
        }

        fun clear() {
            loaders.clear()
        }
    }

    fun platformSetupLoomIde() {
        loom.setIdeConfigGenerated()
    }

    fun common(forgeEnabled: Boolean) {
        common {
            if (!forgeEnabled) {
                remove("forge")
            }
        }
    }

    fun common(action: CommonSettings.() -> Unit) {
        common(Action { it.action() })
    }

    fun common(platforms: Array<String>) {
        common {
            clear()
            add(platforms)
        }
    }

    fun common(action: Action<CommonSettings>) {
        val settings = CommonSettings().also {
            it.loaders += ModLoader.FABRIC
            it.loaders += ModLoader.FORGE
            action.execute(it)
        }

        if (injectInjectables) {
            var plsAddInjectables = false
            project.configurations.findByName("architecturyTransformerClasspath")
                ?: project.configurations.create("architecturyTransformerClasspath") {
                    it.extendsFrom(project.configurations.getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME))
                    plsAddInjectables = true
                }

            with(project.dependencies) {
                add(
                    JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME,
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
                    loader.transformProduction(it, loom)

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

fun String.legalizePackageName(): String =
    filter { Character.isJavaIdentifierPart(it) }

internal val ByteArray.sha256: String
    get() = Hashing.sha256().hashBytes(this).toString()
