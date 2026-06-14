package dev.architectury.plugin.loom

import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.api.RunConfiguration
import net.fabricmc.loom.build.mixin.AnnotationProcessorInvoker
import net.fabricmc.loom.task.RemapJarTask
import net.fabricmc.loom.util.gradle.GradleUtils
import net.fabricmc.loom.util.gradle.SourceSetHelper
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.nio.file.Path
import java.util.function.Consumer

class LoomInterface117(private val project: Project) : LoomInterface {
    private val extension: LoomGradleExtension
        get() = LoomGradleExtension.get(project)

    override val allMixinMappings: Collection<File>
        get() {
            val files = mutableListOf<File>()
            GradleUtils.allLoomProjects(project.gradle) { proj: Project ->
                val ext = LoomGradleExtension.get(proj)

                if (this.extension.disableObfuscation() != ext.disableObfuscation()) {
                    return@allLoomProjects
                }
                if (!this.extension.disableObfuscation()) {
                    if (!this.extension.mappingConfiguration.mappingsIdentifier.equals(ext.mappingConfiguration.mappingsIdentifier)) {
                        return@allLoomProjects
                    }
                }

                for (sourceSet in SourceSetHelper.getSourceSets(proj)) {
                    val mixinMappings: File = AnnotationProcessorInvoker.getMixinMappingsForSourceSet(proj, sourceSet)
                    if (!mixinMappings.exists()) {
                        continue
                    }
                    files.add(mixinMappings)
                }
            }
            return files
        }

    override val tinyMappingsWithSrg: Path
        get() = extension.mappingConfiguration.tinyMappingsWithSrg

    override val refmapName: String
        get() = extension.mixin.defaultRefmapName.get()

    override var generateSrgTiny: Boolean
        get() = extension.shouldGenerateSrgTiny()
        set(value) {
            extension.setGenerateSrgTiny(value)
        }

    override val legacyMixinApEnabled: Boolean
        get() = extension.mixin.useLegacyMixinAp.get()
    
    
    override val addRefmapForForge: Boolean
        // Awful hack to check if the version >= 1.20.5, we don't get any info of forge version in common
        get() = !extension.minecraftProvider.versionInfo.isVersionOrNewer("2024-04-23T00:00:00+00:00")

    override val generateTransformerPropertiesInTask = true

    override val disableObfuscation: Boolean
        get() = extension.disableObfuscation()

    override fun settingsPostEdit(action: (config: LoomInterface.LoomRunConfig) -> Unit) {
        extension.settingsPostEdit.add(Consumer { c -> action(LoomRunConfigImpl(c)) })
    }

    override fun setIdeConfigGenerated() {
        extension.runConfigs.forEach { it.generateRunConfig.set(true) }
        extension.runConfigs.whenObjectAdded { it.generateRunConfig.set(true) }
        extension.addTaskBeforeRun("\$PROJECT_DIR\$/${project.name}:classes")
    }

    override fun setRemapJarInput(task: Jar, archiveFile: Provider<RegularFile>) {
        task as RemapJarTask
        task.inputFile.set(archiveFile)
    }

    class LoomRunConfigImpl(private val config: RunConfiguration) : LoomInterface.LoomRunConfig {
        override var mainClass: String
            get() = config.devLaunchMainClass.get()
            set(value) {
                config.devLaunchMainClass.set(value)
            }

        override fun addVmArg(vmArg: String) {
            config.jvmArguments.add(vmArg)
        }

        override fun escape(arg: String): String = arg
    }
}