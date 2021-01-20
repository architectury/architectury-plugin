package me.shedaniel.architect.plugin.transformers

import me.shedaniel.architect.plugin.Transformer
import org.gradle.api.Project
import org.zeroturnaround.zip.ByteSource
import org.zeroturnaround.zip.ZipUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

object GenerateFakeFabricModJson : Transformer {
    override fun invoke(project: Project, input: Path, output: Path) {
        Files.copy(input, output)
        val fakeModId = "generated_" + UUID.randomUUID().toString().filterNot { it == '-' }.take(7)
        ZipUtil.addOrReplaceEntries(
            output.toFile(), arrayOf(
                ByteSource(
                    "fabric.mod.json", """{
  "schemaVersion": 1,
  "id": "$fakeModId",
  "name": "Generated Mod (Please Ignore)",
  "version": "1.0.0",
  "custom": {
    "fabric-loom:generated": true
  }
}""".toByteArray()
                )
            )
        )
    }
}