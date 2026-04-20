package com.mtedwin.ekeyboard

import android.content.Context
import android.inputmethodservice.KeyboardView
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent

/**
 * A custom KeyboardView that bypasses the built-in GestureDetector
 * to prevent double-tap detection from swallowing rapid key taps.
 *
 * When [bypassTouchHandling] is true (for T13 mode), ALL touch events
 * are handled here: we detect which key was tapped and fire onKey
 * directly, without ever passing through KeyboardView's internal
 * GestureDetector / onModifiedTouchEvent.
 */
@Suppress("DEPRECATION")
class NoDoubleTapKeyboardView constructor(
    context: Context,
    attrs: AttributeSet? = null
) : KeyboardView(context, attrs) {

    companion object {
        private const val TAG = "NoDoubleTapKBView"
    }

    /** Set to true to bypass all internal KeyboardView touch handling. */
    var bypassTouchHandling: Boolean = false

    // Track the key pressed on ACTION_DOWN so we fire it on ACTION_UP
    private var downKeyIndex: Int = -1

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!bypassTouchHandling) {
            return super.onTouchEvent(event)
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val keyIndex = getKeyIndexAt(event.x.toInt(), event.y.toInt())
                downKeyIndex = keyIndex
                Log.d(TAG, "BYPASS ACTION_DOWN keyIndex=$keyIndex")

                // Highlight the key visually
                if (keyIndex >= 0) {
                    invalidateKey(keyIndex)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Track finger movement — update key if moved to different key
                val keyIndex = getKeyIndexAt(event.x.toInt(), event.y.toInt())
                if (keyIndex != downKeyIndex) {
                    downKeyIndex = keyIndex
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val keyIndex = downKeyIndex
                downKeyIndex = -1
                Log.d(TAG, "BYPASS ACTION_UP keyIndex=$keyIndex")

                if (keyIndex >= 0) {
                    val keys = keyboard?.keys
                    if (keys != null && keyIndex in keys.indices) {
                        val key = keys[keyIndex]
                        Log.d(TAG, "BYPASS firing onKey code=${key.codes[0]}")
                        onKeyboardActionListener?.onKey(key.codes[0], key.codes)
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                downKeyIndex = -1
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    /**
     * Find the key index at the given x,y coordinates,
     * accounting for key gaps/padding.
     */
    private fun getKeyIndexAt(x: Int, y: Int): Int {
        val keys = keyboard?.keys ?: return -1
        // First try exact hit
        for (i in keys.indices) {
            val key = keys[i]
            if (x >= key.x && x < key.x + key.width &&
                y >= key.y && y < key.y + key.height) {
                return i
            }
        }
        // If no exact hit, find nearest key within a tolerance
        var bestIndex = -1
        var bestDist = Int.MAX_VALUE
        val tolerance = 10 // pixels
        for (i in keys.indices) {
            val key = keys[i]
            val cx = key.x + key.width / 2
            val cy = key.y + key.height / 2
            val dx = x - cx
            val dy = y - cy
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = i
            }
        }
        // Only return nearest if within reasonable distance
        if (bestIndex >= 0) {
            val key = keys[bestIndex]
            if (x >= key.x - tolerance && x < key.x + key.width + tolerance &&
                y >= key.y - tolerance && y < key.y + key.height + tolerance) {
                return bestIndex
            }
        }
        return -1
    }
}
