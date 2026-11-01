package com.badnng.moe.notifications

/** LocalServer32 appends -Embedding. This parser performs no configuration or OS access. */
object WindowsToastLaunchArguments {
    fun isActivation(args: Array<String>): Boolean {
        val activation = "--toast-activate"
        if (args.firstOrNull() != activation) {
            require(args.none { it == activation || it.equals("-Embedding", true) })
            return false
        }
        require(args.size == 1 || args.size == 2 && args[1].equals("-Embedding", true))
        return true
    }
}
