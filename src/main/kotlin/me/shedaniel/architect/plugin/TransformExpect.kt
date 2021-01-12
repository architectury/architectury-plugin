package me.shedaniel.architect.plugin

import me.shedaniel.architect.plugin.utils.ClassTransformer
import me.shedaniel.architect.plugin.utils.Transform
import org.gradle.api.Project
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.*
import org.zeroturnaround.zip.ZipUtil
import java.io.File
import java.io.InputStream
import java.lang.invoke.CallSite
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.*
import java.util.zip.ZipEntry
import kotlin.properties.Delegates

const val expectPlatform = "Lme/shedaniel/architectury/ExpectPlatform;"
const val expectPlatformNew = "Lme/shedaniel/architectury/annotations/ExpectPlatform;"

fun Project.projectUniqueIdentifier(): String {
    val cache = File(project.file(".gradle"), "architectury-cache")
    cache.mkdirs()
    val uniqueIdFile = File(cache, "projectID")
    var id by Delegates.notNull<String>()
    if (uniqueIdFile.exists()) {
        id = uniqueIdFile.readText()
    } else {
        id = UUID.randomUUID().toString().filterNot { it == '-' }
        uniqueIdFile.writeText(id)
    }
    var name = project.name
    if (project.rootProject != project) name = project.rootProject.name + "_" + name
    return "architectury_inject_${name}_$id".filter { Character.isJavaIdentifierPart(it) }
}

fun transformExpectPlatform(project: Project): ClassTransformer {
    val projectUniqueIdentifier by lazy { project.projectUniqueIdentifier() }
    var injectedClass = !project.extensions.getByType(ArchitectPluginExtension::class.java).injectInjectables
    return { clazz, classAdder ->
        if (!injectedClass) {
            injectedClass = true
            Transform::class.java.getResourceAsStream("/annotations-inject/injection.jar").use { stream ->
                ZipUtil.iterate(stream) { input: InputStream, entry: ZipEntry ->
                    if (entry.name.endsWith(".class")) {
                        val newName = "$projectUniqueIdentifier/${
                            entry.name.substringBeforeLast(".class").substringAfterLast('/')
                        }"
                        classAdder(newName, input.readBytes().let {
                            val node = ClassNode(Opcodes.ASM8)
                            ClassReader(it).accept(node, ClassReader.EXPAND_FRAMES)
                            val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
                            val remapper = ClassRemapper(writer, object : Remapper() {
                                override fun map(internalName: String?): String {
                                    if (internalName?.startsWith("me/shedaniel/architect/plugin/callsite") == true) {
                                        return internalName.replace(
                                            "me/shedaniel/architect/plugin/callsite",
                                            projectUniqueIdentifier
                                        )
                                    }
                                    return super.map(internalName)
                                }
                            })
                            node.apply {
                                name = newName
                            }.accept(remapper)

                            writer.toByteArray()
                        })
                    }
                }
            }
        }

        clazz.methods.mapNotNull { method ->
            when {
                method?.visibleAnnotations?.any { it.desc == expectPlatform } == true -> method to "me/shedaniel/architectury/PlatformMethods"
                method?.invisibleAnnotations?.any { it.desc == expectPlatformNew } == true -> {
                    method to "$projectUniqueIdentifier/PlatformMethods"
                }
                else -> null
            }
        }.forEach { (method, platformMethodsClass) ->
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
                        else -> method.instructions.addLoad(char, when (char) {
                            'J', 'D' -> index.also { index += 2 }
                            else -> index++
                        })
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
                    platformMethodsClass,
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
                method.maxStack = -1
            }
        }

        clazz
    }
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