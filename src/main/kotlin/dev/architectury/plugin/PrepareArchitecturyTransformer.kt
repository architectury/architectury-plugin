package dev.architectury.plugin

import dev.architectury.transformer.transformers.properties.TransformersWriter
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File
import java.io.StringWriter
import java.util.*

internal abstract class PrepareArchitecturyTransformer : DefaultTask() {

    @get:Internal
    val transform: Property<Transform> = project.objects.property(Transform::class.java)

    @get:Input
    val fileTransformerProperties: MapProperty<String, String> =
        project.objects.mapProperty(String::class.java, String::class.java)

    @get:CompileClasspath
    val forgeLikeDevelopmentConfiguration: ConfigurableFileCollection = project.objects.fileCollection()

    @get:CompileClasspath
    val developmentConfiguration = project.objects.fileCollection()

    @get:OutputFile
    val propertiesTransformerFile: RegularFileProperty = project.objects.fileProperty()

    @get:OutputFile
    val runtimeTransformerFile: RegularFileProperty = project.objects.fileProperty()

    @TaskAction
    fun run() {
        prepareTransformer(
            transform.get(),
            fileTransformerProperties.get(),
            forgeLikeDevelopmentConfiguration,
            developmentConfiguration,
            propertiesTransformerFile.get().asFile,
            runtimeTransformerFile.get().asFile
        )
    }
}

internal fun prepareTransformer(
    transform: Transform,
    fileTransformerProperties: Map<String, String>,
    forgeLikeDevelopment: Iterable<File>,
    devConfig: Iterable<File>,
    propertiesTransformerFile: File,
    runtimeTransformerFile: File,
) {
    val strWriter = StringWriter()
    TransformersWriter(strWriter).use { writer ->
        devConfig.forEach { file ->
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

    runtimeTransformerFile.writeText(strWriter.toString())


    val properties = Properties()
    properties.putAll(fileTransformerProperties)
    propertiesTransformerFile.writer(Charsets.UTF_8).use {
        properties.store(it, "Architectury Runtime Transformer Properties")
    }

}
