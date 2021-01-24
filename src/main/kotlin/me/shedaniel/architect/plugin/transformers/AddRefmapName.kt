package me.shedaniel.architect.plugin.transformers

import me.shedaniel.architect.plugin.Transformer
import me.shedaniel.architect.plugin.TransformerStepSkipped
import net.fabricmc.loom.LoomGradleExtension
import org.gradle.api.Project
import java.nio.file.Files
import java.nio.file.Path

object AddRefmapName : Transformer {
    override fun invoke(project: Project, input: Path, output: Path) {
        Files.copy(input, output)
        val loomExtension = project.extensions.getByType(LoomGradleExtension::class.java)
        var refmapHelperClass: Class<*>? = null
        runCatching {
            refmapHelperClass = Class.forName("net.fabricmc.loom.util.MixinRefmapHelper")
        }.onFailure {
            runCatching {
                refmapHelperClass = Class.forName("net.fabricmc.loom.build.MixinRefmapHelper")
            }.onFailure {
                throw ClassNotFoundException("Failed to find MixinRefmapHelper!")
            }
        }

        val method = refmapHelperClass!!.getDeclaredMethod(
            "addRefmapName",
            String::class.java,
            String::class.java,
            Path::class.java
        )
        if (
            method.invoke(
                null,
                loomExtension.getRefmapName(),
                loomExtension.mixinJsonVersion,
                output
            ) as Boolean
        ) {
            project.logger.debug("Transformed mixin reference maps in output JAR!")
        } else {
            throw TransformerStepSkipped
        }
    }
}