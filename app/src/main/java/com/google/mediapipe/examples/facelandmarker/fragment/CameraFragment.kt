package com.google.mediapipe.examples.facelandmarker.fragment

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_DRAGGING
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.examples.facelandmarker.MainViewModel
import com.google.mediapipe.examples.facelandmarker.R
import com.google.mediapipe.examples.facelandmarker.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@ExperimentalCamera2Interop
class CameraFragment : Fragment(), FaceLandmarkerHelper.LandmarkerListener {

    private var participantId: String? = null
    private var isDetectionPaused = false
    private var isMacroMode = false

    private enum class CaptureState {
        IDLE,
        AWAITING_PRE_CAPTURE_METERING,
        AWAITING_CENTERING,
        AWAITING_STABILITY,
        READY_TO_CAPTURE,
        LOCKING_FOCUS,
        CAPTURING,
        AWAITING_RECENTER,
    }

    companion object {
        private const val TAG = "Face Landmarker"
        private const val IMAGES_TO_CAPTURE = 5
        private const val STABILITY_THRESHOLD = 0.01f
        private const val CENTER_THRESHOLD = 0.05f // Tighter threshold
        private const val STABILITY_CHECK_DELAY_MS = 500L
        private const val CAPTURE_SERIES_DELAY_MS = 100L
        private const val FOCUS_CONFIRMATION_DELAY_MS = 2000L // 2-second delay for focus
        private const val SHARPNESS_THRESHOLD = 10.0
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private val faceBlendshapesResultAdapter by lazy { FaceBlendshapesResultAdapter() }

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT
    private var imageCapture: ImageCapture? = null

    private lateinit var backgroundExecutor: ExecutorService

    private var captureState = CaptureState.IDLE
    private var imagesCaptured = 0
    private var isCapturingRightEye = true
    private var lastFaceBoundingBox: RectF? = null
    private var lastToastMessage: String? = null

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
        }
        backgroundExecutor.execute {
            if (!this::faceLandmarkerHelper.isInitialized || faceLandmarkerHelper.isClose()) {
                faceLandmarkerHelper.setupFaceLandmarker()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::faceLandmarkerHelper.isInitialized) {
            viewModel.setMaxFaces(faceLandmarkerHelper.maxNumFaces)
            viewModel.setMinFaceDetectionConfidence(faceLandmarkerHelper.minFaceDetectionConfidence)
            viewModel.setMinFaceTrackingConfidence(faceLandmarkerHelper.minFaceTrackingConfidence)
            viewModel.setMinFacePresenceConfidence(faceLandmarkerHelper.minFacePresenceConfidence)
            viewModel.setDelegate(faceLandmarkerHelper.currentDelegate)
            backgroundExecutor.execute { faceLandmarkerHelper.clearFaceLandmarker() }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arguments?.let {
            participantId = it.getString("participantId")
        }

        with(fragmentCameraBinding.recyclerviewResults) {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = faceBlendshapesResultAdapter
        }

        backgroundExecutor = Executors.newSingleThreadExecutor()
        fragmentCameraBinding.viewFinder.post { setUpCamera() }

        backgroundExecutor.execute {
            faceLandmarkerHelper = FaceLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minFaceDetectionConfidence = viewModel.currentMinFaceDetectionConfidence,
                minFaceTrackingConfidence = viewModel.currentMinFaceTrackingConfidence,
                minFacePresenceConfidence = viewModel.currentMinFacePresenceConfidence,
                maxNumFaces = viewModel.currentMaxFaces,
                currentDelegate = viewModel.currentDelegate,
                faceLandmarkerHelperListener = this
            )
        }

        initBottomSheetControls()

        fragmentCameraBinding.captureButton.setOnClickListener {
            if (captureState == CaptureState.IDLE) {
                startCaptureFlow()
            }
        }

        fragmentCameraBinding.cameraSwitchButton.setOnClickListener {
            cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            bindCameraUseCases()
        }

        fragmentCameraBinding.macroButton.setOnClickListener {
            isMacroMode = !isMacroMode
            bindCameraUseCases()
        }

        fragmentCameraBinding.stopButton.setOnClickListener {
            resetCaptureSequence("Capture stopped.")
        }

        fragmentCameraBinding.backButton.setOnClickListener {
            findNavController().navigate(R.id.action_camera_to_login)
        }
    }

