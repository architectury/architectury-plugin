package me.shedaniel.architect.plugin.transformers

import me.shedaniel.architect.plugin.Transformer
import org.gradle.api.Project
import org.zeroturnaround.zip.ZipUtil
import java.nio.file.Files
import java.nio.file.Path

object RemoveFabricModJson : Transformer {
    override fun invoke(project: Project, input: Path, output: Path) {
        Files.copy(input, output)
        if (ZipUtil.containsEntry(output.toFile(), "fabric.mod.json")) {
            ZipUtil.removeEntry(output.toFile(), "fabric.mod.json")
        }
    }
}