package com.example.handsender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.Executors

class HandService : LifecycleService() {
    companion object {
        @Volatile var running = false
        @Volatile var hands = 0
        const val PORT = 25566
    }

    private val exec = Executors.newSingleThreadExecutor()
    private var landmarker: HandLandmarker? = null
    private var provider: ProcessCameraProvider? = null
    private var socket: DatagramSocket? = null
    private val addr: InetAddress = InetAddress.getByName("127.0.0.1")
    private var lastTs = 0L

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("hand", "Hand tracking", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, "hand")
            .setContentTitle("Hand Sender работает")
            .setContentText("Камера отслеживает руки для Minecraft")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        running = true

        socket = DatagramSocket()
        landmarker = HandLandmarker.createFromOptions(
            this,
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
        )

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = future.get()
            provider = p
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(exec) { img ->
                try { process(img) } catch (t: Throwable) { Log.e("HandSender", "frame error", t) } finally { img.close() }
            }
            p.unbindAll()
            p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun process(img: ImageProxy) {
        val lm = landmarker ?: return
        val bmp = img.toBitmap()
        val opts = ImageProcessingOptions.builder().setRotationDegrees(img.imageInfo.rotationDegrees).build()
        val ts = maxOf(lastTs + 1, System.currentTimeMillis()); lastTs = ts
        val res = lm.detectForVideo(BitmapImageBuilder(bmp).build(), opts, ts)
        hands = res.landmarks().size
        for (i in res.landmarks().indices) {
            // кадр фронтальной камеры не зеркальный: метки MediaPipe инвертированы, x нужно отразить
            val label = res.handedness()[i][0].categoryName()
            val idx = if (label == "Right") 0 else 1   // 0 = левая рука пользователя, 1 = правая
            val sb = StringBuilder().append(idx)
            for (p in res.landmarks()[i]) {
                sb.append(',').append(String.format(Locale.US, "%.4f,%.4f,%.4f", 1f - p.x(), p.y(), p.z()))
            }
            val b = sb.toString().toByteArray()
            socket?.send(DatagramPacket(b, b.size, addr, PORT))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false; hands = 0
        provider?.unbindAll()
        exec.shutdown()
        landmarker?.close()
        socket?.close()
        super.onDestroy()
    }
}
