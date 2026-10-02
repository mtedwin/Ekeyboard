package com.mtedwin.ekeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val path = Path()
    private val strokes = mutableListOf<List<PointF>>()
    private var currentStroke = mutableListOf<PointF>()
    var onStrokeFinished: (() -> Unit)? = null

    data class PointF(val x: Float, val y: Float, val t: Long)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val t = System.currentTimeMillis()
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                path.moveTo(x, y)
                currentStroke = mutableListOf()
                currentStroke.add(PointF(x, y, t))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                path.lineTo(x, y)
                currentStroke.add(PointF(x, y, t))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                strokes.add(currentStroke.toList())
                currentStroke.clear()
                onStrokeFinished?.invoke()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun clear() {
        path.reset()
        strokes.clear()
        currentStroke.clear()
        invalidate()
    }

    fun isEmpty(): Boolean = strokes.isEmpty() && currentStroke.isEmpty()

    fun getStrokes(): List<List<PointF>> {
        val allStrokes = strokes.toMutableList()
        if (currentStroke.isNotEmpty()) allStrokes.add(currentStroke.toList())
        return allStrokes
    }

    private fun buildInk(strokes: List<List<PointF>>): Ink {
        val inkBuilder = Ink.builder()
        for (stroke in strokes) {
            if (stroke.isEmpty()) continue
            val strokeBuilder = Ink.Stroke.builder()
            for (point in stroke) {
                strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, point.t))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }
        return inkBuilder.build()
    }

    private fun getModelIdentifier(): DigitalInkRecognitionModelIdentifier? {
        return try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag("zh-TW")
        } catch (e: Exception) {
            null
        } ?: try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag("zh-Hant")
        } catch (e: Exception) {
            null
        }
    }

    fun recognize(): String {
        val allStrokes = getStrokes()
        if (allStrokes.isEmpty()) return ""

        return try {
            val modelIdentifier = getModelIdentifier() ?: return ""
            val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
            val modelManager = RemoteModelManager.getInstance()
            val isDownloaded = Tasks.await(modelManager.isModelDownloaded(model))
            if (!isDownloaded) {
                Tasks.await(modelManager.download(model, DownloadConditions.Builder().build()))
            }
            val recognizer = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build()
            )
            val result = Tasks.await(recognizer.recognize(buildInk(allStrokes)))
            result.candidates.firstOrNull()?.text?.trim().orEmpty()
        } catch (e: Exception) {
            Log.e("DrawingView", "ML Kit Traditional Chinese recognition failed", e)
            ""
        }
    }
}
