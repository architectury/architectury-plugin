package me.shedaniel.architect.plugin.transformers

import com.google.gson.JsonObject
import me.shedaniel.architectury.transformer.Transformer
import me.shedaniel.architectury.transformer.TransformerStepSkipped
import me.shedaniel.architectury.transformer.transformers.base.AssetEditTransformer
import me.shedaniel.architectury.transformer.transformers.base.edit.AssetEditSink
import me.shedaniel.architectury.transformer.transformers.base.edit.TransformerContext
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.LoomGradlePlugin
import org.gradle.api.Project
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class AddRefmapName(private val project: Project) : AssetEditTransformer {
    override fun doEdit(context: TransformerContext, sink: AssetEditSink) {
        val loomExtension = project.extensions.getByType(LoomGradleExtension::class.java)
        
        val mixins = mutableSetOf<String>()
        sink.handle { path, bytes ->
            // Check JSON file in root directory
            if (path.endsWith(".json") && !path.contains("/") && !path.contains("\\")) {
                try {
                    val json =
                        LoomGradlePlugin.GSON.fromJson(ByteArrayInputStream(bytes).reader(), JsonObject::class.java)
                    if (json != null) {
                        val hasMixins = json.has("mixins") && json["mixins"].isJsonArray
                        val hasClient = json.has("client") && json["client"].isJsonArray
                        val hasServer = json.has("server") && json["server"].isJsonArray
                        if (json.has("package") && (hasMixins || hasClient || hasServer)) {
                            if (!json.has("refmap") || !json.has("minVersion")) {
                                mixins.add(path)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        mixins.forEach { path -> 
            sink.transformFile(path) {
                val json: JsonObject = LoomGradlePlugin.GSON.fromJson<JsonObject>(
                    ByteArrayInputStream(it).reader(),
                    JsonObject::class.java
                )

                if (!json.has("refmap")) {
                    json.addProperty("refmap", loomExtension.getRefmapName())
                }

                LoomGradlePlugin.GSON.toJson(json).toByteArray()
            }
        }
    }
}