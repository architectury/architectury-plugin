package dev.architectury.plugin.transformers

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dev.architectury.transformer.Transform
import dev.architectury.transformer.input.FileAccess
import dev.architectury.transformer.transformers.BuiltinProperties
import dev.architectury.transformer.transformers.base.AssetEditTransformer
import dev.architectury.transformer.transformers.base.edit.TransformerContext
import dev.architectury.transformer.util.Logger
import java.io.ByteArrayInputStream

class AddRefmapName : AssetEditTransformer {
    val gson = GsonBuilder().setPrettyPrinting().create()
    override fun doEdit(context: TransformerContext, output: FileAccess) {
        val refmap = System.getProperty(BuiltinProperties.REFMAP_NAME) ?: return
        val mixins = mutableSetOf<String>()
        output.handle { path, bytes ->
            // Check JSON file in root directory
            if (path.endsWith(".json") && !Transform.trimLeadingSlash(path)
                    .contains("/") && !Transform.trimLeadingSlash(path).contains("\\")
            ) {
                Logger.debug("Checking whether $path is a mixin config.")
                try {
                    val json = gson.fromJson(ByteArrayInputStream(bytes).reader(), JsonObject::class.java)
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
        mixins.forEach { path ->
            output.modifyFile(path) {
                val json: JsonObject = gson.fromJson<JsonObject>(
                    ByteArrayInputStream(it).reader(),
                    JsonObject::class.java
                )

                if (!json.has("refmap")) {
                    Logger.debug("Injecting $refmap to $path")
                    json.addProperty("refmap", refmap)
                }

                gson.toJson(json).toByteArray()
            }
        }
    }
}