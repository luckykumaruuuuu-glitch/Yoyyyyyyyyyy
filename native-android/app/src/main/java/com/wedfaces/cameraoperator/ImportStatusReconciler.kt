package com.wedfaces.cameraoperator

import com.wedfaces.cameraoperator.model.ImportStatus
import com.wedfaces.cameraoperator.model.QueueState
import com.wedfaces.cameraoperator.model.TransferItem

internal data class ImportReconciliation(
    val item: TransferItem,
    val state: QueueState,
    val error: String?,
)

internal object ImportStatusReconciler {
    fun reconcile(items: List<TransferItem>, status: ImportStatus): List<ImportReconciliation> {
        val candidates = items.filter {
            it.state == QueueState.UPLOADED_WAITING_IMPORT || it.state == QueueState.IMPORT_REVIEW
        }
        val results = status.results
        if (results != null) {
            return candidates.map { item ->
                val result = results.firstOrNull { it.uploadId == item.uploadId }
                    ?: results.firstOrNull {
                        it.fileName?.equals(item.fileName, ignoreCase = true) == true
                    }
                when (result?.success) {
                    true -> ImportReconciliation(item, QueueState.IMPORTED, null)
                    false -> ImportReconciliation(
                        item,
                        QueueState.FAILED,
                        result.error ?: "WED Faces did not import this file successfully.",
                    )
                    null -> ImportReconciliation(
                        item,
                        QueueState.IMPORT_REVIEW,
                        "WED Faces did not return a clear result for this file.",
                    )
                }
            }
        }

        val failureCount = maxOf(status.failed ?: 0, status.failures.size)
        val matchingFailures = candidates.associateWith { item ->
            status.failures.firstOrNull { failure ->
                failure.contains(item.fileName, ignoreCase = true)
            }
        }.filterValues { it != null }
        if (status.state == "COMPLETED" && failureCount == 0) {
            return candidates.map { ImportReconciliation(it, QueueState.IMPORTED, null) }
        }

        // Legacy status responses can contain failures from other uploads to the same album.
        // Only identified failures for this batch affect individual queue rows.
        val unmatchedFailures = failureCount - matchingFailures.size
        return candidates.map { item ->
            val matchedError = matchingFailures[item]
            when {
                matchedError != null -> ImportReconciliation(item, QueueState.FAILED, matchedError)
                matchingFailures.isNotEmpty() -> ImportReconciliation(
                    item,
                    QueueState.IMPORT_REVIEW,
                    "The server reported failures for this batch but did not confirm this file.",
                )
                unmatchedFailures > 0 -> ImportReconciliation(item, QueueState.UPLOADED_WAITING_IMPORT, null)
                else -> ImportReconciliation(
                    item,
                    QueueState.IMPORT_REVIEW,
                    status.error ?: "WED Faces could not confirm this file's import result.",
                )
            }
        }
    }
}
