package me.shedaniel.architect.plugin.utils

import org.gradle.api.Project
import java.net.URI
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Path
import java.nio.file.ProviderNotFoundException
import java.nio.file.spi.FileSystemProvider

fun Project.validateJarFs(path: Path) {
    val uri = URI("jar:" + path.toUri().toString())
    val provider = FileSystemProvider.installedProviders().firstOrNull { it.scheme == uri.scheme }
        ?: throw ProviderNotFoundException("Provider \"${uri.scheme}\" not found")
    try {
        val fs = provider.getFileSystem(uri)
        val cl = fs.javaClass.classLoader
        if (fs.isOpen) {
            logger.error("Detected open FS on $path! Forcefully closing the FS! The FS is created on $cl!")
            fs.close()
        }
    } catch (ignored: FileSystemNotFoundException) {
    } catch (e: Exception) {
        e.printStackTrace()
    }
}