package dev.architectury.plugin.loom

import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.configuration.ide.RunConfig
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.nio.file.Path
import java.util.function.Consumer

class LoomInterface06(private val project: Project) : LoomInterface {
    private val extension: LoomGradleExtension
        get() = project.extensions.getByType(LoomGradleExtension::class.java)

    override val allMixinMappings: Collection<File>
        get() = extension.allMixinMappings

    override val tinyMappingsWithSrg: Path
        get() {
            val mappingsProvider = LoomGradleExtension::class.java.getDeclaredMethod("getMappingsProvider").invoke(extension)
            return mappingsProvider.javaClass.getField("tinyMappingsWithSrg").get(mappingsProvider) as Path
        }

    override val refmapName: String
        get() = extension.getRefmapName()

    override var generateSrgTiny: Boolean
        get() = extension.generateSrgTiny
        set(value) {
            extension.generateSrgTiny = value
        }

    override fun settingsPostEdit(action: (config: LoomInterface.LoomRunConfig) -> Unit) {
        extension.settingsPostEdit.add(Consumer { c -> action(LoomRunConfigImpl(c)) })
    }

    override fun setIdeConfigGenerated() {
        extension.runConfigs.forEach { it.isIdeConfigGenerated = true }
        extension.runConfigs.whenObjectAdded { it.isIdeConfigGenerated = true }
        extension.addTaskBeforeRun("\$PROJECT_DIR\$/${project.name}:classes")
    }

    override fun setRemapJarInput(task: Jar, archiveFile: Provider<RegularFile>) {
        task as RemapJarTask
        task.input.set(archiveFile)
    }

    class LoomRunConfigImpl(private val config: RunConfig) : LoomInterface.LoomRunConfig {
        override var mainClass: String
            get() = config.mainClass
            set(value) {
                config.mainClass = value
            }

        override fun addVmArg(vmArg: String) {
            config.vmArgs += " $vmArg"
        }
    }
}