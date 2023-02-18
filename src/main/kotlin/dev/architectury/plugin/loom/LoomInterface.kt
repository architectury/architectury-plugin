package dev.architectury.plugin.loom

import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.nio.file.Path

interface LoomInterface {
    companion object {
        fun get(project: Project): LoomInterface {
            fun use(interfaceName: String): LoomInterface {
                return Class.forName(interfaceName)
                    .getDeclaredConstructor(Project::class.java)
                    .newInstance(project) as LoomInterface
            }

            fun useIfFound(className: String, interfaceName: String): LoomInterface? {
                return try {
                    Class.forName(className)
                    use(interfaceName)
                } catch (ignored: ClassNotFoundException) {
                    null
                }
            }

            return useIfFound(
                "net.fabricmc.loom.util.service.ScopedSharedServiceManager",
                "dev.architectury.plugin.loom.LoomInterface11" // 1.1
            ) ?: useIfFound(
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
    }

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
        
        fun escape(arg: String): String {
            if (arg.any(Char::isWhitespace)) return "\"$arg\""
            return arg
        }
    }
}