package com.badnng.moe.media

import java.util.concurrent.atomic.AtomicLong

private val mediaDownloadLeaseIds = AtomicLong()
internal actual fun nextMediaDownloadLeaseId(): Long = mediaDownloadLeaseIds.incrementAndGet()
