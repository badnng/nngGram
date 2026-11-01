package com.badnng.moe.tdlib

import java.nio.file.Files
import java.util.Properties
import kotlin.test.*

class InstallationConfigurationTest {
    @Test fun firstInstallationSelectsUnifiedDataAndDownloadsWithoutCreatingAccountFiles() {
        val root = Files.createTempDirectory("install-中文-")
        val data = root.resolve("聊天 # 数据")
        val config = root.resolve("local/tdlib.properties")
        InstallationConfiguration.configure(data, root.resolve("app"), config)
        val read = assertNotNull(ConfigurationStore(config, root.resolve("unused"),
            bundledApiId = "12345", bundledApiHash = "a".repeat(32)).read())
        assertEquals(data, read.dataDirectory)
        val downloads = Properties().apply {
            Files.newBufferedReader(config.resolveSibling("ui-downloads.properties")).use(::load)
        }
        assertEquals(data.resolve("downloads").toString(), downloads.getProperty("custom-path"))
        assertEquals("CUSTOM", downloads.getProperty("directory"))
        assertFalse(Files.exists(data))
        assertFalse(Files.readString(config).contains("api_hash"))
    }

    @Test fun upgradePreservesConfigurationAndDownloadPreferencesByteForByte() {
        val root = Files.createTempDirectory("install-upgrade-")
        val config = root.resolve("tdlib.properties")
        Files.writeString(config, "# existing\ndata_directory=D:/existing\n")
        val downloads = root.resolve("ui-downloads.properties")
        Files.writeString(downloads, "# existing download choice\n")
        InstallationConfiguration.configure(root.resolve("new-data"), root.resolve("app"), config)
        assertEquals("# existing\ndata_directory=D:/existing\n", Files.readString(config))
        assertEquals("# existing download choice\n", Files.readString(downloads))
    }

    @Test fun overlappingOrRelativePathsCannotCreateConfiguration() {
        val root = Files.createTempDirectory("install-invalid-")
        val config = root.resolve("tdlib.properties")
        for ((data, app) in listOf(root to root, root.resolve("app/data") to root.resolve("app"),
            root to root.resolve("app"), java.nio.file.Path.of("relative") to root.resolve("app"))) {
            assertFailsWith<IllegalArgumentException> { InstallationConfiguration.configure(data, app, config) }
            assertFalse(Files.exists(config))
        }
    }
}
