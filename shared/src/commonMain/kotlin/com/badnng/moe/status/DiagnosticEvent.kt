package com.badnng.moe.status

/** Only finite, application-owned values can describe events. Raw TDLib objects are never accepted. */
enum class DiagnosticKind(val summary: String) {
    START_REQUESTED("开始初始化客户端"),
    NATIVE_LOADED("原生库版本与提交已确认"),
    CONFIGURATION_REQUIRED("等待填写本地应用配置"),
    CLIENT_CREATED("已创建唯一 TDLib 客户端"),
    PARAMETERS_SENT("已提交初始化参数"),
    PARAMETERS_ACCEPTED("TDLib 已接受初始化参数"),
    AUTHORIZATION_CHANGED("账号授权状态已更新"),
    LOGIN_SUBMITTED("已提交当前登录步骤"),
    LOGIN_ACCEPTED("登录请求已接受，等待授权更新"),
    LOGIN_REJECTED("当前登录步骤未通过，请检查输入后重试"),
    CONNECTION_CHANGED("网络连接状态已更新"),
    PROXY_CONFIGURING("正在应用连接方式"),
    PROXY_APPLIED("连接方式已应用"),
    PROXY_FAILED("连接方式应用失败"),
    CLOSE_REQUESTED("已请求关闭 TDLib 客户端"),
    CLOSE_CONFIRMED("TDLib 已确认关闭，目录锁已释放"),
    STOPPED_WITHOUT_CLIENT("没有活动客户端，停止完成"),
    CLOSE_TIMED_OUT("TDLib 尚未确认关闭，客户端与目录锁仍保留"),
    FAILURE("客户端发生可分类错误"),
}

data class DiagnosticEvent(
    val timestampEpochMillis: Long,
    val generation: Long,
    val kind: DiagnosticKind,
    val engine: EnginePhase,
    val authorization: AuthorizationPhase,
    val connection: ConnectionPhase,
    val category: FailureCategory? = null,
    val requestErrorCode: Int? = null,
    val nativeVersion: String? = null,
    val nativeCommit: String? = null,
) {
    val summary: String get() = kind.summary

    /** Portable UTC clock label; the on-disk event also contains its full ISO date. */
    val timeLabel: String get() {
        val seconds = ((timestampEpochMillis / 1_000) % 86_400 + 86_400) % 86_400
        fun two(value: Long) = value.toString().padStart(2, '0')
        return "${two(seconds / 3_600)}:${two(seconds / 60 % 60)}:${two(seconds % 60)} UTC"
    }

    val recovery: String get() = when (category) {
        FailureCategory.CONFIGURATION -> "请检查本地应用配置；凭据只保存在本地。"
        FailureCategory.DIRECTORY -> "请检查数据目录权限、空间以及是否有其他客户端占用。"
        FailureCategory.NATIVE -> "请重新构建或完整复制匹配的 Windows x64 原生包。"
        FailureCategory.TDLIB_REQUEST -> "请核对本地参数后停止并重试。"
        FailureCategory.LIFECYCLE -> "请继续等待 TDLib 确认关闭，完成前不要复用此数据目录。"
        FailureCategory.INTERNAL -> "请停止客户端后重试，并检查安全事件记录。"
        FailureCategory.PROXY -> "请在本地修正代理配置并重启客户端。"
        FailureCategory.AUTHENTICATION -> "请查看登录表单中的提示并重试；操作过于频繁时请稍后再试。"
        null -> ""
    }
}
