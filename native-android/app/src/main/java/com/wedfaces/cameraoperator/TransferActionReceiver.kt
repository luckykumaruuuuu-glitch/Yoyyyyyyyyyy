package com.wedfaces.cameraoperator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val batchId = intent.getStringExtra(KEY_BATCH_ID) ?: return
        val services = (context.applicationContext as CameraOperatorApp).services
        when (intent.action) {
            ACTION_PAUSE -> services.pauseBatch(batchId)
            ACTION_CANCEL -> services.cancelBatch(batchId)
        }
    }

    companion object {
        const val KEY_BATCH_ID = "batch_id"
        const val ACTION_PAUSE = "com.wedfaces.cameraoperator.PAUSE_TRANSFER"
        const val ACTION_CANCEL = "com.wedfaces.cameraoperator.CANCEL_TRANSFER"
    }
}
