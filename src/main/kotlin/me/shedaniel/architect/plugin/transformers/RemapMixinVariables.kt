package me.shedaniel.architect.plugin.transformers

import me.shedaniel.architectury.transformer.shadowed.impl.net.fabricmc.tinyremapper.IMappingProvider
import me.shedaniel.architectury.transformer.shadowed.impl.net.fabricmc.tinyremapper.TinyUtils
import me.shedaniel.architectury.transformer.transformers.base.TinyRemapperTransformer
import net.fabricmc.loom.LoomGradleExtension
import org.gradle.api.Project
import java.io.File

class RemapMixinVariables(private val project: Project) : TinyRemapperTransformer {
    override fun collectMappings(): MutableList<IMappingProvider> {
        val loomExtension = project.extensions.getByType(LoomGradleExtension::class.java)
        return loomExtension.allMixinMappings.asSequence().filter(File::exists).map {
            TinyUtils.createTinyMappingProvider(
                it.toPath(),
                "named",
                "intermediary"
            )
        }.toMutableList()
    }
}