    private fun startCaptureFlow() {
        captureState = CaptureState.AWAITING_PRE_CAPTURE_METERING
        imagesCaptured = 0
        isCapturingRightEye = true
        isDetectionPaused = false
        fragmentCameraBinding.captureButton.visibility = View.GONE
        fragmentCameraBinding.cameraSwitchButton.visibility = View.GONE
        fragmentCameraBinding.stopButton.visibility = View.VISIBLE
        updateUiWithMessage("Preparing camera...")
    }

    private fun proceedToLeftEyeCapture() {
        captureState = CaptureState.AWAITING_RECENTER
        isCapturingRightEye = false
        isDetectionPaused = false
        camera?.cameraControl?.cancelFocusAndMetering()
        zoomOut()
        fragmentCameraBinding.overlay.isTargetingRightEye = false
        fragmentCameraBinding.overlay.showCenteringGuide = true
        fragmentCameraBinding.overlay.invalidate()
        updateUiWithMessage("Right eye done. Please align your left eye with the crosshair.")
    }

    private fun zoomIn(eyeBoundingBox: RectF): ListenableFuture<Void> {
        val viewFinder = fragmentCameraBinding.viewFinder
        val viewWidth = viewFinder.width
        val eyeWidthInView = eyeBoundingBox.width() * viewWidth
        val desiredEyeWidth = viewWidth * 0.4f
        val zoomRatio = desiredEyeWidth / eyeWidthInView
        val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1.0f
        val finalZoom = min(zoomRatio, maxZoom)
        return camera!!.cameraControl.setZoomRatio(finalZoom)
    }

    private fun zoomOut() {
        camera?.cameraControl?.setZoomRatio(1.0f)
    }

