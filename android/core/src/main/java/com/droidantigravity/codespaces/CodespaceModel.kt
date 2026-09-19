package com.droidantigravity.codespaces

import com.droidantigravity.core.CodespaceState

/**
 * Data model for a GitHub Codespace.
 */
data class CodespaceItem(
    val name: String,
    val repositoryName: String,
    val repositoryFullName: String,
    val state: CodespaceState,
    val webUrl: String,
    val branch: String? = null,
    val lastUsedAt: String? = null
) {
    val isRunning: Boolean get() = state.isRunning
    val isStopped: Boolean get() = state.isStopped
    val isBusy: Boolean get() = state.isBusy
}

/**
 * Environment type for a workspace.
 */
enum class WorkspaceEnvironment {
    LOCAL,
    CODESPACE
}

/**
 * Environment-independent workspace model for DroidAntigravity.
 */
data class WorkspaceModel(
    val name: String,
    val repository: String,
    val environment: WorkspaceEnvironment,
    val path: String,
    val webEndpoint: String
)

