package com.jonathancruzate.duopanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * PATCH 0.1.6 (corrected): one-shot screen capture for the Stage over-app illusion.
 *
 * Holds the process-wide MediaProjection (consent is requested once per process) and the most
 * recent captured frame. The bitmap is MEMORY-ONLY: it is never written to storage or logs and
 * StageActivity recycles it when the Stage closes.
 */
object StageBackdrop {
    @Volatile
    var projection: MediaProjection? = null

    @Volatile
    var bitmap: Bitmap? = null

    @Volatile
    var pendingCallback: ((Boolean) -> Unit)? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            projection = null
        }
    }

    fun registerCallbackIfNeeded(projection: MediaProjection) {
        try {
            projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        } catch (_: Throwable) {
            // Already registered or unsupported; reuse still works or fails loudly at VD creation.
        }
    }

    fun releaseBitmap() {
        val bmp = bitmap
        bitmap = null
        try {
            bmp?.recycle()
        } catch (_: Throwable) {
        }
    }
}

/**
 * Minimal one-shot foreground service for MediaProjection capture (PATCH 0.1.6).
 * Starts, grabs exactly one frame from a VirtualDisplay, stops. Declared with
 * foregroundServiceType="mediaProjection" as required on current Android.
 */
class StageCaptureService : Service() {

    companion object {
        private const val EXTRA_RESULT_CODE = "extra_capture_result_code"
        private const val EXTRA_RESULT_DATA = "extra_capture_result_data"
        private const val NOTIFICATION_ID = 6106
        private const val CHANNEL_ID = "duopanel_stage_capture"
        private const val GRAB_DELAY_MS = 160L

        fun requestCapture(
            context: Context,
            resultCode: Int?,
            data: Intent?,
            callback: (Boolean) -> Unit
        ) {
            StageBackdrop.pendingCallback = callback
            val intent = Intent(context, StageCaptureService::class.java).apply {
                if (resultCode != null && data != null) {
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_RESULT_DATA, data)
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                StageBackdrop.pendingCallback = null
                callback(false)
            }
        }
    }

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        val projection = obtainProjection(resultCode, data)
        if (projection == null) {
            finishCapture(null)
            return START_NOT_STICKY
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        if (width <= 0 || height <= 0) {
            finishCapture(null)
            return START_NOT_STICKY
        }

        try {
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            virtualDisplay = projection.createVirtualDisplay(
                "duopanel-stage",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                Handler(Looper.getMainLooper())
            )
            // Let the mirrored display settle (consent dialog gone, app frame current), then grab.
            Handler(Looper.getMainLooper()).postDelayed({ grabFrame(width, height, retry = true) }, GRAB_DELAY_MS)
        } catch (t: Throwable) {
            finishCapture(null)
        }
        return START_NOT_STICKY
    }

    private fun obtainProjection(resultCode: Int, data: Intent?): MediaProjection? {
        val existing = StageBackdrop.projection
        if (existing != null) return existing
        if (data == null || resultCode == Int.MIN_VALUE) return null
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return null
        return try {
            val created = manager.getMediaProjection(resultCode, data) ?: return null
            StageBackdrop.registerCallbackIfNeeded(created)
            StageBackdrop.projection = created
            created
        } catch (_: Throwable) {
            null
        }
    }

    private fun grabFrame(width: Int, height: Int, retry: Boolean) {
        if (finished) return
        val reader = imageReader
        if (reader == null) {
            finishCapture(null)
            return
        }
        var bitmap: Bitmap? = null
        try {
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val paddedWidth = width + rowPadding / pixelStride
                    val full = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                    full.copyPixelsFromBuffer(buffer)
                    bitmap = if (paddedWidth != width) {
                        val cropped = Bitmap.createBitmap(full, 0, 0, width, height)
                        if (cropped !== full) full.recycle()
                        cropped
                    } else {
                        full
                    }
                } finally {
                    image.close()
                }
            }
        } catch (_: Throwable) {
            bitmap = null
        }
        if (bitmap == null && retry) {
            Handler(Looper.getMainLooper()).postDelayed({ grabFrame(width, height, retry = false) }, GRAB_DELAY_MS)
            return
        }
        finishCapture(bitmap)
    }

    private fun finishCapture(bitmap: Bitmap?) {
        if (finished) return
        finished = true
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Throwable) {
        }
        imageReader = null
        if (bitmap != null) {
            val old = StageBackdrop.bitmap
            StageBackdrop.bitmap = bitmap
            if (old != null && old !== bitmap) {
                try {
                    old.recycle()
                } catch (_: Throwable) {
                }
            }
        }
        val callback = StageBackdrop.pendingCallback
        StageBackdrop.pendingCallback = null
        try {
            callback?.invoke(bitmap != null)
        } catch (_: Throwable) {
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                manager?.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "DuoPanel Stage capture",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            } catch (_: Throwable) {
            }
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DuoPanel")
            .setContentText("Capturing one frame for the Stage backdrop")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Throwable) {
            }
        }
    }
}
