package dev.architectury.plugin.loom

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.nio.file.Path

interface LoomInterface {
    val allMixinMappings: Collection<File>
    val tinyMappingsWithSrg: Path
    val refmapName: String
    var generateSrgTiny: Boolean

    fun settingsPostEdit(action: (config: LoomRunConfig) -> Unit)
    fun setIdeConfigGenerated()
    fun setRemapJarInput(task: Jar, archiveFile: Provider<RegularFile>)

    interface LoomRunConfig {
        var mainClass: String
        var vmArgs: String
    }
}