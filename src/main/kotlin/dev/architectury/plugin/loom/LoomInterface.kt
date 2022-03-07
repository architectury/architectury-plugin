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

    /**
     * Loom 0.11+ has to generate the runtime transformer properties file
     * in a separate hook that is guaranteed to run after mod dep processing.
     * (Which is a task hooked to `configureLaunch`.)
     * This is just unfortunate `afterEvaluate` ordering, sadly.
     *
     * See [architectury-loom#72](https://github.com/architectury/architectury-loom/issues/72)
     */
    val generateTransformerPropertiesInTask: Boolean

    fun settingsPostEdit(action: (config: LoomRunConfig) -> Unit)
    fun setIdeConfigGenerated()
    fun setRemapJarInput(task: Jar, archiveFile: Provider<RegularFile>)

    interface LoomRunConfig {
        var mainClass: String
        
        fun addVmArg(vmArg: String)
    }
}