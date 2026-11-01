package com.badnng.moe.status

enum class EnginePhase {
    NOT_STARTED, WAITING_CONFIGURATION, STARTING, INITIALIZED, FAILED, CLOSING, CLOSED,
}

enum class AuthorizationPhase {
    UNKNOWN, WAITING_PARAMETERS, WAITING_LOGIN, WAITING_CODE, WAITING_PASSWORD, OTHER_STEP, READY, CLOSING, CLOSED,
}

enum class ConnectionPhase {
    UNKNOWN, WAITING_NETWORK, CONNECTING_PROXY, CONNECTING, UPDATING, READY,
}

enum class FailureCategory {
    CONFIGURATION, DIRECTORY, NATIVE, TDLIB_REQUEST, LIFECYCLE, INTERNAL, PROXY, AUTHENTICATION,
}

data class EngineFailure(
    val summary: String,
    val recovery: String,
    val category: FailureCategory = FailureCategory.INTERNAL,
)

/** Each stream is independent: initialized never implies signed in or connected. */
data class ClientStatus(
    val engine: EnginePhase = EnginePhase.NOT_STARTED,
    val authorization: AuthorizationPhase = AuthorizationPhase.UNKNOWN,
    val connection: ConnectionPhase = ConnectionPhase.UNKNOWN,
    val failure: EngineFailure? = null,
)

sealed interface StatusEvent {
    data class EngineChanged(val phase: EnginePhase, val failure: EngineFailure? = null) : StatusEvent
    data class AuthorizationChanged(val phase: AuthorizationPhase) : StatusEvent
    data class ConnectionChanged(val phase: ConnectionPhase) : StatusEvent
}

fun ClientStatus.reduce(event: StatusEvent): ClientStatus = when (event) {
    is StatusEvent.EngineChanged -> copy(
        engine = event.phase,
        failure = event.failure.takeIf { event.phase == EnginePhase.FAILED },
    )
    is StatusEvent.AuthorizationChanged -> copy(authorization = event.phase)
    is StatusEvent.ConnectionChanged -> copy(connection = event.phase)
}

enum class PreviewScenario(val title: String, val description: String) {
    CONFIGURATION("待配置", "准备就绪，从基础配置开始"),
    LOADING("加载中", "正在准备客户端运行环境"),
    LOGIN("待登录", "引擎初始化完成，等待账号登录"),
    FAILURE("失败", "遇到问题，查看恢复建议");

    fun snapshot(): ClientStatus = when (this) {
        CONFIGURATION -> ClientStatus(engine = EnginePhase.WAITING_CONFIGURATION)
        LOADING -> ClientStatus(EnginePhase.STARTING, AuthorizationPhase.WAITING_PARAMETERS)
        LOGIN -> ClientStatus(EnginePhase.INITIALIZED, AuthorizationPhase.WAITING_LOGIN)
        FAILURE -> ClientStatus(
            engine = EnginePhase.FAILED,
            failure = EngineFailure("原生库尚未准备", "后续接入阶段需准备与 JVM 架构一致的 TDLib 原生库。", FailureCategory.NATIVE),
        )
    }
}
