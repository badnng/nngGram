package com.badnng.moe.status

enum class StatusTone { NEUTRAL, ACTIVE, SUCCESS, ERROR }

data class StatusPresentation(val label: String, val detail: String, val tone: StatusTone = StatusTone.NEUTRAL)

fun EnginePhase.presentation(): StatusPresentation = when (this) {
    EnginePhase.NOT_STARTED -> StatusPresentation("未启动", "客户端引擎尚未启动")
    EnginePhase.WAITING_CONFIGURATION -> StatusPresentation("待配置", "等待本地配置准备完成")
    EnginePhase.STARTING -> StatusPresentation("启动中", "正在加载运行环境", StatusTone.ACTIVE)
    EnginePhase.INITIALIZED -> StatusPresentation("初始化完成", "引擎已准备好，账号与网络状态独立显示", StatusTone.SUCCESS)
    EnginePhase.FAILED -> StatusPresentation("启动失败", "查看下方问题说明与恢复建议", StatusTone.ERROR)
    EnginePhase.CLOSING -> StatusPresentation("正在关闭", "等待客户端释放资源", StatusTone.ACTIVE)
    EnginePhase.CLOSED -> StatusPresentation("已关闭", "客户端资源已释放")
}

fun AuthorizationPhase.presentation(): StatusPresentation = when (this) {
    AuthorizationPhase.UNKNOWN -> StatusPresentation("尚未获知", "尚未收到账号授权信息")
    AuthorizationPhase.WAITING_PARAMETERS -> StatusPresentation("等待参数", "等待提交客户端初始化参数", StatusTone.ACTIVE)
    AuthorizationPhase.WAITING_LOGIN -> StatusPresentation("等待登录", "账号尚未登录")
    AuthorizationPhase.WAITING_CODE -> StatusPresentation("等待验证码", "等待完成验证码验证", StatusTone.ACTIVE)
    AuthorizationPhase.WAITING_PASSWORD -> StatusPresentation("等待二步验证", "等待验证账号密码", StatusTone.ACTIVE)
    AuthorizationPhase.READY -> StatusPresentation("已登录", "账号授权已完成", StatusTone.SUCCESS)
    AuthorizationPhase.OTHER_STEP -> StatusPresentation("等待其他验证", "请查看下方登录说明，当前步骤尚不支持提交", StatusTone.ACTIVE)
    AuthorizationPhase.CLOSING -> StatusPresentation("正在关闭", "等待授权流程关闭", StatusTone.ACTIVE)
    AuthorizationPhase.CLOSED -> StatusPresentation("已关闭", "账号授权流程已关闭")
}

fun ConnectionPhase.presentation(): StatusPresentation = when (this) {
    ConnectionPhase.UNKNOWN -> StatusPresentation("尚未获知", "尚未收到网络连接信息")
    ConnectionPhase.WAITING_NETWORK -> StatusPresentation("等待网络", "等待可用的网络连接")
    ConnectionPhase.CONNECTING_PROXY -> StatusPresentation("连接代理中", "正在连接代理服务器", StatusTone.ACTIVE)
    ConnectionPhase.CONNECTING -> StatusPresentation("连接中", "正在连接 Telegram", StatusTone.ACTIVE)
    ConnectionPhase.UPDATING -> StatusPresentation("更新中", "正在同步最新状态", StatusTone.ACTIVE)
    ConnectionPhase.READY -> StatusPresentation("已连接", "网络连接已建立", StatusTone.SUCCESS)
}
