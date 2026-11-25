package com.aicarcompanion.app.assistant.voice

/**
 * The voice configuration state behind [AndroidSpeechOutput] (pure, so it can be unit-tested).
 *
 * [configure] always records the user's real configuration. A preview only sets a temporary
 * override, so a settings change that arrives during a preview is never lost when the preview
 * ends. A voice that failed because it needs internet stays suspended until connectivity
 * returns or the voice setting changes.
 */
class VoiceConfigHolder {
    private var configured = VoiceConfig()
    private var override: VoiceConfig? = null
    private var pendingApply = false
    private var networkSuspended = false

    /** What the engine should be using right now. */
    @get:Synchronized val effective: VoiceConfig get() = override ?: configured

    /** True when the engine still has to be updated to [effective] (applied before the next utterance). */
    @get:Synchronized val pending: Boolean get() = pendingApply

    /** Records new settings. Returns true if they can be applied to the engine immediately. */
    @Synchronized fun configure(config: VoiceConfig, busy: Boolean): Boolean {
        configured = config
        networkSuspended = false // The user chose something; try it.
        if (override != null || busy) {
            pendingApply = true
            return false
        }
        pendingApply = false
        return true
    }

    @Synchronized fun beginPreview(config: VoiceConfig) {
        override = config.copy(engine = configured.engine) // A preview never changes the engine.
    }

    /** Drops the override; the real configuration must be re-applied. */
    @Synchronized fun endPreview() {
        override = null
        pendingApply = true
    }

    @Synchronized fun markApplied() { pendingApply = false }

    @Synchronized fun suspendNetworkVoice() {
        networkSuspended = true
        pendingApply = true
    }

    /** Whether a network voice may be used now; resumes it once the internet is back. */
    @Synchronized fun networkVoiceAllowed(online: Boolean): Boolean {
        if (networkSuspended && online) {
            networkSuspended = false
            pendingApply = true
        }
        return !networkSuspended
    }
}
