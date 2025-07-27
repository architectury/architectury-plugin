package dev.architectury.plugin

import dev.architectury.plugin.loom.LoomInterface
import dev.architectury.plugin.transformers.AddRefmapName
import dev.architectury.transformer.shadowed.impl.com.google.gson.Gson
import dev.architectury.transformer.transformers.*
import dev.architectury.transformer.transformers.base.ClassEditTransformer
import dev.architectury.transformer.util.TransformerPair

open class ModLoader(
    val id: String,
    val transformDevelopment: Transform.() -> Unit,
    val transformProduction: TransformingTask.(loom: LoomInterface, settings: ArchitectPluginExtension.CommonSettings) -> Unit,
) {
    init {
        LOADERS[id] = this
    }

    open val titledId = id.capitalize()

    companion object {
        fun valueOf(id: String): ModLoader =
            LOADERS[id] ?: throw IllegalArgumentException("No modloader with id $id")

        val LOADERS = LinkedHashMap<String, ModLoader>()
        val FABRIC = ModLoader(
            id = "fabric",
            transformDevelopment = {
                val transform = this
                this += RuntimeMixinRefmapDetector::class.java
                this += GenerateFakeFabricMod::class.java
                add(TransformExpectPlatform::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                    if (transform.platformPackage != null) {
                        this[BuiltinProperties.PLATFORM_PACKAGE] = transform.platformPackage!!
                    }
                }
                add(RemapInjectables::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                }
                this += TransformPlatformOnly::class.java
            },
            transformProduction = { _, settings ->
                this += RemapMixinVariables()
                add(TransformExpectPlatform()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                    settings.platformPackages[valueOf("fabric")]?.let { platformPackage ->
                        this[BuiltinProperties.PLATFORM_PACKAGE] = platformPackage
                    }
                }
                add(RemapInjectables()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                }
                this += AddRefmapName()
                this += TransformPlatformOnly()
            }
        )

        val FORGE = ModLoader(
            id = "forge",
            transformDevelopment = {
                val transform = this
                this += RuntimeMixinRefmapDetector::class.java
                add(TransformExpectPlatform::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                    if (transform.platformPackage != null) {
                        this[BuiltinProperties.PLATFORM_PACKAGE] = transform.platformPackage!!
                    }
                }
                add(RemapInjectables::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                }
                this += TransformPlatformOnly::class.java

                this += TransformForgeAnnotations::class.java
                this += TransformForgeEnvironment::class.java
                this += GenerateFakeForgeMod::class.java
                this += FixForgeMixin::class.java
            },
            transformProduction = { loom, settings ->
                add(TransformExpectPlatform()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                    settings.platformPackages[valueOf("forge")]?.let { platformPackage ->
                        this[BuiltinProperties.PLATFORM_PACKAGE] = platformPackage
                    }
                }
                add(RemapInjectables()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                }
                this += AddRefmapName { loom.addRefmapForForge }
                this += TransformPlatformOnly()

                this += TransformForgeAnnotations()
                this += TransformForgeEnvironment()
                this += FixForgeMixin()

                loom.generateSrgTiny = true
            }
        )

        val NEOFORGE = object : ModLoader(
            id = "neoforge",
            transformDevelopment = {
                val transform = this
                add(TransformExpectPlatform::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                    if (transform.platformPackage != null) {
                        this[BuiltinProperties.PLATFORM_PACKAGE] = transform.platformPackage!!
                    }
                }
                add(RemapInjectables::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                }
                this += TransformPlatformOnly::class.java

                this += TransformNeoForgeAnnotations::class.java
                this += TransformNeoForgeEnvironment::class.java
                this += GenerateFakeNeoForgeMod::class.java
            },
            transformProduction = { _, settings ->
                add(TransformExpectPlatform()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                    settings.platformPackages[valueOf("neoforge")]?.let { platformPackage ->
                        this[BuiltinProperties.PLATFORM_PACKAGE] = platformPackage
                    }
                }
                add(RemapInjectables()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                }
                this += TransformPlatformOnly()

                this += TransformNeoForgeAnnotations()
                this += TransformNeoForgeEnvironment()
            }
        ) {
            override val titledId: String
                get() = "NeoForge"
        }

        fun applyNeoForgeForgeLikeDev(loom: LoomInterface, transform: Transform): List<TransformerPair> {
            val properties = mutableMapOf<String, Any>()
            properties[BuiltinProperties.NEOFORGE_LIKE_REMAPS] = transform.extraForgeLikeToNeoForgeRemaps
            return listOf(TransformerPair(TransformForgeLikeToNeoForge::class.java, Gson().toJsonTree(properties).asJsonObject))
        }

        fun applyNeoForgeForgeLikeProd(loom: LoomInterface, settings: ArchitectPluginExtension.CommonSettings): ClassEditTransformer {
            val properties = mutableMapOf<String, Any>()
            properties[BuiltinProperties.NEOFORGE_LIKE_REMAPS] = settings.extraForgeLikeToNeoForgeRemaps
            val transform = TransformForgeLikeToNeoForge()
            transform.supplyProperties(Gson().toJsonTree(properties).asJsonObject)
            return transform
        }

        val QUILT = ModLoader(
            id = "quilt",
            transformDevelopment = {
                val transform = this
                this += RuntimeMixinRefmapDetector::class.java
                this += GenerateFakeQuiltMod::class.java
                add(TransformExpectPlatform::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                    if (transform.platformPackage != null) {
                        this[BuiltinProperties.PLATFORM_PACKAGE] = transform.platformPackage!!
                    }
                }
                add(RemapInjectables::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                }
                this += TransformPlatformOnly::class.java
                envAnnotationProvider = "org.quiltmc:quilt-loader:+"
            },
            transformProduction = { _, settings ->
                this += RemapMixinVariables()
                add(TransformExpectPlatform()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                    settings.platformPackages[valueOf("quilt")]?.let { platformPackage ->
                        this[BuiltinProperties.PLATFORM_PACKAGE] = platformPackage
                    }
                }
                add(RemapInjectables()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                }
                this += AddRefmapName()
                this += TransformPlatformOnly()
            }
        )
    }
}