package com.example.handsender

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var button: Button
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        val info = TextView(this).apply {
            text = "1) Нажмите «Запустить».\n2) Откройте Zalith и играйте - приложение шлёт руки в мод.\n" +
                    "Держите телефон так, чтобы фронтальная камера видела ваши руки."
            textSize = 16f
        }
        status = TextView(this).apply { textSize = 18f; setPadding(0, 32, 0, 32) }
        val prefs = getSharedPreferences(HandService.PREFS, MODE_PRIVATE)
        val gpu = CheckBox(this).apply {
            text = "GPU (может быть быстрее; применится при следующем запуске)"
            isChecked = prefs.getBoolean(HandService.PREF_GPU, false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(HandService.PREF_GPU, on).apply() }
        }
        button = Button(this)
        button.setOnClickListener {
            if (HandService.running) stopService(Intent(this, HandService::class.java))
            else ensurePermissionsThenStart()
        }
        root.addView(info); root.addView(status); root.addView(gpu); root.addView(button)
        setContentView(root)
        tick()
    }

    private fun tick() {
        status.text = if (HandService.running) "Работает. Рук в кадре: ${HandService.hands}, кадров/с: ${"%.0f".format(HandService.fps)}" else "Остановлено"
        button.text = if (HandService.running) "Остановить" else "Запустить"
        ui.postDelayed({ tick() }, 400)
    }

    private fun ensurePermissionsThenStart() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) start() else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) start()
    }

    private fun start() = startForegroundService(Intent(this, HandService::class.java))
}
