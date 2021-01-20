package me.shedaniel.architect.plugin.transformers

import me.shedaniel.architect.plugin.Transformer
import org.gradle.api.Project
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.zeroturnaround.zip.ByteSource
import org.zeroturnaround.zip.ZipUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

object GenerateFakeForgeMod : Transformer {
    override fun invoke(project: Project, input: Path, output: Path) {
        val fakeModId = "generated_" + UUID.randomUUID().toString().filterNot { it == '-' }.take(7)
        Files.copy(input, output)
        ZipUtil.addEntries(
            output.toFile(), arrayOf(
                ByteSource(
                    "META-INF/mods.toml",
                    """modLoader = "javafml"
    loaderVersion = "[33,)"
    license = "Generated"
    [[mods]]
    modId = "$fakeModId"""".toByteArray()
                ),
                ByteSource(
                    "pack.mcmeta",
                    """{"pack":{"description":"Generated","pack_format":4}}""".toByteArray()
                ),
                ByteSource(
                    "generated/$fakeModId.class",
                    ClassWriter(0).let { classWriter ->
                        classWriter.visit(52, Opcodes.ACC_PUBLIC, "generated/$fakeModId", null, "java/lang/Object", null)
                        val modAnnotation = classWriter.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", false)
                        modAnnotation.visit("value", fakeModId)
                        modAnnotation.visitEnd()
                        classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, arrayOf()).also {
                            it.visitVarInsn(Opcodes.ALOAD, 0)
                            it.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                            it.visitInsn(Opcodes.RETURN)
                            it.visitMaxs(1, 1)
                            it.visitEnd()
                        }
                        classWriter.visitEnd()
                        classWriter.toByteArray()
                    }
                )
            )
        )
    }
}