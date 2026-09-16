package com.example.duofoldwallpaper

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import kotlin.math.PI
import kotlin.math.abs

/**
 * Overlay-based fold transition wallpaper service.
 * Uses WindowManager to draw over all apps, unaffected by OS display switching.
 */
class OverlayFoldService : Service(), SensorEventListener {

    private lateinit var windowManager: WindowManager
    private lateinit var sensorManager: SensorManager
    private var surfaceView: SurfaceView? = null
    private var hingeSensor: Sensor? = null
    
    private var mHingeAngle = 0f
    private var displayedFold = 0f
    private var targetFold = 0f
    
    companion object {
        private const val TAG = "OverlayFoldService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayFoldService created")
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        
        if (hingeSensor == null) {
            Log.w(TAG, "Hinge angle sensor not available")
        }
        
        setupOverlay()
    }

    private fun setupOverlay() {
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.d(TAG, "Overlay surface created")
                    startRendering()
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    Log.d(TAG, "Overlay surface changed: $width x $height")
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Log.d(TAG, "Overlay surface destroyed")
                    stopRendering()
                }
            })
        }

        val params = LayoutParams().apply {
            type = LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            flags = LayoutParams.FLAG_NOT_FOCUSABLE or
                    LayoutParams.FLAG_NOT_TOUCHABLE or
                    LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

        windowManager.addView(surfaceView, params)
    }

    private fun startRendering() {
        sensorManager.registerListener(this, hingeSensor, SensorManager.SENSOR_DELAY_FASTEST)
    }

    private fun stopRendering() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HINGE_ANGLE) return
        val degrees = event.values[0]
        mHingeAngle = degrees
        targetFold = ((180.0 - degrees) * PI / 180.0).toFloat().coerceIn(0f, PI.toFloat())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OverlayFoldService destroyed")
        sensorManager.unregisterListener(this)
        if (surfaceView != null) {
            windowManager.removeView(surfaceView)
        }
    }
}
