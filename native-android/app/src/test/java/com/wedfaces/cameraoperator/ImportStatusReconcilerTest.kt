package com.wedfaces.cameraoperator

import com.wedfaces.cameraoperator.data.TransferQueueRepository
import com.wedfaces.cameraoperator.model.ImportFileResult
import com.wedfaces.cameraoperator.model.ImportStatus
import com.wedfaces.cameraoperator.model.PhotoSource
import com.wedfaces.cameraoperator.model.QueueState
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportStatusReconcilerTest {
    @Test
    fun newServerResultsMatchByUploadIdBeforeFileName() {
        val first = item("upload-one", "one.jpg")
        val second = item("upload-two", "two.jpg")
        val status = completed(
            results = listOf(
                ImportFileResult("upload-one", "different-name.jpg", true, null),
                ImportFileResult(null, "two.jpg", false, "bad image"),
            ),
        )

        val reconciled = ImportStatusReconciler.reconcile(listOf(first, second), status)

        assertEquals(listOf(QueueState.IMPORTED, QueueState.FAILED), reconciled.map { it.state })
        assertEquals("bad image", reconciled.last().error)
    }

    @Test
    fun legacyGlobalFailuresForOtherFilesDoNotMarkThisBatchForReview() {
        val first = item("upload-one", "one.jpg")
        val second = item("upload-two", "two.jpg")
        val status = completed(
            failed = 2,
            failures = listOf("from-another-batch.jpg — corrupt", "another-event.png — invalid"),
        )

        val reconciled = ImportStatusReconciler.reconcile(listOf(first, second), status)

        assertEquals(
            listOf(QueueState.UPLOADED_WAITING_IMPORT, QueueState.UPLOADED_WAITING_IMPORT),
            reconciled.map { it.state },
        )
    }

    @Test
    fun legacyStatusImportsOnlyWhenItReportsNoFailures() {
        val items = listOf(item("upload-one", "one.jpg"), item("upload-two", "two.jpg"))

        val reconciled = ImportStatusReconciler.reconcile(items, completed(failed = 0))

        assertEquals(listOf(QueueState.IMPORTED, QueueState.IMPORTED), reconciled.map { it.state })
    }

    @Test
    fun legacyNamedFailureOnlyFailsTheMatchingFile() {
        val first = item("upload-one", "one.jpg")
        val second = item("upload-two", "two.jpg")
        val status = completed(failed = 1, failures = listOf("one.jpg — unsupported image"))

        val reconciled = ImportStatusReconciler.reconcile(listOf(first, second), status)

        assertEquals(QueueState.FAILED, reconciled.first().state)
        assertEquals(QueueState.IMPORT_REVIEW, reconciled.last().state)
    }

    private fun item(uploadId: String, name: String) = TransferQueueRepository.newTransfer(
        batchId = "batch",
        albumId = "album",
        albumName = "Wedding",
        source = PhotoSource.TEST_GALLERY,
        sourceObjectId = "device:$name",
        fileName = name,
        mimeType = "image/jpeg",
        sizeBytes = 100L,
        initialState = QueueState.UPLOADED_WAITING_IMPORT,
    ).copy(uploadId = uploadId)

    private fun completed(
        failed: Int? = null,
        failures: List<String> = emptyList(),
        results: List<ImportFileResult>? = null,
    ) = ImportStatus(
        state = "COMPLETED",
        total = 2,
        imported = 2 - (failed ?: 0),
        duplicates = 0,
        failed = failed,
        failures = failures,
        error = null,
        results = results,
    )
}
