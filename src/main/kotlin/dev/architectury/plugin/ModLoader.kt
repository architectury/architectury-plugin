package dev.architectury.plugin

import dev.architectury.plugin.loom.LoomInterface
import dev.architectury.plugin.transformers.AddRefmapName
import dev.architectury.transformer.transformers.*
import java.nio.charset.StandardCharsets

class ModLoader(
    val id: String,
    val transformDevelopment: Transform.() -> Unit,
    val transformProduction: TransformingTask.(loom: LoomInterface) -> Unit,
) {
    init {
        LOADERS[id] = this
    }

    val titledId = id.capitalize()

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
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] =
                        (project.projectUniqueIdentifier() + "_" + file.toString()
                            .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName()
                }
                add(RemapInjectables::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] =
                        (project.projectUniqueIdentifier() + "_" + file.toString()
                            .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName()
                }
                this += TransformPlatformOnly::class.java
            },
            transformProduction = { _ ->
                this += RemapMixinVariables()
                add(TransformExpectPlatform()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] =
                        (project.projectUniqueIdentifier() + "_" + file.toString()
                            .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName();
                }
                add(RemapInjectables()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] =
                        (project.projectUniqueIdentifier() + "_" + file.toString()
                            .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName();
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
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] =
                        (project.projectUniqueIdentifier() + "_" + file.toString()
                            .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName()
                }
                add(RemapInjectables::class.java) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] =
                        (project.projectUniqueIdentifier() + "_" + file.toString()
                            .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName()
                }
                this += TransformPlatformOnly::class.java

                this += TransformForgeAnnotations::class.java
                this += TransformForgeEnvironment::class.java
                this += GenerateFakeForgeMod::class.java
                this += FixForgeMixin::class.java
            },
            transformProduction = { loom ->
                add(TransformExpectPlatform()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] =
                        (project.projectUniqueIdentifier() + "_" + file.toString()
                            .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName();
                }
                add(RemapInjectables()) { file ->
                    this[BuiltinProperties.UNIQUE_IDENTIFIER] =
                        (project.projectUniqueIdentifier() + "_" + file.toString()
                            .toByteArray(StandardCharsets.UTF_8).sha256 + file.fileName).legalizePackageName();
                }
                this += AddRefmapName()
                this += TransformPlatformOnly()

                this += TransformForgeAnnotations()
                this += TransformForgeEnvironment()
                this += FixForgeMixin()

                loom.generateSrgTiny = true
            }
        )
    }
}