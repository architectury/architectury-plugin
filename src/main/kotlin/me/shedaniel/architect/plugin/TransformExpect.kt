package me.shedaniel.architect.plugin

import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.lang.invoke.CallSite
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlin.math.max

const val expectPlatform = "Lme/shedaniel/architectury/ExpectPlatform;"

fun transformExpectPlatform(): (ClassNode, (String, ByteArray) -> Unit) -> ClassNode = { clazz, classAdder ->
    clazz.methods.filter { method -> method?.visibleAnnotations?.any { it.desc == expectPlatform } == true }
        .forEach { method ->
            if (method.access and Opcodes.ACC_STATIC == 0) {
                System.err.println("@ExpectPlatform can only apply to static methods!")
            } else {
                method.instructions.clear()
                val endOfDesc = method.desc.lastIndexOf(')')
                val returnValue = method.desc.substring(endOfDesc + 1)
                val args = method.desc.substring(1, endOfDesc)
                var cursor = 0
                var inClass = false
                var index = 0
                while (cursor < args.length) {
                    val char = args[cursor]
                    if (inClass) {
                        if (char == ';') {
                            method.instructions.addLoad(char, index++)
                            inClass = false
                        }
                    } else when (char) {
                        '[' -> Unit
                        'L' -> inClass = true
                        else -> method.instructions.addLoad(char, index++)
                    }
                    cursor++
                }

                val methodType = MethodType.methodType(
                    CallSite::class.java,
                    MethodHandles.Lookup::class.java,
                    String::class.java,
                    MethodType::class.java
                )

                val handle = Handle(
                    Opcodes.H_INVOKESTATIC,
                    "me/shedaniel/architectury/PlatformMethods",
                    "platform",
                    methodType.toMethodDescriptorString(),
                    false
                )

                method.instructions.add(
                    InvokeDynamicInsnNode(
                        method.name,
                        method.desc,
                        handle
                    )
                )

                method.instructions.addReturn(returnValue.first { it != '[' })
                method.maxStack = max(1, index)
            }
        }

    clazz
}

private fun InsnList.addLoad(type: Char, index: Int) {
    when (type) {
        ';' -> add(
            VarInsnNode(
                Opcodes.ALOAD,
                index
            )
        )
        'I', 'S', 'B', 'C', 'Z' -> add(
            VarInsnNode(
                Opcodes.ILOAD,
                index
            )
        )
        'F' -> add(
            VarInsnNode(
                Opcodes.FLOAD,
                index
            )
        )
        'J' -> add(
            VarInsnNode(
                Opcodes.LLOAD,
                index
            )
        )
        'D' -> add(
            VarInsnNode(
                Opcodes.DLOAD,
                index
            )
        )
        else -> throw IllegalStateException("Invalid Type: $type")
    }
}

private fun InsnList.addReturn(type: Char) {
    when (type) {
        'L' -> add(
            InsnNode(
                Opcodes.ARETURN
            )
        )
        'I', 'S', 'B', 'C', 'Z' -> add(
            InsnNode(
                Opcodes.IRETURN
            )
        )
        'F' -> add(
            InsnNode(
                Opcodes.FRETURN
            )
        )
        'J' -> add(
            InsnNode(
                Opcodes.LRETURN
            )
        )
        'D' -> add(
            InsnNode(
                Opcodes.DRETURN
            )
        )
        'V' -> add(
            InsnNode(
                Opcodes.RETURN
            )
        )
    }
}