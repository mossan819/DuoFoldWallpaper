package com.example.duofoldwallpaper

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

/**
 * Overlay-based fold transition wallpaper service.
 * Uses WindowManager to draw over all apps, unaffected by OS display switching.
 */
class OverlayFoldService : Service(), SensorEventListener {

    private lateinit var windowManager: WindowManager
    private lateinit var sensorManager: SensorManager
    private lateinit var deviceConfig: DeviceConfig
    private lateinit var runtimeShader: RuntimeShader
    private lateinit var paint: Paint
    private lateinit var choreographer: Choreographer
    
    private var surfaceView: SurfaceView? = null
    private var hingeSensor: Sensor? = null
    
    private var mHingeAngle = 0f
    private var displayedFold = 0f
    private var targetFold = 0f
    private var frameCallbackPosted = false
    
    private var canvasWidth = 0f
    private var canvasHeight = 0f
    private var imageWidth = 0f
    private var imageHeight = 0f
    
    companion object {
        private const val TAG = "OverlayFoldService"
        private const val BLUR_DOWNSCALE = 4f
        private const val LOW_PASS_ALPHA = 0.12f
        private const val SETTLE_EPSILON = 0.0002f
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayFoldService created")
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        choreographer = Choreographer.getInstance()
        
        deviceConfig = DeviceConfig.detect(this)
        Log.i(TAG, "Device config: $deviceConfig")
        
        runtimeShader = RuntimeShader(ShaderCode.DUO_SHADER)
        paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = runtimeShader }
        
        runtimeShader.setFloatUniform("uHingePos", deviceConfig.hingeRatio)
        runtimeShader.setFloatUniform("uEyeRatio", deviceConfig.perspectiveRatio)
        
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        
        loadAndBindWallpaper()
        setupOverlay()
    }

    private fun loadAndBindWallpaper() {
        val bitmap = loadWallpaperBitmap()
        imageWidth = bitmap.width.toFloat()
        imageHeight = bitmap.height.toFloat()

        val bitmapShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        runtimeShader.setInputShader("uImage", bitmapShader)

        val blurW = (bitmap.width / BLUR_DOWNSCALE).toInt().coerceAtLeast(1)
        val blurH = (bitmap.height / BLUR_DOWNSCALE).toInt().coerceAtLeast(1)
        val blurBitmap = Bitmap.createScaledBitmap(bitmap, blurW, blurH, true)
        val blurShader = BitmapShader(blurBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        runtimeShader.setInputShader("uImageBlur", blurShader)
        runtimeShader.setFloatUniform("uBlurScale", BLUR_DOWNSCALE)
    }

    private fun loadWallpaperBitmap(): Bitmap {
        val customFile = File(filesDir, "custom_wallpaper.png")
        if (customFile.exists()) {
            val opts = BitmapFactory.Options().apply { inScaled = false }
            BitmapFactory.decodeFile(customFile.absolutePath, opts)?.let {
                Log.d(TAG, "Loaded custom wallpaper: ${it.width}×${it.height}")
                return it
            }
        }
        val resId = resources.getIdentifier("wallpaper", "drawable", packageName)
        if (resId != 0) {
            val opts = BitmapFactory.Options().apply { inScaled = false }
            BitmapFactory.decodeResource(resources, resId, opts)?.let { return it }
        }
        Log.w(TAG, "No wallpaper found, using generated fallback")
        return createFallbackBitmap()
    }

    private fun createFallbackBitmap(): Bitmap {
        val w = deviceConfig.innerWidthPx
        val h = deviceConfig.innerHeightPx
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val gradient = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF1E1E2E.toInt(), 0xFF4B2E83.toInt(), 0xFF0F3460.toInt()),
            null, Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = gradient })
        return bmp
    }

    private fun setupOverlay() {
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.d(TAG, "Overlay surface created")
                    sensorManager.registerListener(
                        this@OverlayFoldService,
                        hingeSensor,
                        SensorManager.SENSOR_DELAY_FASTEST
                    )
                    postFrameCallback()
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    Log.d(TAG, "Overlay surface changed: $width x $height")
                    canvasWidth = width.toFloat()
                    canvasHeight = height.toFloat()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Log.d(TAG, "Overlay surface destroyed")
                    sensorManager.unregisterListener(this@OverlayFoldService)
                    choreographer.removeFrameCallback(frameCallback)
                    frameCallbackPosted = false
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

    private fun postFrameCallback() {
        if (!frameCallbackPosted) {
            frameCallbackPosted = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private val frameCallback = Choreographer.FrameCallback { nowNanos ->
        frameCallbackPosted = false
        drawFrame(nowNanos)
        val stillMoving = abs(targetFold - displayedFold) > SETTLE_EPSILON
        if (stillMoving) postFrameCallback()
    }

    private fun drawFrame(nowNanos: Long) {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return

        val holder = surfaceView?.holder ?: return
        val canvas = holder.lockCanvas() ?: return

        try {
            displayedFold += (targetFold - displayedFold) * LOW_PASS_ALPHA

            val hingeAngle = mHingeAngle
            val effectiveIsOuter = when {
                hingeAngle < 45f -> true
                hingeAngle > 135f -> false
                else -> canvasHeight > canvasWidth * 1.5f
            }

            val progress = if (effectiveIsOuter) {
                ((PI.toFloat() - displayedFold) / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
            } else {
                (displayedFold / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
            }
            val motion = FoldMath.smoothstep(progress)

            runtimeShader.setFloatUniform("uPixel", 1f / imageWidth, 1f / imageHeight)
            runtimeShader.setFloatUniform("uMotion", motion)
            runtimeShader.setFloatUniform("uRadiusMax", deviceConfig.maxBlurRadius)

            if (effectiveIsOuter) {
                val offsetX = FoldMath.outerUvOffsetX(displayedFold, deviceConfig)
                val scaleX = deviceConfig.coverToInnerRatio
                runtimeShader.setFloatUniform("uUvOffset", offsetX, 0f)
                runtimeShader.setFloatUniform("uUvScale", scaleX / canvasWidth, 1f / canvasHeight)
                runtimeShader.setFloatUniform("uGrad", offsetX, offsetX + scaleX)
                runtimeShader.setFloatUniform("uFold", 0f)
            } else {
                runtimeShader.setFloatUniform("uUvOffset", 0f, 0f)
                runtimeShader.setFloatUniform("uUvScale", 1f / canvasWidth, 1f / canvasHeight)
                runtimeShader.setFloatUniform("uGrad", deviceConfig.hingeRatio, 0f)
                runtimeShader.setFloatUniform("uFold", displayedFold)
            }

            canvas.drawRect(0f, 0f, canvasWidth, canvasHeight, paint)
        } catch (t: Throwable) {
            Log.e(TAG, "drawFrame failed", t)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HINGE_ANGLE) return
        val degrees = event.values[0]
        mHingeAngle = degrees
        targetFold = ((180.0 - degrees) * PI / 180.0).toFloat().coerceIn(0f, PI.toFloat())
        postFrameCallback()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OverlayFoldService destroyed")
        sensorManager.unregisterListener(this)
        choreographer.removeFrameCallback(frameCallback)
        if (surfaceView != null) {
            windowManager.removeView(surfaceView)
        }
    }
}
