package me.shedaniel.architect.plugin.transformers

import me.shedaniel.architect.plugin.Transformer
import me.shedaniel.architect.plugin.utils.Transform
import org.gradle.api.Project
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.MethodInsnNode
import java.nio.file.Path

object TransformForgeBytecode : Transformer {
    val forgeEvent = "Lme/shedaniel/architectury/ForgeEvent;"
    val forgeEventCancellable = "Lme/shedaniel/architectury/ForgeEventCancellable;"
    val cancellable = "Lnet/minecraftforge/eventbus/api/Cancelable;"

    private val environmentClass = "net/fabricmc/api/Environment"

    override fun invoke(project: Project, input: Path, output: Path) {
        Transform.transform(input, output) { node, classAdder ->
            if (node.access and Opcodes.ACC_INTERFACE == 0) {
                if (node.visibleAnnotations?.any { it.desc == forgeEvent || it.desc == forgeEventCancellable } == true) {
                    node.superName = "net/minecraftforge/eventbus/api/Event"
                    node.methods.forEach {
                        if (it.name == "<init>") {
                            for (insnNode in it.instructions) {
                                if (insnNode.opcode == Opcodes.INVOKESPECIAL) {
                                    insnNode as MethodInsnNode
                                    if (insnNode.name == "<init>" && insnNode.owner == "java/lang/Object") {
                                        insnNode.owner = "net/minecraftforge/eventbus/api/Event"
                                        break
                                    }
                                }
                            }
                        }
                    }
                    node.signature?.let {
                        node.signature = it.substringBeforeLast('L') + "Lnet/minecraftforge/eventbus/api/Event;"
                    }
                    // if @ForgeEventCancellable, add the cancellable annotation from forge
                    node.visibleAnnotations.apply {
                        if (any { it.desc == forgeEventCancellable }) {
                            add(AnnotationNode(cancellable))
                        }
                    }
                }
            }
            node.visibleAnnotations = (node.visibleAnnotations ?: mutableListOf()).apply {
                val invisibleEnvironments =
                    node.invisibleAnnotations?.filter { it.desc == "L${environmentClass};" } ?: emptyList()
                node.invisibleAnnotations?.removeAll(invisibleEnvironments)
                addAll(invisibleEnvironments)
            }
            node.fields.forEach { field ->
                field.visibleAnnotations = (field.visibleAnnotations ?: mutableListOf()).apply {
                    val invisibleEnvironments =
                        field.invisibleAnnotations?.filter { it.desc == "L${environmentClass};" } ?: emptyList()
                    field.invisibleAnnotations?.removeAll(invisibleEnvironments)
                    addAll(invisibleEnvironments)
                }
            }
            node.methods.forEach { method ->
                method.visibleAnnotations = (method.visibleAnnotations ?: mutableListOf()).apply {
                    val invisibleEnvironments =
                        method.invisibleAnnotations?.filter { it.desc == "L${environmentClass};" } ?: emptyList()
                    method.invisibleAnnotations?.removeAll(invisibleEnvironments)
                    addAll(invisibleEnvironments)
                }
            }
            node
        }
    }
}