package com.droidantigravity.antigravity

/** Capabilities exposed by the installed agy CLI. */
data class AntigravityCapabilities(
    val version: String,
    val interactiveRemoteControl: Boolean,
    val remoteControlDaemon: Boolean
)
