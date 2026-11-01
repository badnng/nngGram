package com.badnng.moe.tdlib

import java.io.StringWriter
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties

/** Installer-only bootstrap. Never opens TDLib, creates account files or migrates existing data. */
object InstallationConfiguration {
    fun configure(dataDirectory: Path, installationDirectory: Path, configurationPath: Path) {
        require(dataDirectory.isAbsolute && installationDirectory.isAbsolute && configurationPath.isAbsolute)
        // Upgrades retain the exact existing configuration, even if the caller supplies other paths.
        if (Files.exists(configurationPath)) {
            require(Files.isRegularFile(configurationPath))
            return
        }
        val data = canonicalDestination(dataDirectory)
        val application = canonicalDestination(installationDirectory)
        require(!data.startsWith(application) && !application.startsWith(data))
        require(!Files.exists(data) || Files.isDirectory(data))
        require(data.toString().none { it.isISOControl() })
        val config = Properties().apply {
            setProperty("data_directory", data.toString().replace('\\', '/'))
            setProperty("use_test_dc", "false")
            setProperty("proxy_type", "none")
        }
        Files.createDirectories(configurationPath.parent)
        if (!writeNew(configurationPath, config)) return
        // Native cache remains <data>/files; explicitly saved downloads use this sibling folder.
        val downloads = Properties().apply {
            setProperty("version", "1")
            setProperty("ask-every-time", "false")
            setProperty("directory", "CUSTOM")
            setProperty("custom-path", data.resolve("downloads").toString())
        }
        writeNew(configurationPath.resolveSibling("ui-downloads.properties"), downloads)
    }

    private fun writeNew(path: Path, values: Properties): Boolean = try {
        val text = StringWriter().also { values.store(it, "nngGram installation preferences") }.toString()
        Files.writeString(path, text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        true
    } catch (_: FileAlreadyExistsException) { false }

    private fun canonicalDestination(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        var ancestor = normalized
        while (!Files.exists(ancestor)) ancestor = requireNotNull(ancestor.parent)
        return ancestor.toRealPath().resolve(ancestor.relativize(normalized)).normalize()
    }
}
