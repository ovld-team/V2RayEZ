package com.v2rayez.app.data.core

/** One entry in the Core-manager / background download queue. */
enum class QueueItemKind { CORE, ADDON, GEO }

/** Lifecycle of a [DownloadQueueItem]. */
enum class QueueItemState { QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED }

data class DownloadQueueItem(
    val id: String,
    val kind: QueueItemKind,
    val label: String,
    val subLabel: String,
    val state: QueueItemState = QueueItemState.QUEUED,
    /** 0f..1f, or null while indeterminate. */
    val progress: Float? = null,
    val message: String? = null,
    val cancellable: Boolean = true,
    val progressTags: List<String> = emptyList(),
    val progressTagPrefix: String? = null
)

/** Result returned from a queued install action. */
data class QueueOutcome(val state: QueueItemState, val message: String? = null)