    private fun focusOnCenterAndCapture() {
        val cameraControl = camera?.cameraControl ?: return
        cameraControl.cancelFocusAndMetering()

        val vf = fragmentCameraBinding.viewFinder
        val factory = vf.meteringPointFactory
        val centerPoint = factory.createPoint(vf.width / 2f, vf.height / 2f)

        val action = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        val future = cameraControl.startFocusAndMetering(action)
        future.addListener({
            try {
                val result = future.get()
                if (result.isFocusSuccessful) {
                    updateUiWithMessage("Focus locked. Capturing...")
                    if (captureState == CaptureState.LOCKING_FOCUS) {
                        captureState = CaptureState.CAPTURING
                        imagesCaptured = 0
                        takePictureSeries()
                    }
                } else {
                    resetCaptureSequence("Focus failed. Please try again.")
                }
            } catch (e: Exception) {
                resetCaptureSequence("Focus failed. Please try again.")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }


    private fun resetCaptureSequence(reason: String) {
        activity?.runOnUiThread {
            if (captureState == CaptureState.IDLE) return@runOnUiThread
            isDetectionPaused = false
            lockAeAwb(false) // Unlock AE/AWB
            zoomOut()
            captureState = CaptureState.IDLE
            imagesCaptured = 0
            fragmentCameraBinding.captureButton.visibility = View.VISIBLE
            fragmentCameraBinding.cameraSwitchButton.visibility = View.VISIBLE
            fragmentCameraBinding.stopButton.visibility = View.GONE
            fragmentCameraBinding.overlay.isTargetingRightEye = null
            fragmentCameraBinding.overlay.showCenteringGuide = false
            fragmentCameraBinding.overlay.showEyeAlignmentBox = false
            updateUiWithMessage(reason)
        }
    }

    private fun calculateSharpness(uri: Uri): Double {
        val bitmap = try {
            val inputStream =
                requireContext().contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from URI", e)
            return 0.0
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var sumOfLaplacian = 0.0

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                // Convert to grayscale and apply Laplacian operator
                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                val gray = (r + g + b) / 3

                val p_top = pixels[(y - 1) * width + x]
                val r_top = (p_top shr 16) and 0xff
                val g_top = (p_top shr 8) and 0xff
                val b_top = p_top and 0xff
                val gray_top = (r_top + g_top + b_top) / 3

                val p_bottom = pixels[(y + 1) * width + x]
                val r_bottom = (p_bottom shr 16) and 0xff
                val g_bottom = (p_bottom shr 8) and 0xff
                val b_bottom = p_bottom and 0xff
                val gray_bottom = (r_bottom + g_bottom + b_bottom) / 3

                val p_left = pixels[y * width + (x - 1)]
                val r_left = (p_left shr 16) and 0xff
                val g_left = (p_left shr 8) and 0xff
                val b_left = p_left and 0xff
                val gray_left = (r_left + g_left + b_left) / 3

                val p_right = pixels[y * width + (x + 1)]
                val r_right = (p_right shr 16) and 0xff
                val g_right = (p_right shr 8) and 0xff
                val b_right = p_right and 0xff
                val gray_right = (r_right + g_right + b_right) / 3

                val laplacian =
                    (gray_top + gray_bottom + gray_left + gray_right) - 4 * gray
                sumOfLaplacian += laplacian * laplacian
            }
        }
        return sumOfLaplacian / (width * height)
    }

    private fun takePictureSeries() {
        if (captureState != CaptureState.CAPTURING) return

        if (imagesCaptured >= IMAGES_TO_CAPTURE) {
            if (isCapturingRightEye) {
                proceedToLeftEyeCapture()
            } else {
                resetCaptureSequence("All captures finished.")
            }
            return
        }

        val eyeIdentifier = if (isCapturingRightEye) "Right" else "Left"
        val photoFile = ContentValues().apply {
            put(
                MediaStore.MediaColumns.DISPLAY_NAME,
                "${participantId}_${eyeIdentifier}_${imagesCaptured + 1}.jpg"
            )
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            requireContext().contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            photoFile
        ).build()

        imageCapture?.takePicture(outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    resetCaptureSequence("Photo capture failed.")
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: return
                    val sharpness = calculateSharpness(savedUri)
                    Log.d(TAG, "Image sharpness: $sharpness")

                    if (sharpness >= SHARPNESS_THRESHOLD) {
                        imagesCaptured++
                        val msg =
                            "$eyeIdentifier eye photo ${imagesCaptured}/${IMAGES_TO_CAPTURE} saved."
                        Log.d(TAG, msg)
                        updateUiWithMessage(msg)
                    } else {
                        requireContext().contentResolver.delete(savedUri, null, null)
                        val msg =
                            "$eyeIdentifier eye photo ${imagesCaptured + 1}/${IMAGES_TO_CAPTURE} is not sharp enough. Retaking."
                        Log.d(TAG, msg)
                        updateUiWithMessage(msg)
                    }

                    Handler(Looper.getMainLooper()).postDelayed(
                        { takePictureSeries() },
                        CAPTURE_SERIES_DELAY_MS
                    )
                }
            })
    }

