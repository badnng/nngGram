package com.badnng.moe

import com.badnng.moe.tdlib.InstallationConfiguration
import java.nio.file.Path

internal fun handleInstallationArguments(args: Array<String>): Int? {
    if (args.firstOrNull() != "--configure-installation") return null
    return try {
        require(args.size == 3)
        val local = requireNotNull(System.getenv("LOCALAPPDATA"))
        InstallationConfiguration.configure(Path.of(args[1]), Path.of(args[2]),
            Path.of(local).resolve("nngGram/tdlib.properties"))
        0
    } catch (_: Exception) {
        System.err.println("Unable to configure installation directories; existing account data was preserved.")
        1
    }
}
