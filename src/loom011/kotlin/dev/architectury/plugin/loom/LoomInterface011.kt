package dev.architectury.plugin.loom

import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.configuration.ide.RunConfig
import net.fabricmc.loom.configuration.providers.mappings.MappingsProviderImpl
import net.fabricmc.loom.task.RemapJarTask
import net.fabricmc.loom.task.service.MixinMappingsService
import net.fabricmc.loom.util.service.SharedServiceManager
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.nio.file.Path
import java.util.function.Consumer

class LoomInterface011(private val project: Project) : LoomInterface {
    private val extension: LoomGradleExtension
        get() = LoomGradleExtension.get(project)

    override val allMixinMappings: Collection<File>
        get() = extractMixinMappings(getMixinService(SharedServiceManager.get(project)))

    private fun getMixinService(serviceManager: SharedServiceManager): MixinMappingsService {
        return try {
            MixinMappingsService::class.java.getDeclaredMethod("getService", SharedServiceManager::class.java).also {
                it.isAccessible = true
            }.invoke(null, serviceManager) as MixinMappingsService
        } catch (ignored: NoSuchMethodException) {
            MixinMappingsService::class.java.getDeclaredMethod("getService", SharedServiceManager::class.java, MappingsProviderImpl::class.java).also {
                it.isAccessible = true
            }.invoke(null, serviceManager, extension.mappingsProvider) as MixinMappingsService
        }
    }

    private fun extractMixinMappings(service: MixinMappingsService): Collection<File> {
        return MixinMappingsService::class.java.getDeclaredField("mixinMappings").also {
            it.isAccessible = true
        }.get(service) as HashSet<File>
    }

    override val tinyMappingsWithSrg: Path
        get() = extension.mappingsProvider.tinyMappingsWithSrg

    override val refmapName: String
        get() = extension.mixin.defaultRefmapName.get()

    override var generateSrgTiny: Boolean
        get() = extension.shouldGenerateSrgTiny()
        set(value) {
            extension.setGenerateSrgTiny(value)
        }

    override val generateTransformerPropertiesInTask = true

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
            config.vmArgs.add(vmArg)
        }

        override fun escape(arg: String): String = arg
    }
}