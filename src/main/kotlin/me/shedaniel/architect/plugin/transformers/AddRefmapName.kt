package me.shedaniel.architect.plugin.transformers

import com.google.gson.JsonObject
import me.shedaniel.architectury.transformer.Transform
import me.shedaniel.architectury.transformer.transformers.BuiltinProperties
import me.shedaniel.architectury.transformer.transformers.base.AssetEditTransformer
import me.shedaniel.architectury.transformer.transformers.base.edit.AssetEditSink
import me.shedaniel.architectury.transformer.transformers.base.edit.TransformerContext
import me.shedaniel.architectury.transformer.util.Logger
import net.fabricmc.loom.LoomGradlePlugin
import java.io.ByteArrayInputStream

class AddRefmapName : AssetEditTransformer {
    override fun doEdit(context: TransformerContext, sink: AssetEditSink) {
        val mixins = mutableSetOf<String>()
        sink.handle { path, bytes ->
            // Check JSON file in root directory
            if (path.endsWith(".json") && !Transform.stripLoadingSlash(path)
                    .contains("/") && !Transform.stripLoadingSlash(path).contains("\\")
            ) {
                Logger.debug("Checking whether $path is a mixin config.")
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
        if (mixins.isNotEmpty()) {
            Logger.debug("Found mixin config(s): " + java.lang.String.join(",", mixins))
        }
        val refmap = System.getProperty(BuiltinProperties.REFMAP_NAME)
        mixins.forEach { path ->
            sink.transformFile(path) {
                val json: JsonObject = LoomGradlePlugin.GSON.fromJson<JsonObject>(
                    ByteArrayInputStream(it).reader(),
                    JsonObject::class.java
                )

                if (!json.has("refmap")) {
                    Logger.debug("Injecting $refmap to $path")
                    json.addProperty("refmap", refmap)
                }

                LoomGradlePlugin.GSON.toJson(json).toByteArray()
            }
        }
    }
}