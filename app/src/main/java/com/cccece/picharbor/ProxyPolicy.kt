package com.cccece.picharbor

import org.json.JSONObject

/**
 * Proxy configuration, selection and routing, decided by the shared core.
 *
 * Contract (stage two):
 * - Proxy is off by default; the user opts in explicitly.
 * - Only explicit, non-secret parameters go to the core action
 *   `validate_proxy` (enabled/scheme/host/port/username). Credentials never
 *   enter the core and never enter logs: the password lives in
 *   [CredentialStore] (Android Keystore, AES-GCM) and is only handed to the
 *   engine session in memory when building the proxy URL.
 * - TLS verification is retained: the Python transport keeps
 *   `session.verify = True` and `trust_env = False` (MobileApi._new_session
 *   in harbor_android.py); no proxy setting may disable it.
 */
data class ProxyConfig(
    val enabled: Boolean,
    val scheme: String = "http",
    val host: String = "",
    val port: Int = 0,
    val username: String = ""
)

object ProxyPolicy {
    val SUPPORTED_SCHEMES = setOf("http", "https")

    /** Device-level proxy setting (not per account, not in the profile maps). */
    fun buildConfig(settings: HarborSettings): ProxyConfig? {
        if (!settings.proxyEnabled) return null
        return ProxyConfig(
            enabled = true,
            scheme = settings.proxyScheme.lowercase(),
            host = settings.proxyHost.trim(),
            port = settings.proxyPort,
            username = settings.proxyUser.trim()
        )
    }

    /**
     * Local shape check. Returns null when the config is usable, otherwise
     * an error code (PROXY_INVALID_*). This is the fallback when the core
     * predates validate_proxy, and a pre-check before asking the core.
     */
    fun validateLocally(config: ProxyConfig): String? {
        if (!config.enabled) return null
        if (config.scheme !in SUPPORTED_SCHEMES) return "PROXY_INVALID_SCHEME"
        if (config.host.isEmpty() || config.host.any { it.isWhitespace() || it == '@' || it == ':' || it == '/' }) {
            return "PROXY_INVALID_HOST"
        }
        if (config.port !in 1..65535) return "PROXY_INVALID_PORT"
        if (config.username.any { it == ':' || it == '@' || it == '/' }) return "PROXY_INVALID_USERNAME"
        return null
    }

    sealed interface Validation {
        data object Valid : Validation
        data class Invalid(val error: String) : Validation
    }

    /**
     * Core validation with local fallback. UNKNOWN_OPERATION means the synced
     * core predates validate_proxy: the local shape check decides. Any other
     * core failure also falls back locally (the core is a validator, not a
     * connectivity probe); only an explicit core rejection is Invalid. The
     * core answers {"ok": true, "enabled": bool, ...} on success (no
     * "valid" field) and {"ok": false, "error": "INVALID_PROXY" |
     * "PROXY_TLS_VERIFICATION_DISABLED"} on rejection. TLS verification is
     * always requested on (verify_tls=true); the core rejects any attempt
     * to turn it off.
     */
    suspend fun validate(core: HarborCore, config: ProxyConfig): Validation {
        validateLocally(config)?.let { return Validation.Invalid(it) }
        if (!core.available()) return Validation.Invalid("ENGINE_ERROR")
        val payload = JSONObject()
            .put("enabled", config.enabled)
            .put("scheme", config.scheme)
            .put("host", config.host)
            .put("port", config.port)
            .put("username", config.username)
            .put("verify_tls", true)
        val result = core.validateProxy(payload)
        if (result.optBoolean("ok")) return Validation.Valid
        if (core.isUnknownOperation(result)) return Validation.Invalid("CORE_ACTION_UNAVAILABLE")
        return Validation.Invalid(result.optString("error", "PROXY_INVALID"))
    }

    /**
     * Builds the proxy URL for the engine session. The password is only ever
     * held in memory here; callers must never log the result. Returns "" when
     * there is no proxy to use.
     */
    fun proxyUrl(config: ProxyConfig?, password: String?): String {
        if (config == null || !config.enabled) return ""
        val authority = buildString {
            if (config.username.isNotEmpty()) {
                append(config.username)
                if (!password.isNullOrEmpty()) append(":").append(password)
                append("@")
            }
            append(config.host).append(":").append(config.port)
        }
        return "${config.scheme}://$authority"
    }
}