    private fun lockAeAwb(lock: Boolean) {
        val cam = camera ?: return
        val control = Camera2CameraControl.from(cam.cameraControl)
        val info = Camera2CameraInfo.from(cam.cameraInfo)

        val awbLockSupported = info.getCameraCharacteristic<Boolean>(
            CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE
        ) ?: false

        val opts = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_LOCK, lock)
            .apply {
                if (awbLockSupported) {
                    setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK, lock)
                }
            }
            .build()

        control.addCaptureRequestOptions(opts)
    }

    private fun initBottomSheetControls() {
        // ... (omitted for brevity)
    }

    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider =
            cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelectorBuilder = CameraSelector.Builder().requireLensFacing(cameraFacing)

        if (isMacroMode) {
            cameraSelectorBuilder.addCameraFilter { cameras ->
                val macroCameras = cameras.filter { cameraInfo ->
                    val camera2CameraInfo = Camera2CameraInfo.from(cameraInfo)
                    val characteristics =
                        camera2CameraInfo.getCameraCharacteristic<IntArray>(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    characteristics?.contains(CameraMetadata.CONTROL_AF_MODE_MACRO) == true
                }
                if (macroCameras.isNotEmpty()) {
                    listOf(macroCameras.first())
                } else {
                    emptyList()
                }
            }
        }

        val cameraSelector = cameraSelectorBuilder.build()

        preview = Preview.Builder()
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(
                        backgroundExecutor,
                        this::detectFace
                    )
                }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer,
                imageCapture
            )
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)

            if (cameraFacing == CameraSelector.LENS_FACING_BACK) {
                fragmentCameraBinding.macroButton.visibility = View.VISIBLE
            } else {
                fragmentCameraBinding.macroButton.visibility = View.GONE
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectFace(imageProxy: ImageProxy) {
        if (isDetectionPaused) {
            imageProxy.close()
            return
        }
        faceLandmarkerHelper.detectLiveStream(
            imageProxy = imageProxy,
            isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)//hello
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    private fun getEyeBoundingBox(
        faceLandmarkerResult: FaceLandmarkerResult, isRightEye: Boolean
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

    private fun getFaceBoundingBox(faceLandmarkerResult: FaceLandmarkerResult): RectF? {
        val faceLandmarks =
            faceLandmarkerResult.faceLandmarks().getOrNull(0) ?: return null

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (landmark in faceLandmarks) {
            minX = min(minX, landmark.x())
            minY = min(minY, landmark.y())
            maxX = max(maxX, landmark.x())
            maxY = max(maxY, landmark.y())
        }

        return RectF(minX, minY, maxX, maxY)
    }

    private fun updateUiWithMessage(message: String) {
        if (lastToastMessage == message) return
        lastToastMessage = message
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                if (captureState == CaptureState.LOCKING_FOCUS || captureState == CaptureState.CAPTURING) {
                    fragmentCameraBinding.overlay.clear()
                    isDetectionPaused = true
                } else {
                    isDetectionPaused = false
                    if (fragmentCameraBinding.recyclerviewResults.scrollState != SCROLL_STATE_DRAGGING) {
                        faceBlendshapesResultAdapter.updateResults(resultBundle.result)
                        faceBlendshapesResultAdapter.notifyDataSetChanged()
                    }
                    fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
                        String.format("%d ms", resultBundle.inferenceTime)
                    fragmentCameraBinding.overlay.setResults(
                        resultBundle.result,
                        resultBundle.inputImageHeight,
                        resultBundle.inputImageWidth,
                        RunningMode.LIVE_STREAM
                    )
                }
                fragmentCameraBinding.overlay.invalidate()

                val faceBoundingBox = getFaceBoundingBox(resultBundle.result)
                val eyeBoundingBox =
                    getEyeBoundingBox(resultBundle.result, isCapturingRightEye)

                if (faceBoundingBox == null || eyeBoundingBox == null) {
                    lastFaceBoundingBox = null
                    return@runOnUiThread
                }

                when (captureState) {
                    CaptureState.AWAITING_PRE_CAPTURE_METERING -> {
                        val cameraControl = camera?.cameraControl ?: return@runOnUiThread
                        val vf = fragmentCameraBinding.viewFinder
                        val factory = vf.meteringPointFactory
                        
                        val viewW = vf.width.toFloat()
                        val viewH = vf.height.toFloat()
                        val scale = max(viewW / resultBundle.inputImageWidth, viewH / resultBundle.inputImageHeight)
                        val offX = (viewW - resultBundle.inputImageWidth * scale) / 2f
                        val offY = (viewH - resultBundle.inputImageHeight * scale) / 2f

                        val faceCenterXInView = faceBoundingBox.centerX() * resultBundle.inputImageWidth * scale + offX
                        val faceCenterYInView = faceBoundingBox.centerY() * resultBundle.inputImageHeight * scale + offY

                        val faceCenterPoint = factory.createPoint(faceCenterXInView, faceCenterYInView)
                        
                        val action = FocusMeteringAction.Builder(faceCenterPoint, FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                        val future = cameraControl.startFocusAndMetering(action)
                        future.addListener({
                            lockAeAwb(true)
                            updateUiWithMessage("Camera ready. Please align your right eye.")
                            captureState = CaptureState.AWAITING_CENTERING
                        }, ContextCompat.getMainExecutor(requireContext()))
                    }

                    CaptureState.AWAITING_CENTERING, CaptureState.AWAITING_RECENTER -> {
                        fragmentCameraBinding.overlay.showCenteringGuide = true
                        val viewFinder = fragmentCameraBinding.viewFinder
                        val viewCenterX = viewFinder.width / 2f
                        val viewCenterY = viewFinder.height / 2f

                        val scaleFactor = max(
                            viewFinder.width * 1f / resultBundle.inputImageWidth,
                            viewFinder.height * 1f / resultBundle.inputImageHeight
                        )
                        val offsetX =
                            (viewFinder.width - resultBundle.inputImageWidth * scaleFactor) / 2
                        val offsetY =
                            (viewFinder.height - resultBundle.inputImageHeight * scaleFactor) / 2

                        val eyeCenterXInView =
                            eyeBoundingBox.centerX() * resultBundle.inputImageWidth * scaleFactor + offsetX
                        val eyeCenterYInView =
                            eyeBoundingBox.centerY() * resultBundle.inputImageHeight * scaleFactor + offsetY

                        val dX = abs(eyeCenterXInView - viewCenterX)
                        val dY = abs(eyeCenterYInView - viewCenterY)

                        if (dX < CENTER_THRESHOLD * viewFinder.width && dY < CENTER_THRESHOLD * viewFinder.height) {
                            captureState = CaptureState.AWAITING_STABILITY
                            updateUiWithMessage("Eye centered. Hold steady.")
                            lastFaceBoundingBox = faceBoundingBox
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (captureState == CaptureState.AWAITING_STABILITY) {
                                    captureState = CaptureState.READY_TO_CAPTURE
                                }
                            }, STABILITY_CHECK_DELAY_MS)
                        } else {
                            val eyeName = if (isCapturingRightEye) "right" else "left"
                            updateUiWithMessage("Please align your $eyeName eye with the crosshair.")
                        }
                    }

                    CaptureState.AWAITING_STABILITY -> {
                        val lastBox = lastFaceBoundingBox
                        if (lastBox != null) {
                            val centerX = faceBoundingBox.centerX()
                            val lastCenterX = lastBox.centerX()
                            if (abs(centerX - lastCenterX) > STABILITY_THRESHOLD) {
                                captureState =
                                    if (isCapturingRightEye) CaptureState.AWAITING_CENTERING else CaptureState.AWAITING_RECENTER
                                fragmentCameraBinding.overlay.showCenteringGuide =
                                    true
                                fragmentCameraBinding.overlay.invalidate()
                                updateUiWithMessage("Movement detected. Please re-center and hold steady.")
                            }
                        }
                        lastFaceBoundingBox = faceBoundingBox
                    }

                    CaptureState.READY_TO_CAPTURE -> {
                        captureState = CaptureState.LOCKING_FOCUS
                        isDetectionPaused = true
                        fragmentCameraBinding.overlay.clear()
                        val zoomFuture = zoomIn(eyeBoundingBox)
                        zoomFuture.addListener({
                            val viewFinder = fragmentCameraBinding.viewFinder
                            val boxSize = min(viewFinder.width, viewFinder.height) * 0.4f
                            val left = (viewFinder.width - boxSize) / 2f
                            val top = (viewFinder.height - boxSize) / 2f
                            val right = left + boxSize
                            val bottom = top + boxSize
                            val eyeAlignmentBox = RectF(left, top, right, bottom)

                            fragmentCameraBinding.overlay.eyeAlignmentBox = eyeAlignmentBox
                            fragmentCameraBinding.overlay.showEyeAlignmentBox = true
                            fragmentCameraBinding.overlay.invalidate()
                            updateUiWithMessage("Align your eye with the box.")

                            Handler(Looper.getMainLooper()).postDelayed({
                                focusOnCenterAndCapture()
                            }, FOCUS_CONFIRMATION_DELAY_MS)
                        }, ContextCompat.getMainExecutor(requireContext()))
                    }

                    else -> { /* Do nothing in other states */
                    }
                }
            }
        }
    }

    override fun onEmpty() {
        fragmentCameraBinding.overlay.clear()
        activity?.runOnUiThread {
            faceBlendshapesResultAdapter.updateResults(null)
            faceBlendshapesResultAdapter.notifyDataSetChanged()
        }
        lastFaceBoundingBox = null
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            faceBlendshapesResultAdapter.updateResults(null)
            faceBlendshapesResultAdapter.notifyDataSetChanged()
            if (errorCode == FaceLandmarkerHelper.GPU_ERROR) {
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    FaceLandmarkerHelper.DELEGATE_CPU, false
                )
            }
        }
    }
}