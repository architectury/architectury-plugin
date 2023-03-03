package dev.architectury.plugin.utils

import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.util.GradleVersion

object GradleSupport {
    fun getFileProperty(project: Project): RegularFileProperty {
        return try {
            getFilePropertyModern(project)
        } catch (var3: Exception) {
            try {
                getFilePropertyLegacy(project)
            } catch (var2: Exception) {
                throw RuntimeException("Failed to find file property", var2)
            }
        }
    }

    private fun getFilePropertyModern(project: Project): RegularFileProperty {
        return getFilePropertyLegacyFromObject(project.objects)
    }

    private fun getFilePropertyLegacy(project: Project): RegularFileProperty {
        return getFilePropertyLegacyFromObject(project.layout)
    }

    private fun getFilePropertyLegacyFromObject(`object`: Any): RegularFileProperty {
        val method = `object`.javaClass.getDeclaredMethod("fileProperty")
        method.isAccessible = true
        return method.invoke(`object`) as RegularFileProperty
    }

    fun isGradle8(project: Project): Boolean {
        return GradleVersion.current().baseVersion >= GradleVersion.version("8.0")
    }
}