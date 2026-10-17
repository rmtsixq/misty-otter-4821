package com.sixq.gozjest.core.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

/**
 * Turns the main device's screen as dark as an app is allowed to make it.
 *
 * Three things happen together, because any one alone still leaks light:
 *
 *  * an opaque black view is laid over everything, including the cutout area;
 *  * the window brightness override drops to the panel minimum;
 *  * the status and navigation bars are hidden, and the camera preview is
 *    detached so no bright surface is being composited underneath.
 *
 * On an OLED panel the result is indistinguishable from a screen that is off.
 * On an LCD the backlight cannot be switched off by an app, so a very faint
 * glow remains — that is a hardware limit, not a setting.
 *
 * The screen deliberately stays *on*: the front camera has to keep delivering
 * frames, otherwise the eye-closure gesture could never bring the screen back.
 *
 * Escape hatch: press and hold anywhere for three seconds (or press a volume
 * key) to leave the blackout by hand, in case the room is too dark for the
 * face tracker to see the eyes open again.
 */
class BlackoutController(
    private val activity: Activity,
    private val onManualExit: () -> Unit,
    private val onStateChanged: (black: Boolean) -> Unit,
) {

    var isBlack: Boolean = false
        private set

    private var overlay: View? = null
    private var longPressStartX = 0f
    private var longPressStartY = 0f

    private val longPressRunnable = Runnable {
        if (isBlack) {
            disable()
            onManualExit()
        }
    }

    fun toggle() = if (isBlack) disable() else enable()

    @SuppressLint("ClickableViewAccessibility")
    fun enable() {
        if (isBlack) return
        isBlack = true

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val view = View(activity).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            keepScreenOn = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setOnTouchListener { _, event -> handleTouch(event) }
        }
        overlay = view
        root.addView(view)
        view.bringToFront()

        activity.window.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attributes = attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            attributes = attributes.apply { screenBrightness = MINIMUM_BRIGHTNESS }
            statusBarColor = Color.BLACK
            navigationBarColor = Color.BLACK
        }
        systemBars(visible = false)
        onStateChanged(true)
    }

    fun disable() {
        if (!isBlack) return
        isBlack = false

        overlay?.let { view ->
            view.removeCallbacks(longPressRunnable)
            (view.parent as? ViewGroup)?.removeView(view)
        }
        overlay = null

        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        systemBars(visible = true)
        onStateChanged(false)
    }

    /** Lets the activity route a volume key press here as a manual escape. */
    fun onEscapeKey(): Boolean {
        if (!isBlack) return false
        disable()
        onManualExit()
        return true
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val view = overlay ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressStartX = event.rawX
                longPressStartY = event.rawY
                view.postDelayed(longPressRunnable, MANUAL_EXIT_HOLD_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                val drifted = abs(event.rawX - longPressStartX) > MANUAL_EXIT_SLOP ||
                    abs(event.rawY - longPressStartY) > MANUAL_EXIT_SLOP
                if (drifted) view.removeCallbacks(longPressRunnable)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                view.removeCallbacks(longPressRunnable)
        }
        // Swallow the gesture so stray taps cannot reach the UI underneath.
        return true
    }

    private fun systemBars(visible: Boolean) {
        val controller = WindowInsetsControllerCompat(
            activity.window,
            activity.window.decorView,
        )
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private companion object {
        /**
         * 0f is the panel minimum rather than "off"; Android gives an app no
         * way to cut the backlight while keeping the activity resumed.
         */
        const val MINIMUM_BRIGHTNESS = 0f

        const val MANUAL_EXIT_HOLD_MS = 3_000L
        const val MANUAL_EXIT_SLOP = 60f
    }
}
