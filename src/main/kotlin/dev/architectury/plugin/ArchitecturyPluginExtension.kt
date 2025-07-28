@file:Suppress("UnstableApiUsage")

package dev.architectury.plugin

import dev.architectury.transformer.Transformer
import dev.architectury.transformer.shadowed.impl.com.google.common.hash.Hashing
import dev.architectury.transformer.shadowed.impl.com.google.gson.Gson
import dev.architectury.transformer.shadowed.impl.com.google.gson.JsonObject
import dev.architectury.transformer.util.TransformerPair
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.logging.Logging
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.function.BiConsumer
import java.util.function.Function

open class ArchitectPluginExtension(private val projectPath: String, private val projectUniqueIdentifier: String) {
    constructor(project: Project) : this(project.path, project.projectUniqueIdentifier())

    var transformerVersion = "5.2.87"
    var injectablesVersion = "1.0.10"
    var minecraft = ""
    var injectInjectables = true
    var addCommonMarker = true
    internal var compileOnly = false
    internal val transforms = mutableMapOf<String, Transform>()
    internal var platformSetupLoomIde = false
    internal var settings: CommonSettings? = null
    internal var transformedLoom = false

    @Transient
    private val logger = Logging.getLogger(ArchitectPluginExtension::class.java)

    fun compileOnly() {
        if (compileOnly) {
            throw IllegalStateException("compileOnly() can only be called once for project ${projectPath}!")
        }
        compileOnly = true
        injectInjectables = false
        logger.debug("Compile only mode enabled for {}. Injectables will not be injected.", projectPath)
    }


    fun transform(name: String, action: Action<Transform>) {
        transforms.getOrPut(name) {
            Transform(
                projectUniqueIdentifier,
                name,
                "development" + (if (name == "neoforge") "NeoForge" else name.capitalize())
            ).also { transform ->
                action.execute(transform)
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
        transform(loader.id) {
            if (!compileOnly) {
                loader.transformDevelopment(it)
            }
            action.execute(it)
        }
    }

    fun common() {
        logger.warn("architectury's common() is deprecated, use common(String... platforms) instead")
        common {}
    }

    data class CommonSettings(
        val loaders: MutableSet<ModLoader> = LinkedHashSet(),
        val platformPackages: MutableMap<ModLoader, String> = mutableMapOf(),
        var isForgeLike: Boolean = false,
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
        platformSetupLoomIde = true
    }

    fun common(forgeEnabled: Boolean) {
        logger.warn("architectury's common(Boolean forgeEnabled) is deprecated, use common(String... platforms) instead")
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
        settings = CommonSettings().also {
            it.loaders += ModLoader.FABRIC
            it.loaders += ModLoader.FORGE
            action.execute(it)
        }
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


data class Transform(
    val projectUniqueIdentifier: String,
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
        add(transformer) { file, map ->
            config(map, file)
        }
    }
}

internal fun Transform.projectGeneratedPackage(file: Path): String =
    (projectUniqueIdentifier + "_" + file.toString()
        .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName()


internal fun TransformingTask.projectGeneratedPackage(file: Path): String =
    (projectUniqueIdentifier + "_" + file.toString()
        .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName()

internal fun String.legalizePackageName(): String =
    filter { Character.isJavaIdentifierPart(it) }

internal val ByteArray.sha256: String
    get() = Hashing.sha256().hashBytes(this).toString()
