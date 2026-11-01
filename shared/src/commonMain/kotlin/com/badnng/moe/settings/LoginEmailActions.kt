package com.badnng.moe.settings

/** Email/code arguments are private transient requests. Callbacks never return those secrets. */
data class LoginEmailActions(
    val open: (Long, Long, LoginEmailEntryKind, () -> Boolean) -> Unit = { _, _, _, _ -> },
    val close: (LoginEmailSetupToken) -> Unit = {},
    val submitEmail: (LoginEmailSetupToken, String, (LoginEmailOutcome) -> Unit) -> Unit = { _, _, done -> done(LoginEmailOutcome(issue = LoginEmailIssue.NOT_AVAILABLE)) },
    val resend: (LoginEmailChallengeToken, (LoginEmailOutcome) -> Unit) -> Unit = { _, done -> done(LoginEmailOutcome(issue = LoginEmailIssue.NOT_AVAILABLE)) },
    val check: (LoginEmailChallengeToken, String, (LoginEmailOutcome) -> Unit) -> Unit = { _, _, done -> done(LoginEmailOutcome(issue = LoginEmailIssue.NOT_AVAILABLE)) },
    val refreshProof: (LoginEmailSetupToken) -> Unit = {},
    val hideOptional: (Long, Long) -> Unit = { _, _ -> },
)
