package com.example.handsender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import kotlin.math.hypot

class HandService : LifecycleService() {
    companion object {
        @Volatile var running = false
        @Volatile var hands = 0
        @Volatile var fps = 0f
        const val PORT = 25566
        const val PREFS = "hand"
        const val PREF_GPU = "gpu"
    }

    private val exec = Executors.newSingleThreadExecutor()
    private var landmarker: HandLandmarker? = null
    private var provider: ProcessCameraProvider? = null
    private var socket: DatagramSocket? = null
    private val addr: InetAddress = InetAddress.getByName("127.0.0.1")
    private var lastTs = 0L

    // последнее положение запястья каждой руки (для устойчивого "левая/правая")
    private val lastX = FloatArray(2)
    private val lastY = FloatArray(2)
    private val lastT = LongArray(2)

    private var fpsFrames = 0
    private var fpsStart = 0L

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
        val gpu = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_GPU, false)

        // Модель создаём и используем на ОДНОМ потоке (exec): так надо GPU-делегату, и главный поток не подвисает.
        exec.execute {
            try {
                landmarker = createLandmarker(gpu)
            } catch (t: Throwable) {
                Log.e("HandSender", "landmarker init failed", t)
                stopSelf()
                return@execute
            }
            ContextCompat.getMainExecutor(this).execute { if (running) startCamera() }
        }
    }

    private fun createLandmarker(gpu: Boolean): HandLandmarker {
        fun build(delegate: Delegate): HandLandmarker = HandLandmarker.createFromOptions(
            this,
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath("hand_landmarker.task")
                        .setDelegate(delegate)
                        .build()
                )
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(2)
                // пониженные пороги: рука реже "слетает" при быстрых движениях
                .setMinHandDetectionConfidence(0.4f)
                .setMinHandPresenceConfidence(0.4f)
                .setMinTrackingConfidence(0.4f)
                .build()
        )
        if (gpu) {
            try {
                return build(Delegate.GPU)
            } catch (t: Throwable) {
                Log.w("HandSender", "GPU delegate failed, falling back to CPU", t)
            }
        }
        return build(Delegate.CPU)
    }

    /** Лучший диапазон FPS фронтальной камеры (до 30). Без этого в плохом свете камера падает до 15 кадров/с. */
    private fun pickFpsRange(): Range<Int>? {
        try {
            val cm = getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            } ?: return null
            val ranges = cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
            return ranges.filter { it.upper in 24..30 }
                .maxWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower }))
        } catch (t: Throwable) {
            return null
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildAnalysis(): ImageAnalysis {
        val b = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        pickFpsRange()?.let {
            Camera2Interop.Extender(b).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
        }
        return b.build()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!running) return@addListener
            val p = future.get()
            provider = p
            val analysis = buildAnalysis()
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
        val ts = maxOf(lastTs + 1, img.imageInfo.timestamp / 1_000_000L); lastTs = ts
        val res = lm.detectForVideo(BitmapImageBuilder(bmp).build(), opts, ts)
        val n = res.landmarks().size
        hands = n
        countFps()
        if (n == 0) return

        val idxs = assign(res, System.currentTimeMillis())
        for (i in 0 until n) {
            val lms = res.landmarks()[i]
            // кадр фронтальной камеры не зеркальный: x нужно отразить
            val sb = StringBuilder(700).append(idxs[i])
            for (p in lms) {
                sb.append(',').append(fmt(1f - p.x())).append(',').append(fmt(p.y())).append(',').append(fmt(p.z()))
            }
            val b = sb.toString().toByteArray()
            socket?.send(DatagramPacket(b, b.size, addr, PORT))
        }
    }

    private fun fmt(v: Float): String = (Math.round(v * 10000f) / 10000f).toString()

    private fun countFps() {
        val now = System.currentTimeMillis()
        if (fpsStart == 0L) fpsStart = now
        fpsFrames++
        if (now - fpsStart >= 1000) {
            fps = fpsFrames * 1000f / (now - fpsStart)
            fpsFrames = 0
            fpsStart = now
        }
    }

    /**
     * Какая рука левая (0), какая правая (1). Метка MediaPipe иногда мигает, и рука "перепрыгивает" на другую
     * сторону, поэтому опираемся ещё и на положение запястья в прошлых кадрах.
     * Метки MediaPipe инвертированы (кадр не зеркальный): "Right" = левая рука пользователя.
     */
    private fun assign(res: HandLandmarkerResult, now: Long): IntArray {
        val n = res.landmarks().size
        val out = IntArray(n)
        val wx = FloatArray(n) { 1f - res.landmarks()[it][0].x() }
        val wy = FloatArray(n) { res.landmarks()[it][0].y() }
        val lab = IntArray(n) { if (res.handedness()[it][0].categoryName() == "Right") 0 else 1 }
        val known = BooleanArray(2) { lastT[it] != 0L && now - lastT[it] < 600 }
        fun d(i: Int, k: Int): Float = hypot(wx[i] - lastX[k], wy[i] - lastY[k])

        if (n >= 2) {
            if (known[0] && known[1]) {
                val straight = d(0, 0) + d(1, 1)
                val swapped = d(0, 1) + d(1, 0)
                if (straight <= swapped) { out[0] = 0; out[1] = 1 } else { out[0] = 1; out[1] = 0 }
            } else if (lab[0] != lab[1]) {
                out[0] = lab[0]; out[1] = lab[1]
            } else if (wx[0] <= wx[1]) {
                out[0] = 0; out[1] = 1
            } else {
                out[0] = 1; out[1] = 0
            }
        } else if (n == 1) {
            val l = lab[0]
            val o = 1 - l
            val dl = if (known[l]) d(0, l) else 9f
            val dOther = if (known[o]) d(0, o) else 9f
            out[0] = if (dOther < 0.2f && dOther < dl) o else l
        }
        for (i in 0 until n) {
            lastX[out[i]] = wx[i]; lastY[out[i]] = wy[i]; lastT[out[i]] = now
        }
        return out
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false; hands = 0; fps = 0f
        provider?.unbindAll()
        exec.execute {
            try { landmarker?.close() } catch (t: Throwable) { Log.w("HandSender", "close failed", t) }
            landmarker = null
        }
        exec.shutdown()
        socket?.close()
        super.onDestroy()
    }
}
