package com.badnng.moe

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform