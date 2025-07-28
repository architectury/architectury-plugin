package dev.architectury.plugin

import dev.architectury.transformer.transformers.properties.TransformersWriter
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.*
import java.io.File
import java.io.StringWriter
import java.util.*

internal abstract class PrepareArchitecturyTransformer : DefaultTask() {

    @get:Internal
    val transforms: ListProperty<Transform> = project.objects.listProperty(Transform::class.java)

    @get:Input
    val fileTransformerProperties: MapProperty<String, String> =
        project.objects.mapProperty(String::class.java, String::class.java)

    @get:CompileClasspath
    val forgeLikeDevelopment: ConfigurableFileCollection = project.objects.fileCollection()

    @get:Input
    val devConfigs: MapProperty<String, ConfigurableFileCollection> =
        project.objects.mapProperty(String::class.java, ConfigurableFileCollection::class.java)

    @get:OutputFile
    val propertiesTransformerFile: RegularFileProperty = project.objects.fileProperty()

    @get:OutputFile
    val runtimeTransformerFile: RegularFileProperty = project.objects.fileProperty()

    @TaskAction
    fun run() {
        prepareTransformer(
            transforms.get(),
            fileTransformerProperties.get(),
            forgeLikeDevelopment,
            devConfigs.get(),
            propertiesTransformerFile.get().asFile,
            runtimeTransformerFile.get().asFile
        )
    }
}

internal fun prepareTransformer(
    transforms: List<Transform>,
    fileTransformerProperties: Map<String, String>,
    forgeLikeDevelopment: Iterable<File>,
    devConfigs: Map<String, Iterable<File>>,
    propertiesTransformerFile: File,
    runtimeTransformerFile: File,
) {
    val strWriter = StringWriter()
    TransformersWriter(strWriter).use { writer ->
        for (transform in transforms) {
            devConfigs[transform.devConfigName]?.forEach { file ->
                transform.transformers.map { it.apply(file.toPath()) }
                    .forEach { pair ->
                        writer.write(file.toPath(), pair.clazz, pair.properties)
                    }
            }

            if (transform.name == "neoforge") {
                forgeLikeDevelopment.forEach { file ->
                    (transform.transformers.map { it.apply(file.toPath()) } + ModLoader.applyNeoForgeForgeLikeDev(
                        transform
                    ))
                        .forEach { pair ->
                            writer.write(file.toPath(), pair.clazz, pair.properties)
                        }
                }
            }
        }
    }

    runtimeTransformerFile.writeText(strWriter.toString())


    val properties = Properties()
    properties.putAll(fileTransformerProperties)
    propertiesTransformerFile.writer(Charsets.UTF_8).use {
        properties.store(it, "Architectury Runtime Transformer Properties")
    }

}
