package me.shedaniel.architect.plugin.utils

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Transform {
    inline fun transform(input: Path, output: Path, transform: (ClassNode, (String, ByteArray) -> Unit) -> ClassNode) {
        output.toFile().delete()

        if (!Files.exists(input)) {
            throw FileNotFoundException(input.toString())
        }

        if (input.toFile().absolutePath.endsWith(".class")) {
            var allBytes = Files.newInputStream(input).readBytes()
            val reader = ClassReader(allBytes)
            if ((reader.access and Opcodes.ACC_MODULE) == 0) {
                val node = ClassNode(Opcodes.ASM8)
                reader.accept(node, ClassReader.EXPAND_FRAMES)
                val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
                transform(node) { name, bytes ->
                    File(output.toFile().parentFile, "$name.class").also {
                        it.delete()
                        it.writeBytes(bytes)
                    }
                }.accept(writer)
                allBytes = writer.toByteArray()
            }
            output.toFile().writeBytes(allBytes)
        } else {
            val zipOutputStream = ZipOutputStream(output.toFile().outputStream())
            zipOutputStream.use {
                ZipInputStream(Files.newInputStream(input)).use {
                    while (true) {
                        val entry = it.nextEntry ?: break
                        var allBytes = it.readBytes()
                        if (entry.name.toString().endsWith(".class")) {
                            val reader = ClassReader(allBytes)
                            if ((reader.access and Opcodes.ACC_MODULE) == 0) {
                                val node = ClassNode(Opcodes.ASM8)
                                reader.accept(node, ClassReader.EXPAND_FRAMES)
                                val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
                                transform(node) { name, bytes ->
                                    zipOutputStream.putNextEntry(ZipEntry(name))
                                    zipOutputStream.write(bytes)
                                    zipOutputStream.closeEntry()
                                }.accept(writer)
                                allBytes = writer.toByteArray()
                            }
                        }
                        zipOutputStream.putNextEntry(ZipEntry(entry.name))
                        zipOutputStream.write(allBytes)
                        zipOutputStream.closeEntry()
                    }
                }
            }
        }
    }
}