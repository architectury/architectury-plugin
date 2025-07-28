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

        private fun Transform.commonAction() {
            val transform = this@commonAction
            add(TransformExpectPlatform::class.java) { file ->
                this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage( file)
                if (transform.platformPackage != null) {
                    this[BuiltinProperties.PLATFORM_PACKAGE] = transform.platformPackage!!
                }
            }
            add(RemapInjectables::class.java) { file ->
                this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage( file)
            }
            this += TransformPlatformOnly::class.java
        }

        fun valueOf(id: String): ModLoader =
            LOADERS[id] ?: throw IllegalArgumentException("No modloader with id $id")

        val LOADERS = LinkedHashMap<String, ModLoader>()
        val FABRIC = ModLoader(
            id = "fabric",
            transformDevelopment = {
                this += RuntimeMixinRefmapDetector::class.java
                this += GenerateFakeFabricMod::class.java
                commonAction()
            },
            transformProduction = { _, settings ->
                this += RemapMixinVariables()
                add(TransformExpectPlatform()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage( file)
                    settings.platformPackages[valueOf("fabric")]?.let { platformPackage ->
                        this[BuiltinProperties.PLATFORM_PACKAGE] = platformPackage
                    }
                }
                add(RemapInjectables()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage( file)
                }
                this += AddRefmapName()
                this += TransformPlatformOnly()
            }
        )

        val FORGE = ModLoader(
            id = "forge",
            transformDevelopment = {
                this += RuntimeMixinRefmapDetector::class.java
                commonAction()

                this += TransformForgeAnnotations::class.java
                this += TransformForgeEnvironment::class.java
                this += GenerateFakeForgeMod::class.java
                this += FixForgeMixin::class.java
            },
            transformProduction = { loom, settings ->
                add(TransformExpectPlatform()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage( file)
                    settings.platformPackages[valueOf("forge")]?.let { platformPackage ->
                        this[BuiltinProperties.PLATFORM_PACKAGE] = platformPackage
                    }
                }
                add(RemapInjectables()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(
                         file)
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
                commonAction()

                this += TransformNeoForgeAnnotations::class.java
                this += TransformNeoForgeEnvironment::class.java
                this += GenerateFakeNeoForgeMod::class.java
            },
            transformProduction = { _, settings ->
                add(TransformExpectPlatform()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage( file)
                    settings.platformPackages[valueOf("neoforge")]?.let { platformPackage ->
                        this[BuiltinProperties.PLATFORM_PACKAGE] = platformPackage
                    }
                }
                add(RemapInjectables()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage( file)
                }
                this += TransformPlatformOnly()

                this += TransformNeoForgeAnnotations()
                this += TransformNeoForgeEnvironment()
            }
        ) {
            override val titledId: String
                get() = "NeoForge"
        }

        internal fun applyNeoForgeForgeLikeDev(transform: Transform): List<TransformerPair> {
            val properties = mutableMapOf<String, Any>()
            properties[BuiltinProperties.NEOFORGE_LIKE_REMAPS] = transform.extraForgeLikeToNeoForgeRemaps
            return listOf(
                TransformerPair(
                    TransformForgeLikeToNeoForge::class.java,
                    Gson().toJsonTree(properties).asJsonObject
                )
            )
        }

        internal fun applyNeoForgeForgeLikeProd(
            settings: ArchitectPluginExtension.CommonSettings
        ): ClassEditTransformer {
            val properties = mutableMapOf<String, Any>()
            properties[BuiltinProperties.NEOFORGE_LIKE_REMAPS] = settings.extraForgeLikeToNeoForgeRemaps
            val transform = TransformForgeLikeToNeoForge()
            transform.supplyProperties(Gson().toJsonTree(properties).asJsonObject)
            return transform
        }

        val QUILT = ModLoader(
            id = "quilt",
            transformDevelopment = {
                this += RuntimeMixinRefmapDetector::class.java
                this += GenerateFakeQuiltMod::class.java
                commonAction()
                envAnnotationProvider = "org.quiltmc:quilt-loader:+"
            },
            transformProduction = { _, settings ->
                this += RemapMixinVariables()
                add(TransformExpectPlatform()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage( file)
                    settings.platformPackages[valueOf("quilt")]?.let { platformPackage ->
                        this[BuiltinProperties.PLATFORM_PACKAGE] = platformPackage
                    }
                }
                add(RemapInjectables()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage( file)
                }
                this += AddRefmapName()
                this += TransformPlatformOnly()
            }
        )
    }
}