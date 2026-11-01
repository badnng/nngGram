package com.badnng.moe.tdlib

import com.badnng.moe.settings.*

interface TdNotificationExceptionsSession {
    fun list(scope: NotificationScope, active: () -> Boolean, result: (SettingsResult<List<Long>>) -> Unit)
    fun resolve(scope: NotificationScope, ids: List<Long>, active: () -> Boolean,
        result: (SettingsResult<List<NotificationExceptionRow>>) -> Unit)
    fun search(scope: NotificationScope, query: String, remote: Boolean, active: () -> Boolean,
        result: (SettingsResult<List<NotificationExceptionRow>>) -> Unit)
    fun read(scope: NotificationScope, chatId: Long, active: () -> Boolean,
        result: (SettingsResult<NotificationExceptionPeer>) -> Unit)
    fun save(edit: NotificationExceptionEdit, active: () -> Boolean,
        result: (NotificationExceptionWriteResult) -> Unit)
    /** null means the scope/defaults or protected context changed. */
    fun observe(observer: ((Long?) -> Unit)?) {}
    fun retire() {}
}
