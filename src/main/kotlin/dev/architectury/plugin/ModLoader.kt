package dev.architectury.plugin

import dev.architectury.plugin.loom.LoomInterface
import dev.architectury.plugin.transformers.AddRefmapName
import dev.architectury.transformer.transformers.*

open class ModLoader(
    val id: String,
    val transformDevelopment: Transform.() -> Unit,
    val transformProduction: TransformingTask.(loom: LoomInterface) -> Unit,
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
                this += RuntimeMixinRefmapDetector::class.java
                this += GenerateFakeFabricMod::class.java
                add(TransformExpectPlatform::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                }
                add(RemapInjectables::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                }
                this += TransformPlatformOnly::class.java
            },
            transformProduction = {
                this += RemapMixinVariables()
                add(TransformExpectPlatform()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
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
                this += RuntimeMixinRefmapDetector::class.java
                add(TransformExpectPlatform::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
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
            transformProduction = { loom ->
                add(TransformExpectPlatform()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                }
                add(RemapInjectables()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                }
                this += AddRefmapName()
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
                add(TransformExpectPlatform::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                }
                add(RemapInjectables::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                }
                this += TransformPlatformOnly::class.java

                this += TransformNeoForgeAnnotations::class.java
                this += TransformNeoForgeEnvironment::class.java
                this += GenerateFakeNeoForgeMod::class.java
            },
            transformProduction = { loom ->
                add(TransformExpectPlatform()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
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

        val QUILT = ModLoader(
            id = "quilt",
            transformDevelopment = {
                this += RuntimeMixinRefmapDetector::class.java
                this += GenerateFakeQuiltMod::class.java
                add(TransformExpectPlatform::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                }
                add(RemapInjectables::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
                }
                this += TransformPlatformOnly::class.java
                envAnnotationProvider = "org.quiltmc:quilt-loader:+"
            },
            transformProduction = {
                this += RemapMixinVariables()
                add(TransformExpectPlatform()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] = projectGeneratedPackage(project, file)
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