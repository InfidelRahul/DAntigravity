package com.droidantigravity.web

/**
 * A browser endpoint supplied by an Antigravity runtime. The WebView does not
 * care whether it came from the official Remote Control tunnel, a local server,
 * or a future Codespace integration.
 */
data class AntigravityEndpoint(
    val url: String,
    val source: EndpointSource
)

enum class EndpointSource {
    REMOTE_CONTROL,
    LOCAL,
    CODESPACE
}
