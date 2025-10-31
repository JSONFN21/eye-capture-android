package com.google.mediapipe.examples.facelandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.examples.facelandmarker.fragment.FaceLandmarkerHelper
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var results: FaceLandmarkerResult? = null
    private var linePaint = Paint()
    private var pointPaint = Paint()
    private var targetPaint = Paint()
    private var centeringPaint = Paint()
    private var eyeBoxPaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    var isTargetingRightEye: Boolean? = null
    var showCenteringGuide: Boolean = false
    var showEyeAlignmentBox: Boolean = false
    var eyeAlignmentBox: RectF? = null

    init {
        initPaints()
    }

    fun clear() {
        results = null
        isTargetingRightEye = null
        showCenteringGuide = false
        showEyeAlignmentBox = false
        eyeAlignmentBox = null
        linePaint.reset()
        pointPaint.reset()
        targetPaint.reset()
        centeringPaint.reset()
        eyeBoxPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        linePaint.color =
            ContextCompat.getColor(context!!, R.color.mp_color_primary)
        linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        linePaint.style = Paint.Style.STROKE

        pointPaint.color = Color.YELLOW
        pointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        pointPaint.style = Paint.Style.FILL

        targetPaint.color = Color.GREEN
        targetPaint.strokeWidth = TARGET_STROKE_WIDTH
        targetPaint.style = Paint.Style.STROKE

        centeringPaint.color = Color.RED
        centeringPaint.strokeWidth = CENTERING_STROKE_WIDTH
        centeringPaint.style = Paint.Style.STROKE
        centeringPaint.alpha = 128 // Semi-transparent
        
        eyeBoxPaint.color = Color.BLUE
        eyeBoxPaint.strokeWidth = TARGET_STROKE_WIDTH
        eyeBoxPaint.style = Paint.Style.STROKE
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        if (showCenteringGuide) {
            val centerX = width / 2f
            val centerY = height / 2f
            val crosshairSize = 50f
            canvas.drawLine(
                centerX - crosshairSize,
                centerY,
                centerX + crosshairSize,
                centerY,
                centeringPaint
            )
            canvas.drawLine(
                centerX,
                centerY - crosshairSize,
                centerX,
                centerY + crosshairSize,
                centeringPaint
            )
        }
        
        if (showEyeAlignmentBox) {
            eyeAlignmentBox?.let {
                canvas.drawRect(it, eyeBoxPaint)
            }
        }

        if (results?.faceLandmarks().isNullOrEmpty()) {
            return
        }

        results?.let { faceLandmarkerResult ->
            val scaledImageWidth = imageWidth * scaleFactor
            val scaledImageHeight = imageHeight * scaleFactor
            val offsetX = (width - scaledImageWidth) / 2f
            val offsetY = (height - scaledImageHeight) / 2f

            faceLandmarkerResult.faceLandmarks().forEach { faceLandmarks ->
                drawFaceLandmarks(canvas, faceLandmarks, offsetX, offsetY)
                drawFaceConnectors(canvas, faceLandmarks, offsetX, offsetY)
            }

            isTargetingRightEye?.let { isRight ->
                getEyeBoundingBox(faceLandmarkerResult, isRight)?.let {
                    val left = it.left * scaledImageWidth + offsetX
                    val top = it.top * scaledImageHeight + offsetY
                    val right = it.right * scaledImageWidth + offsetX
                    val bottom = it.bottom * scaledImageHeight + offsetY
                    canvas.drawRect(left, top, right, bottom, targetPaint)
                }
            }
        }
    }

    private fun getEyeBoundingBox(
        faceLandmarkerResult: FaceLandmarkerResult,
        isRightEye: Boolean
    ): RectF? {
        val faceLandmarks =
            faceLandmarkerResult.faceLandmarks().getOrNull(0) ?: return null

        val eyeLandmarks = if (isRightEye) {
            FaceLandmarkerHelper.RIGHT_EYE_LANDMARKS
        } else {
            FaceLandmarkerHelper.LEFT_EYE_LANDMARKS
        }

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (index in eyeLandmarks) {
            val landmark = faceLandmarks[index]
            minX = min(minX, landmark.x())
            minY = min(minY, landmark.y())
            maxX = max(maxX, landmark.x())
            maxY = max(maxY, landmark.y())
        }
        return RectF(minX, minY, maxX, maxY)
    }

    private fun drawFaceLandmarks(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        faceLandmarks.forEach { landmark ->
            val x = landmark.x() * imageWidth * scaleFactor + offsetX
            val y = landmark.y() * imageHeight * scaleFactor + offsetY
            canvas.drawPoint(x, y, pointPaint)
        }
    }

    private fun drawFaceConnectors(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        FaceLandmarker.FACE_LANDMARKS_CONNECTORS.filterNotNull().forEach { connector ->
            val startLandmark = faceLandmarks.getOrNull(connector.start())
            val endLandmark = faceLandmarks.getOrNull(connector.end())

            if (startLandmark != null && endLandmark != null) {
                val startX = startLandmark.x() * imageWidth * scaleFactor + offsetX
                val startY = startLandmark.y() * imageHeight * scaleFactor + offsetY
                val endX = endLandmark.x() * imageWidth * scaleFactor + offsetX
                val endY = endLandmark.y() * imageHeight * scaleFactor + offsetY

                canvas.drawLine(startX, startY, endX, endY, linePaint)
            }
        }
    }

    fun setResults(
        faceLandmarkerResults: FaceLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE
    ) {
        results = faceLandmarkerResults
        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(width * 1f / imageWidth, height * 1f / imageHeight)
            }
            RunningMode.LIVE_STREAM -> {
                max(width * 1f / imageWidth, height * 1f / imageHeight)
            }
        }
        invalidate()
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 8F
        private const val TARGET_STROKE_WIDTH = 12F
        private const val CENTERING_STROKE_WIDTH = 8F
        private const val TAG = "Face Landmarker Overlay"
    }
}
