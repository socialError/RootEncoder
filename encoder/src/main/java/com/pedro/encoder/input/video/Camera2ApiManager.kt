/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pedro.encoder.input.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import androidx.annotation.RequiresApi
import com.pedro.common.secureGet
import com.pedro.encoder.input.video.Camera2ResolutionCalculator.getOptimalResolution
import com.pedro.encoder.input.video.CameraHelper.Facing
import com.pedro.encoder.input.video.facedetector.FaceDetectorCallback
import com.pedro.encoder.input.video.facedetector.mapCamera2Faces
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Created by pedro on 4/03/17.
 *
 *
 *
 * Class for use surfaceEncoder to buffer encoder.
 * Advantage = you can use all resolutions.
 * Disadvantages = you cant control fps of the stream, because you cant know when the inputSurface
 * was renderer.
 *
 *
 * Note: you can use opengl for surfaceEncoder to buffer encoder on devices 21 < API > 16:
 * https://github.com/google/grafika
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class Camera2ApiManager(context: Context) {
    private val TAG = "Camera2ApiManager"

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        // zoom ramp frames queued ahead of the camera. Requests reach the sensor a few frames after
        // they are sent, so the queue must cover that lead; it grows by one each time it runs dry.
        const val ZOOM_RAMP_QUEUED_FRAMES_MIN = 4
        const val ZOOM_RAMP_QUEUED_FRAMES_MAX = 10
        // weight of a new sensor timestamp delta in the frame duration estimate
        const val FRAME_DURATION_SMOOTHING = 0.2
        // a gap longer than this many frames (dropped frames) counts only as this much
        const val FRAME_DURATION_MAX_GROWTH = 2.0
        // longest AF lock on the tapped point when the camera never reports the end of the scan
        const val FOCUS_SCAN_HOLD_MS = 3000L
    }

    private var cameraDevice: CameraDevice? = null
    private var surfaceEncoder: Surface? = null
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraCaptureSession: CameraCaptureSession? = null
    var isPrepared: Boolean = false
        private set
    private var cameraId: String = "0"
    private var physicalCameraId: String? = null
    private var facing = Facing.BACK
    private var builderInputSurface: CaptureRequest.Builder? = null
    private var fingerSpacing = 0f
    @Volatile
    private var zoomLevel = 0f
    var isLanternEnabled: Boolean = false
        private set
    var isVideoStabilizationEnabled: Boolean = false
        private set
    var isOpticalStabilizationEnabled: Boolean = false
        private set
    var isAutoFocusEnabled: Boolean = true
        private set
    var isAutoExposureEnabled: Boolean = false
        private set
    var isExposureLockEnabled: Boolean = false
        private set
    var isAutoWhiteBalanceEnabled: Boolean = true
        private set
    var isWhiteBalanceLockEnabled: Boolean = false
        private set
    var isRunning: Boolean = false
        private set
    // camera output size (sensor orientation), the tapped point is mapped through it; null when unknown
    private var captureSize: Size? = null
    // tapped point (tapToFocus), normalized in sensor orientation, guarded by zoomLock
    private var focusPoint: Pair<Float, Float>? = null
    // returns AF to the continuous mode after the scan of a tap, on the camera thread, guarded by zoomLock
    private var focusHoldRunnable: Runnable? = null
    // AF scan of a tap: id, the AF_TRIGGER_START request, its frame number (-1 until its result
    // comes) and the continuous mode to go back to; guarded by zoomLock
    private var focusScanId = 0
    private var focusTriggerRequest: CaptureRequest? = null
    private var focusTriggerFrame = -1L
    private var focusScanContinuousMode: Int? = null
    // a scan waits for its AF state: the capture results must come (needsCaptureCallback)
    @Volatile private var focusScanActive = false
    @Volatile private var focusListener: FocusListener? = null
    private var fps = 30
    private val semaphore = Semaphore(0)
    private var cameraCallbacks: CameraCallbacks? = null
    private var requiredSize: Size? = null
    var dynamicFps = false

    interface ImageCallback {
        fun onImageAvailable(image: Image)
    }

    private var sensorOrientation = 0
    private var faceSensorScale: Rect? = null
    private var faceDetectorCallback: FaceDetectorCallback? = null
    private var frameCapturedCallback: FrameCapturedCallback? = null
    private var faceDetectionEnabled = false
    private var faceDetectionMode = 0
    private var imageReader: ImageReader? = null
    private var availabilityCallback: CameraManager.AvailabilityCallback? = null

    private var customCaptureCompletedCallback: ((session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) -> Unit)? = null

    /**
     * Characteristics needed to set zoom and tap metering, read once per opened camera
     * (see [getZoomInfoLocked]). continuousFocusMode is null without continuous AF.
     */
    private class ZoomInfo(
        val cameraId: String,
        val range: Range<Float>,
        val useZoomRatio: Boolean,
        val activeArray: Rect?,
        val pipelineMaxDepth: Int,
        val preCorrectionArray: Rect?,
        val maxRegionsAf: Int,
        val maxRegionsAe: Int,
        val continuousFocusMode: Int?,
        val hasAutoFocusScan: Boolean,
        val distortionCorrectionSupported: Boolean,
        val sensorOrientation: Int,
        val isFront: Boolean
    )

    /**
     * Zoom ramp stepped by camera frames (see [animateZoom]). toLog is log2 of the target, rate is
     * in doublings per second.
     */
    private class ZoomAnimation(
        val target: Float,
        val toLog: Double,
        val rate: Float
    )

    // Guards the builder and the zoom state for all new zoom code (setZoomCached, animateZoom...).
    private val zoomLock = Any()
    private var zoomInfo: ZoomInfo? = null
    private var zoomAnimation: ZoomAnimation? = null
    // single-frame ramp captures sent to the session whose frame hasn't started yet (identity, not equals)
    private val zoomRampRequests = mutableListOf<CaptureRequest>()
    private var zoomRampQueueSize = ZOOM_RAMP_QUEUED_FRAMES_MIN
    // a ramp frame of the running ramp has started, so a non-ramp frame now means the queue ran dry
    private var zoomRampFrameStarted = false
    private var frameDurationNs = 0.0
    private var lastSensorTimestamp = 0L
    @Volatile
    private var zoomTrackingEnabled = false
    @Volatile
    private var appliedZoom = 0f
    @Volatile
    private var cameraThreadHandler: Handler? = null

    init {
        cameraId = try { getCameraIdForFacing(Facing.BACK) } catch (_: Exception) { "0" }
    }

    fun prepareCamera(surfaceTexture: SurfaceTexture, width: Int, height: Int, fps: Int) {
        val optimalResolution = requiredSize ?: getOptimalResolution(Size(width, height), getCameraResolutions(facing))
        Log.i(TAG, "optimal resolution set to: " + optimalResolution.width + "x" + optimalResolution.height)
        if (!isRunning) this.surfaceEncoder?.release()
        captureSize = optimalResolution
        surfaceTexture.setDefaultBufferSize(optimalResolution.width, optimalResolution.height)
        this.surfaceEncoder = Surface(surfaceTexture)
        this.fps = fps
        isPrepared = true
    }

    fun prepareCamera(surfaceTexture: SurfaceTexture, width: Int, height: Int, fps: Int, facing: Facing) {
        this.facing = facing
        prepareCamera(surfaceTexture, width, height, fps)
    }

    fun prepareCamera(surfaceTexture: SurfaceTexture, width: Int, height: Int, fps: Int, cameraId: String) {
        this.cameraId = cameraId
        this.facing = getFacingByCameraId(cameraManager, cameraId)
        prepareCamera(surfaceTexture, width, height, fps)
    }

    fun prepareCamera(surface: Surface, fps: Int) {
        captureSize = null
        this.surfaceEncoder = surface
        this.fps = fps
        isPrepared = true
    }

    /** Re-open on the same surface: its buffer size is unchanged, so tap-to-focus keeps working. */
    private fun prepareSameSurface(surface: Surface) {
        val size = captureSize
        prepareCamera(surface, fps)
        captureSize = size
    }

    private fun startPreview(cameraDevice: CameraDevice, handler: Handler) {
        try {
            val surface = surfaceEncoder ?: run {
                cameraCallbacks?.onCameraError("You need prepare camera before open it")
                return
            }
            val listSurfaces = mutableListOf<Surface>()
            listSurfaces.add(surface)
            imageReader?.let { listSurfaces.add(it.surface) }
            val captureRequest = drawSurface(cameraDevice, listSurfaces)
            createCaptureSession(
                cameraDevice,
                listSurfaces,
                onConfigured = {
                    cameraCaptureSession = it
                    try {
                        it.setRepeatingRequest(
                            captureRequest,
                            if (needsCaptureCallback()) cb else null,
                            handler
                        )
                    } catch (_: IllegalStateException) {
                        reOpenCamera(cameraId)
                    } catch (e: Exception) {
                        cameraCallbacks?.onCameraError("Create capture session failed: " + e.message)
                        Log.e(TAG, "Error", e)
                    }
                },
                onConfiguredFailed = {
                    it.close()
                    cameraCallbacks?.onCameraError("Configuration failed")
                    Log.e(TAG, "Configuration failed")
                },
                handler
            )
        } catch (_: IllegalStateException) {
            reOpenCamera(cameraId)
        } catch (e: Exception) {
            cameraCallbacks?.onCameraError("Create capture session failed: " + e.message)
            Log.e(TAG, "Error", e)
        }
    }



    @Throws(IllegalStateException::class, Exception::class)
    private fun drawSurface(cameraDevice: CameraDevice, surfaces: List<Surface>): CaptureRequest {
        val builderInputSurface = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        for (surface in surfaces) builderInputSurface.addTarget(surface)
        builderInputSurface.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (dynamicFps) adaptFpsRangeDynamic(fps, builderInputSurface) else adaptFpsRange(fps, builderInputSurface)
        this.builderInputSurface = builderInputSurface
        return builderInputSurface.build()
    }

    private fun adaptFpsRange(expectedFps: Int, builderInputSurface: CaptureRequest.Builder) {
        val fpsRanges = getSupportedFps(null, facing)
        if (fpsRanges.isNotEmpty()) {
            var closestRange = fpsRanges[0]
            var measure = (abs((closestRange.lower - expectedFps).toDouble()) + abs(
                (closestRange.upper - expectedFps).toDouble()
            )).toInt()
            for (range in fpsRanges) {
                if (CameraHelper.discardCamera2Fps(range, facing)) continue
                if (range.lower <= expectedFps && range.upper >= expectedFps) {
                    val curMeasure = abs((((range.lower + range.upper) / 2) - expectedFps).toDouble()).toInt()
                    if (curMeasure < measure) {
                        closestRange = range
                        measure = curMeasure
                    } else if (curMeasure == measure) {
                        if (abs((range.upper - expectedFps).toDouble()) < abs((closestRange.upper - expectedFps).toDouble())) {
                            closestRange = range
                            measure = curMeasure
                        }
                    }
                }
            }
            Log.i(TAG, "fps: " + closestRange.lower + " - " + closestRange.upper)
            builderInputSurface.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, closestRange)
        }
    }

    private fun adaptFpsRangeDynamic(fps: Int, builderInputSurface: CaptureRequest.Builder) {
        val validFps = min(60, fps)
        // Find best FPS range instead of forcing strict [30, 30] which causes HAL duplication stutter
        var bestRange = Range(validFps, validFps)
        try {
            val facing = if (cameraId == "1") Facing.FRONT else Facing.BACK
            val supportedRanges = getSupportedFps(null, facing)

            // Look for a range that maxes out at our target FPS, but allows dipping to save light (e.g. [24, 30] or [15, 30])
            for (range in supportedRanges) {
                if (range.upper == validFps && range.lower < validFps) {
                    // Try to avoid dropping too low (e.g. [15, 30] can be too choppy, prefer [24, 30])
                    if (range.lower >= 24) {
                        bestRange = range
                        break
                    } else if (bestRange.lower == validFps || range.lower > bestRange.lower) {
                        bestRange = range
                    }
                }
            }
            Log.i(TAG, "Selected dynamic FPS range: $bestRange for target $validFps")
        } catch (e: Exception) {
            Log.e(TAG, "Error finding dynamic FPS range", e)
        }
        builderInputSurface.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestRange)
    }

    fun setCustomRequest(request: (CaptureRequest.Builder) -> Unit): Boolean {
        val builderInputSurface = this.builderInputSurface ?: return false
        request(builderInputSurface)
        return applyRequest(builderInputSurface)
    }

    fun setCustomOnCaptureCompletedCallback(
        callback: ((CameraCaptureSession, CaptureRequest, TotalCaptureResult) -> Unit)?
    ) {
        this.customCaptureCompletedCallback = callback
    }

    fun getSupportedFps(size: Size?, facing: Facing): List<Range<Int>> {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(getCameraIdForFacing(facing))
            val fpsSupported = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return emptyList()
            return if (size != null) {
                val streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val list = mutableListOf<Range<Int>>()
                val fd = streamConfigurationMap?.getOutputMinFrameDuration(SurfaceTexture::class.java, size) ?: return emptyList()
                val maxFPS = (1e9 / fd).toInt()
                for (r in fpsSupported) { if (r.upper <= maxFPS) { list.add(r) } }
                list
            } else listOf(*fpsSupported)
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            return emptyList()
        }
    }

    val levelSupported: Int
        get() {
            val characteristics = cameraCharacteristics ?: return -1
            return characteristics.secureGet(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
        }

    fun setRequiredResolution(size: Size?) {
        requiredSize = size
    }

    fun openCameraBack() {
        openCameraFacing(Facing.BACK)
    }

    fun openCameraFront() {
        openCameraFacing(Facing.FRONT)
    }

    fun openLastCamera() {
        openCameraId(cameraId)
    }

    fun setCameraId(cameraId: String) {
        this.cameraId = cameraId
    }

    fun setAvailabilityCallback(callback: CameraManager.AvailabilityCallback?) {
        if (callback == null) {
            availabilityCallback?.let { cameraManager.unregisterAvailabilityCallback(it) }
        } else {
            cameraManager.registerAvailabilityCallback(callback, null)
            availabilityCallback = callback
        }
    }

    var cameraFacing: Facing
        get() = facing
        set(cameraFacing) {
            try {
                val cameraId = getCameraIdForFacing(cameraFacing)
                facing = cameraFacing
                this.cameraId = cameraId
            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
            }
        }

    val cameraResolutionsBack: Array<Size>
        get() = getCameraResolutions(Facing.BACK)

    val cameraResolutionsFront: Array<Size>
        get() = getCameraResolutions(Facing.FRONT)

    fun getCameraResolutions(facing: Facing): Array<Size> = getCameraResolutions(getCameraIdForFacing(facing))

    fun getCameraResolutions(cameraId: String): Array<Size> {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfigurationMap = characteristics.secureGet(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return arrayOf()
            val outputSizes = streamConfigurationMap.getOutputSizes(SurfaceTexture::class.java)
            return outputSizes ?: arrayOf()
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            return arrayOf()
        }
    }

    val cameraCharacteristics: CameraCharacteristics?
        get() {
            try {
                return cameraManager.getCameraCharacteristics(cameraId)
            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
                return null
            }
        }

    @RequiresApi(Build.VERSION_CODES.P)
    fun getPhysicalCamerasAvailable(): List<String> {
        return cameraCharacteristics?.physicalCameraIds?.toList() ?: emptyList()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun setPhysicalCamera(id: String?) {
        physicalCameraId = id
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun openPhysicalCamera(id: String?) {
        setPhysicalCamera(id)
        if (isRunning) reOpenCamera(cameraId)
        else openCameraId(cameraId)
    }

    private fun applyRequest(builder: CaptureRequest.Builder): Boolean {
        this.builderInputSurface = builder
        val cameraCaptureSession = this.cameraCaptureSession ?: return false
        try {
            cameraCaptureSession.setRepeatingRequest(
                builder.build(),
                if (needsCaptureCallback()) cb else null, null
            )
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            return false
        }
    }

    private fun clearRegions(): Array<MeteringRectangle>? {
        val sensor = cameraCharacteristics?.secureGet(
            CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        return arrayOf(MeteringRectangle(0, 0, sensor.width(), sensor.height(),
            MeteringRectangle.METERING_WEIGHT_DONT_CARE))
    }

    /**
     * @param mode value from CameraCharacteristics.CONTROL_AWB_MODE_*
     */
    fun enableAutoWhiteBalance(mode: Int): Boolean {
        val characteristics = cameraCharacteristics ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        val modes = characteristics.secureGet(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: return false
        if (!modes.contains(mode)) return false
        val maxRegionsAwb = characteristics.secureGet(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) ?: 0
        if (maxRegionsAwb > 0) {
            clearRegions()?.let { builderInputSurface.set(CaptureRequest.CONTROL_AWB_REGIONS, it) }
        }
        builderInputSurface.set(CaptureRequest.CONTROL_AWB_MODE, mode)
        isAutoWhiteBalanceEnabled = applyRequest(builderInputSurface)
        return isAutoWhiteBalanceEnabled
    }

    fun disableAutoWhiteBalance() {
        val characteristics = cameraCharacteristics ?: return
        val builderInputSurface = this.builderInputSurface ?: return
        val modes = characteristics.secureGet(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: return
        if (!modes.contains(CaptureRequest.CONTROL_AWB_MODE_OFF)) return
        builderInputSurface.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        applyRequest(builderInputSurface)
        isAutoWhiteBalanceEnabled = false
    }

    fun getAutoWhiteBalanceModesAvailable(): List<Int> {
        val characteristics = cameraCharacteristics ?: return listOf()
        return characteristics.secureGet(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: listOf()
    }

    fun getWhiteBalance(): Int {
        val default = CameraCharacteristics.CONTROL_AWB_MODE_AUTO
        val builderInputSurface = this.builderInputSurface ?: return default
        return builderInputSurface.secureGet(CaptureRequest.CONTROL_AWB_MODE) ?: default
    }

    fun setColorCorrectionGains(red: Float, greenEven: Float, greenOdd: Float, blue: Float): Boolean {
        val characteristics = cameraCharacteristics ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        val modes = characteristics.secureGet(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES) ?: return false
        if (!modes.contains(CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)) return false
        builderInputSurface.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        builderInputSurface.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        builderInputSurface.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1))
        val vector = RggbChannelVector(red, greenEven, greenOdd, blue)
        builderInputSurface.set(CaptureRequest.COLOR_CORRECTION_GAINS, vector)
        return applyRequest(builderInputSurface)
    }

    fun enableAutoExposure(): Boolean {
        val characteristics = cameraCharacteristics ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        val modes = characteristics.secureGet(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: return false
        if (!modes.contains(CaptureRequest.CONTROL_AE_MODE_ON)) return false
        val maxRegionsAe = characteristics.secureGet(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        if (maxRegionsAe > 0) {
            clearRegions()?.let { builderInputSurface.set(CaptureRequest.CONTROL_AE_REGIONS, it) }
        }
        builderInputSurface.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        isAutoExposureEnabled = applyRequest(builderInputSurface)
        return isAutoExposureEnabled
    }

    fun disableAutoExposure() {
        val characteristics = cameraCharacteristics ?: return
        val builderInputSurface = this.builderInputSurface ?: return
        val modes = characteristics.secureGet(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: return
        if (!modes.contains(CaptureRequest.CONTROL_AE_MODE_ON)) return
        builderInputSurface.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        applyRequest(builderInputSurface)
        isAutoExposureEnabled = false
    }

    /**
     * Lock auto exposure to the current value. The camera will stop adjusting exposure
     * automatically (useful to avoid exposure changes produced by faces or lighting changes).
     * @return true if success, false if fail (not supported or called before start camera)
     */
    fun enableExposureLock(): Boolean {
        val characteristics = cameraCharacteristics ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val available = characteristics.secureGet(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: return false
            if (!available) return false
        }
        builderInputSurface.set(CaptureRequest.CONTROL_AE_LOCK, true)
        isExposureLockEnabled = applyRequest(builderInputSurface)
        return isExposureLockEnabled
    }

    fun disableExposureLock() {
        val builderInputSurface = this.builderInputSurface ?: return
        builderInputSurface.set(CaptureRequest.CONTROL_AE_LOCK, false)
        applyRequest(builderInputSurface)
        isExposureLockEnabled = false
    }

    /**
     * Lock auto white balance to the current value. The camera will stop adjusting white balance
     * automatically (useful to avoid color shifts from lighting changes).
     * @return true if success, false if fail (not supported or called before start camera)
     */
    fun enableWhiteBalanceLock(): Boolean {
        val characteristics = cameraCharacteristics ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val available = characteristics.secureGet(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: return false
            if (!available) return false
        }
        builderInputSurface.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        isWhiteBalanceLockEnabled = applyRequest(builderInputSurface)
        return isWhiteBalanceLockEnabled
    }

    fun disableWhiteBalanceLock() {
        val builderInputSurface = this.builderInputSurface ?: return
        builderInputSurface.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        applyRequest(builderInputSurface)
        isWhiteBalanceLockEnabled = false
    }

    fun enableVideoStabilization(): Boolean {
        val characteristics = cameraCharacteristics ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        val modes = characteristics.secureGet(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: return false
        if (!modes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) return false
        builderInputSurface.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        isVideoStabilizationEnabled = applyRequest(builderInputSurface)
        return isVideoStabilizationEnabled
    }

    fun disableVideoStabilization() {
        val characteristics = cameraCharacteristics ?: return
        val builderInputSurface = this.builderInputSurface ?: return
        val modes = characteristics.secureGet(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: return
        if (!modes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) return
        builderInputSurface.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        applyRequest(builderInputSurface)
        isVideoStabilizationEnabled = false
    }

    fun enableOpticalVideoStabilization(): Boolean {
        val characteristics = cameraCharacteristics ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        val opticalStabilizationModes = characteristics.secureGet(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: return false
        if (!opticalStabilizationModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) return false
        builderInputSurface.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        isOpticalStabilizationEnabled = applyRequest(builderInputSurface)
        return isOpticalStabilizationEnabled
    }

    fun disableOpticalVideoStabilization() {
        val characteristics = cameraCharacteristics ?: return
        val builderInputSurface = this.builderInputSurface ?: return
        val modes = characteristics.secureGet(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: return
        if (!modes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) return
        builderInputSurface.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        applyRequest(builderInputSurface)
        isOpticalStabilizationEnabled = false
    }

    fun setFocusDistance(distance: Float) {
        val builderInputSurface = this.builderInputSurface ?: return
        builderInputSurface.set(CaptureRequest.LENS_FOCUS_DISTANCE, max(0f, distance))
        applyRequest(builderInputSurface)
    }

    fun getCurrentCameraId()  = cameraId

    var exposure: Int
        get() {
            val builderInputSurface = this.builderInputSurface ?: return 0
            return builderInputSurface.secureGet(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) ?: 0
        }
        set(value) {
            val characteristics = cameraCharacteristics ?: return
            val builderInputSurface = this.builderInputSurface ?: return
            val supportedExposure = characteristics.secureGet(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return
            val v = value.coerceIn(supportedExposure.lower, supportedExposure.upper)
            builderInputSurface.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, v)
            applyRequest(builderInputSurface)
        }


    val maxExposure: Int
        get() {
            val characteristics = cameraCharacteristics ?: return 0
            val supportedExposure = characteristics.secureGet(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.upper ?: 0
            return supportedExposure
        }

    val minExposure: Int
        get() {
            val characteristics = cameraCharacteristics ?: return 0
            val supportedExposure = characteristics.secureGet(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.lower ?: 0
            return supportedExposure
        }

    private fun getTapRect(view: View, event: MotionEvent): MeteringRectangle? {
        synchronized(zoomLock) {
            val info = getZoomInfoLocked() ?: return null
            val builder = builderInputSurface ?: return null
            val point = tapPoint(view, event, info) ?: return null
            return meteringRegion(builder, info, point)
        }
    }

    /**
     * Tapped point in sensor orientation, normalized. Null outside the image (black bars).
     * Assumes the preview shows the camera output aspect-fit (AspectRatioMode.Adjust, encoder with
     * the same aspect ratio as the camera).
     */
    private fun tapPoint(view: View, event: MotionEvent, info: ZoomInfo): Pair<Float, Float>? {
        val size = captureSize ?: return null
        val displayDegrees = when (view.display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val rotation = FocusMeteringMapper.relativeRotation(info.sensorOrientation, displayDegrees, info.isFront)
        return FocusMeteringMapper.sensorPoint(event.x, event.y, view.width, view.height,
            size.width, size.height, rotation, info.isFront)
    }

    private fun meteringRegion(builder: CaptureRequest.Builder, info: ZoomInfo, point: Pair<Float, Float>): MeteringRectangle? {
        val size = captureSize ?: return null
        val array = if (info.distortionCorrectionSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            builder.get(CaptureRequest.DISTORTION_CORRECTION_MODE) == CaptureRequest.DISTORTION_CORRECTION_MODE_OFF) {
            info.preCorrectionArray ?: info.activeArray
        } else info.activeArray
        array ?: return null
        // region and crop coordinates start at the top-left corner of the array, not at its offset
        val active = Rect(0, 0, array.width(), array.height())
        // zoomRatio: regions are relative to the zoomed field of view, the crop region stays the
        // active array. Legacy zoom: the crop region is the zoom, regions must lie inside it.
        val crop = Rect(builder.get(CaptureRequest.SCALER_CROP_REGION) ?: active)
        if (!crop.intersect(active)) return null
        val bounds = FocusMeteringMapper.region(point.first, point.second,
            crop.left, crop.top, crop.right, crop.bottom, size.width, size.height)
        return MeteringRectangle(bounds[0], bounds[1], bounds[2], bounds[3],
            MeteringRectangle.METERING_WEIGHT_MAX)
    }

    /** Puts the AF/AE regions of the tapped point into the builder. */
    private fun applyFocusPointLocked(builder: CaptureRequest.Builder, info: ZoomInfo): Boolean {
        val point = focusPoint ?: return true
        val region = meteringRegion(builder, info, point) ?: return false
        if (info.maxRegionsAf > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        if (info.maxRegionsAe > 0 && !isExposureLockEnabled) builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        return true
    }

    /**
     * Legacy zoom moves the crop region, so the regions of the tapped point must move with it.
     * On the zoomRatio path they are relative to the zoomed view and stay valid.
     */
    private fun followZoomWithFocusPointLocked(builder: CaptureRequest.Builder, info: ZoomInfo) {
        if (!info.useZoomRatio) applyFocusPointLocked(builder, info)
    }

    /**
     * Focus and exposure on the tapped point, like a focus and exposure point of interest on iOS.
     * AF scans the point once (AUTO + trigger, a region change alone doesn't make continuous AF
     * rescan) and as soon as the camera reports the lens locked (FOCUS_SCAN_HOLD_MS at the latest)
     * goes back to continuous AF that keeps the point; FocusListener gets the result. AE stays
     * continuous, metered on the point. The point stays (also through zoom changes) until the camera
     * closes or enableAutoFocus() is called. Safe while animateZoom() runs.
     * Returns false when the camera can't meter the point (no session, tap outside the image...).
     */
    fun tapToFocus(view: View, event: MotionEvent): Boolean {
        synchronized(zoomLock) {
            val builder = builderInputSurface ?: return false
            val session = cameraCaptureSession ?: return false
            val info = getZoomInfoLocked() ?: return false
            val canFocus = info.maxRegionsAf > 0 && (info.hasAutoFocusScan || info.continuousFocusMode != null)
            if (!canFocus && (info.maxRegionsAe == 0 || isExposureLockEnabled)) return false
            val point = tapPoint(view, event, info) ?: return false
            val previous = focusPoint
            focusPoint = point
            if (!applyFocusPointLocked(builder, info)) {
                focusPoint = previous
                return false
            }
            cancelFocusHoldLocked()
            focusScanId++
            builder.setTag("")
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            val sent = if (canFocus && info.hasAutoFocusScan) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                val continuousMode = info.continuousFocusMode
                // before the repeating request, so its results come with the AF state
                focusScanContinuousMode = continuousMode
                focusScanActive = true
                // Repeating AUTO first, then the trigger (CameraX order): a frame from the old
                // continuous request can't land right after the trigger and reset the scan.
                val scanning = sendRepeatingLocked(builder) && triggerAutoFocusScanLocked(builder, session)
                if (!scanning) cancelFocusScanLocked()
                if (continuousMode == null) {
                    scanning
                } else if (scanning) {
                    holdFocusLocked()
                    true
                } else {
                    // No scan started: don't leave the lens locked in AUTO.
                    builder.set(CaptureRequest.CONTROL_AF_MODE, continuousMode)
                    sendRepeatingLocked(builder)
                }
            } else {
                info.continuousFocusMode?.let { if (canFocus) builder.set(CaptureRequest.CONTROL_AF_MODE, it) }
                sendRepeatingLocked(builder)
            }
            if (sent && canFocus) isAutoFocusEnabled = true
            return sent
        }
    }

    /**
     * One capture with AF_TRIGGER_START, queued after any zoom ramp frames (same zoom as the last
     * of them). The repeating request keeps AUTO with an idle trigger, so the lens locks.
     */
    private fun triggerAutoFocusScanLocked(builder: CaptureRequest.Builder, session: CameraCaptureSession): Boolean {
        return try {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            val request = builder.build()
            focusTriggerRequest = request
            focusTriggerFrame = -1L
            session.capture(request, focusTriggerCallback, cameraThreadHandler)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            false
        } finally {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        }
    }

    /**
     * Fallback for a camera that never reports the end of the scan: after FOCUS_SCAN_HOLD_MS back to
     * continuous AF anyway. Normally onFocusScanResult() does it as soon as the lens locks.
     */
    private fun holdFocusLocked() {
        val handler = cameraThreadHandler ?: return
        val runnable = object : Runnable {
            override fun run() {
                synchronized(zoomLock) {
                    if (focusHoldRunnable !== this) return
                    resumeContinuousFocusLocked()
                }
            }
        }
        focusHoldRunnable = runnable
        handler.postDelayed(runnable, FOCUS_SCAN_HOLD_MS)
    }

    /** After the scan, back to continuous AF on the same point (the mode change starts a new passive scan). */
    private fun resumeContinuousFocusLocked() {
        val continuousMode = focusScanContinuousMode
        cancelFocusHoldLocked()
        val builder = builderInputSurface ?: return
        if (continuousMode == null) return
        builder.set(CaptureRequest.CONTROL_AF_MODE, continuousMode)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        sendRepeatingLocked(builder)
    }

    private fun cancelFocusHoldLocked() {
        focusHoldRunnable?.let { cameraThreadHandler?.removeCallbacks(it) }
        focusHoldRunnable = null
        cancelFocusScanLocked()
    }

    private fun cancelFocusScanLocked() {
        focusScanActive = false
        focusTriggerRequest = null
        focusTriggerFrame = -1L
        focusScanContinuousMode = null
    }

    /** Frame number of the AF_TRIGGER_START capture: the scan's state counts only after it. */
    private val focusTriggerCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            synchronized(zoomLock) {
                if (request === focusTriggerRequest) focusTriggerFrame = result.frameNumber
            }
        }
    }

    /** As soon as the camera reports the scan finished: continuous AF on the point, then the listener. */
    private fun onFocusScanResult(result: CaptureResult) {
        if (!focusScanActive) return
        var finishedScanId = 0
        var success = false
        var finished = false
        synchronized(zoomLock) {
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            if (focusScanActive && FocusMeteringMapper.isScanFinished(afState, result.frameNumber, focusTriggerFrame)) {
                finished = true
                finishedScanId = focusScanId
                success = afState == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED
                // AF without a continuous mode stays locked in AUTO, only the hold ends
                resumeContinuousFocusLocked()
            }
        }
        if (finished) focusListener?.onFocusFinished(finishedScanId, success)
    }

    fun setFocusListener(listener: FocusListener?) {
        focusListener = listener
    }

    /** Id of the scan started by the last tapToFocus call, passed back by FocusListener. */
    fun getFocusScanId(): Int {
        synchronized(zoomLock) {
            return focusScanId
        }
    }

    /** enableAutoFocus() resets the AF regions: the tapped point and its AE region go with them. */
    private fun clearFocusPoint(builder: CaptureRequest.Builder) {
        synchronized(zoomLock) {
            cancelFocusHoldLocked()
            if (focusPoint == null) return
            focusPoint = null
            val info = getZoomInfoLocked() ?: return
            if (info.maxRegionsAe > 0) {
                clearRegions()?.let { builder.set(CaptureRequest.CONTROL_AE_REGIONS, it) }
            }
        }
    }

    /**
     * Tap to meter exposure at a specific point.
     *
     * @param view The view where the touch event occurred
     * @param event The touch event containing coordinates
     * @return true if successful, false otherwise
     */
    fun tapToMeterExposure(view: View, event: MotionEvent): Boolean {
        if (isExposureLockEnabled) return false
        val builderInputSurface = this.builderInputSurface ?: return false
        val characteristics = cameraCharacteristics ?: return false
        if ((characteristics.secureGet(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) == 0) return false
        val aeRect = getTapRect(view, event) ?: return false

        return try {
            builderInputSurface.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(aeRect))
            builderInputSurface.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            isAutoExposureEnabled = applyRequest(builderInputSurface)
            isAutoExposureEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Error setting AE region", e)
            false
        }
    }

    /**
     * Tap to meter white balance at a specific point.
     *
     * @param view The view where the touch event occurred
     * @param event The touch event containing coordinates
     * @return true if successful, false otherwise
     */
    fun tapToMeterWhiteBalance(view: View, event: MotionEvent): Boolean {
        if (isWhiteBalanceLockEnabled) return false
        val builderInputSurface = this.builderInputSurface ?: return false
        val characteristics = cameraCharacteristics ?: return false
        if ((characteristics.secureGet(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) ?: 0) == 0) return false
        val awbRect = getTapRect(view, event) ?: return false

        return try {
            builderInputSurface.set(CaptureRequest.CONTROL_AWB_REGIONS, arrayOf(awbRect))
            builderInputSurface.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            isAutoWhiteBalanceEnabled = applyRequest(builderInputSurface)
            isAutoWhiteBalanceEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Error setting AWB region", e)
            false
        }
    }

    /**
     * Select camera facing
     *
     * @param selectedCameraFacing - CameraCharacteristics.LENS_FACING_FRONT,
     * CameraCharacteristics.LENS_FACING_BACK,
     * CameraCharacteristics.LENS_FACING_EXTERNAL
     */
    fun openCameraFacing(selectedCameraFacing: Facing) {
        try {
            val cameraId = getCameraIdForFacing(selectedCameraFacing)
            openCameraId(cameraId)
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
        }
    }

    val isLanternSupported: Boolean
        get() {
            val characteristics = cameraCharacteristics ?: return false
            val available = characteristics.secureGet(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: return false
            return available
        }

    /**
     * @required: <uses-permission android:name="android.permission.FLASHLIGHT"></uses-permission>
     */
    @Throws(Exception::class)
    fun enableLantern() {
        val builderInputSurface = this.builderInputSurface ?: return
        if (isLanternSupported) {
            try {
                builderInputSurface.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                isLanternEnabled = applyRequest(builderInputSurface)
            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
            }
        } else {
            Log.e(TAG, "Lantern unsupported")
            throw Exception("Lantern unsupported")
        }
    }

    /**
     * @required: <uses-permission android:name="android.permission.FLASHLIGHT"></uses-permission>
     */
    fun disableLantern() {
        val characteristics = cameraCharacteristics ?: return
        val builderInputSurface = this.builderInputSurface ?: return
        val available = characteristics.secureGet(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: return
        if (available) {
            try {
                builderInputSurface.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                applyRequest(builderInputSurface)
                isLanternEnabled = false
            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
            }
        }
    }

    fun enableAutoFocus(): Boolean {
        var result = false
        val characteristics = cameraCharacteristics ?: return false
        val supportedFocusModes = characteristics.secureGet(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        try {
            if (supportedFocusModes.isNotEmpty()) {
                //cancel any existing AF trigger and clear the regions set by tapToFocus.
                builderInputSurface.setTag("")
                clearFocusPoint(builderInputSurface)
                val maxRegionsAf = characteristics.secureGet(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
                if (maxRegionsAf > 0) {
                    clearRegions()?.let { builderInputSurface.set(CaptureRequest.CONTROL_AF_REGIONS, it) }
                }
                builderInputSurface.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                builderInputSurface.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                runCatching {
                    cameraCaptureSession?.capture(builderInputSurface.build(), null, null)
                }.onFailure { Log.e(TAG, "Error", it) }
                // Prefer CONTINUOUS_VIDEO: same smooth AF as CONTINUOUS_PICTURE but defers
                // fine adjustments to not interrupt frame delivery (no dropped frames on focus hunt).
                if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                    builderInputSurface.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    isAutoFocusEnabled = true
                } else if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                    builderInputSurface.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    isAutoFocusEnabled = true
                } else if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                    builderInputSurface.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    isAutoFocusEnabled = true
                } else {
                    builderInputSurface.set(CaptureRequest.CONTROL_AF_MODE, supportedFocusModes[0])
                    isAutoFocusEnabled = false
                }
                builderInputSurface.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                applyRequest(builderInputSurface)
            }
            result = isAutoFocusEnabled
        } catch (e: Exception) {
            isAutoFocusEnabled = false
            Log.e(TAG, "Error", e)
        }
        return result
    }

    fun disableAutoFocus(): Boolean {
        val characteristics = cameraCharacteristics ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        val supportedFocusModes = characteristics.secureGet(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return false
        for (mode in supportedFocusModes) {
            runCatching {
                if (mode == CaptureRequest.CONTROL_AF_MODE_OFF) {
                    // a pending tap hold would switch manual focus back to continuous AF
                    clearFocusPoint(builderInputSurface)
                    builderInputSurface.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    applyRequest(builderInputSurface)
                    isAutoFocusEnabled = false
                    return true
                }
            }.onFailure { Log.e(TAG, "Error", it) }
        }
        return false
    }

    fun enableFaceDetection(faceDetectorCallback: FaceDetectorCallback?): Boolean {
        val characteristics = cameraCharacteristics ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        faceSensorScale = characteristics.secureGet(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        sensorOrientation = characteristics.secureGet(CameraCharacteristics.SENSOR_ORIENTATION) ?: return false
        val fd = characteristics.secureGet(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: return false
        val maxFD = characteristics.secureGet(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: return false
        if (fd.isEmpty() || maxFD <= 0) return false
        this.faceDetectorCallback = faceDetectorCallback
        faceDetectionEnabled = true
        faceDetectionMode = fd.toList().max()
        builderInputSurface.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectionMode)
        applyRequest(builderInputSurface)
        return true
    }


    fun enableFrameCaptureCallback(frameCapturedCallback: FrameCapturedCallback?) {
        this.frameCapturedCallback = frameCapturedCallback
    }

    fun disableFaceDetection() {
        if (faceDetectionEnabled) {
            faceDetectorCallback = null
            faceDetectionEnabled = false
            faceDetectionMode = 0
            builderInputSurface?.let {
                it.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
                applyRequest(it)
            }
        }
    }

    fun isFaceDetectionEnabled() = faceDetectionEnabled

    fun setCameraCallbacks(cameraCallbacks: CameraCallbacks?) {
        this.cameraCallbacks = cameraCallbacks
    }

    private val cb: CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            onZoomCaptureCompleted(result)
            onFocusScanResult(result)
            customCaptureCompletedCallback?.invoke(session, request, result)

            val faces = result.get(CaptureResult.STATISTICS_FACES) ?: return
            faceDetectorCallback?.onGetFaces(
                faces = mapCamera2Faces(faces = faces),
                scaleSensor = faceSensorScale,
                sensorOrientation = sensorOrientation
            )
        }

        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long
        ) {
            onZoomCaptureStarted(request, timestamp)
            frameCapturedCallback?.onFrameCaptured(
                frameNumber = frameNumber,
                timestamp = timestamp
            )
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            onZoomCaptureFailed(request)
        }
    }

    @SuppressLint("MissingPermission")
    fun openCameraId(cameraId: String) {
        this.cameraId = cameraId
        if (isPrepared) {
            val cameraHandlerThread = HandlerThread("$TAG Id = $cameraId")
            cameraHandlerThread.start()
            val handler = Handler(cameraHandlerThread.looper)
            onZoomCameraOpening(handler)
            try {
                cameraManager.openCamera(cameraId, object: CameraDevice.StateCallback() {
                    override fun onOpened(cameraDevice: CameraDevice) {
                        this@Camera2ApiManager.cameraDevice = cameraDevice
                        startPreview(cameraDevice, handler)
                        semaphore.release()
                        cameraCallbacks?.onCameraOpened()
                        Log.i(TAG, "Camera opened")
                    }

                    override fun onClosed(camera: CameraDevice) {
                        super.onClosed(camera)
                        handler.looper.quitSafely()
                    }

                    override fun onDisconnected(cameraDevice: CameraDevice) {
                        cameraDevice.close()
                        semaphore.release()
                        cameraCallbacks?.onCameraDisconnected()
                        Log.i(TAG, "Camera disconnected")
                    }

                    override fun onError(cameraDevice: CameraDevice, i: Int) {
                        cameraDevice.close()
                        semaphore.release()
                        cameraCallbacks?.onCameraError("Open camera failed: $i")
                        Log.e(TAG, "Open failed: $i")
                    }

                }, handler)
                semaphore.acquireUninterruptibly()
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
                isRunning = true
                val facing = cameraCharacteristics.secureGet(CameraCharacteristics.LENS_FACING) ?: return
                this.facing = if (CameraMetadata.LENS_FACING_FRONT == facing) Facing.FRONT else Facing.BACK
                cameraCallbacks?.onCameraChanged(this.facing)
            } catch (e: Exception) {
                cameraCallbacks?.onCameraError("Open camera $cameraId failed")
                Log.e(TAG, "Error", e)
            }
        } else {
            throw IllegalStateException("You need prepare camera before open it")
        }
    }

    val camerasAvailable: Array<String> = cameraManager.cameraIdList

    fun switchCamera() {
        try {
            val cameraId = if (cameraDevice == null || facing == Facing.FRONT) {
                getCameraIdForFacing(Facing.BACK)
            } else {
                getCameraIdForFacing(Facing.FRONT)
            }
            reOpenCamera(cameraId)
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
        }
    }

    fun reOpenCamera(cameraId: String) {
        val surface = surfaceEncoder
        if (cameraDevice != null && surface != null) {
            closeCamera(false)
            prepareSameSurface(surface)
            openCameraId(cameraId)
        }
    }

    val zoomRange: Range<Float>
        get() {
            val characteristics = cameraCharacteristics ?: return Range(1f, 1f)
            var zoomRanges: Range<Float>? = null
            //only camera limited or better support this feature.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && levelSupported != CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                zoomRanges = characteristics.secureGet(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            }
            if (zoomRanges == null) {
                val maxZoom = characteristics.secureGet(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                zoomRanges = Range(1f, maxZoom)
            }
            return zoomRanges
        }

    var zoom: Float
        get() = zoomLevel
        set(level) {
            val characteristics = cameraCharacteristics ?: return
            val builderInputSurface = this.builderInputSurface ?: return
            val l = level.coerceIn(zoomRange.lower, zoomRange.upper)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && levelSupported != CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                    builderInputSurface.set(CaptureRequest.CONTROL_ZOOM_RATIO, l)
                } else {
                    val rect = characteristics.secureGet(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
                    //This ratio is the ratio of cropped Rect to Camera's original(Maximum) Rect
                    val ratio = 1f / l
                    //croppedWidth and croppedHeight are the pixels cropped away, not pixels after cropped
                    val croppedWidth = rect.width() - (rect.width().toFloat() * ratio).roundToInt()
                    val croppedHeight = rect.height() - (rect.height().toFloat() * ratio).roundToInt()
                    //Finally, zoom represents the zoomed visible area
                    val zoom = Rect(
                        croppedWidth / 2, croppedHeight / 2, rect.width() - croppedWidth / 2,
                        rect.height() - croppedHeight / 2
                    )
                    builderInputSurface.set(CaptureRequest.SCALER_CROP_REGION, zoom)
                }
                applyRequest(builderInputSurface)
                zoomLevel = l
            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
            }
        }

    fun getOpticalZooms(): Array<Float> {
        val characteristics = cameraCharacteristics ?: return arrayOf()
        return characteristics.secureGet(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toTypedArray() ?: arrayOf()
    }

    fun setOpticalZoom(level: Float) {
        val builderInputSurface = this.builderInputSurface ?: return
        try {
            builderInputSurface.set(CaptureRequest.LENS_FOCAL_LENGTH, level)
            applyRequest(builderInputSurface)
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
        }
    }

    @JvmOverloads
    fun setZoom(event: MotionEvent, delta: Float = 0.1f) {
        if (event.pointerCount < 2 || event.action != MotionEvent.ACTION_MOVE) return
        val currentFingerSpacing = CameraHelper.getFingerSpacing(event)
        if (currentFingerSpacing > fingerSpacing) {
            zoom += delta
        } else if (currentFingerSpacing < fingerSpacing) {
            zoom -= delta
        }
        fingerSpacing = currentFingerSpacing
    }

    /*
     * Zoom without IPC, zoom applied by the camera and a zoom ramp stepped by camera frames.
     * New API only: the existing zoom methods (zoom, zoomRange, setZoom) are untouched.
     *
     * Threading: CaptureRequest.Builder is not thread safe. Everything here that touches the builder
     * or the zoom state runs under zoomLock. cb can arrive on the camera thread or on the caller's
     * looper (applyRequest passes a null handler), so nothing here assumes a thread.
     * Old methods (enableAutoFocus...) don't know zoomLock: call stopZoomAnimation() first. tapToFocus uses it.
     */

    /** Range from cache, read once per opened camera. */
    fun getZoomRangeCached(): Range<Float> {
        synchronized(zoomLock) {
            return getZoomInfoLocked()?.range ?: Range(1f, 1f)
        }
    }

    /**
     * Same as the zoom setter, but without IPC (characteristics from cache).
     * Returns true only when the request actually went to the capture session.
     * Stops a running animateZoom(). Ramp frames already queued still play first.
     */
    fun setZoomCached(level: Float): Boolean {
        synchronized(zoomLock) {
            zoomAnimation = null
            return setZoomLevelLocked(level)
        }
    }

    /** Enables reading the applied zoom from capture results (attaches cb). */
    fun setZoomTrackingEnabled(enabled: Boolean) {
        synchronized(zoomLock) {
            if (zoomTrackingEnabled == enabled) return
            zoomTrackingEnabled = enabled
            if (!enabled) {
                zoomAnimation = null
                appliedZoom = 0f
            }
            //re-send the current request so cb is attached / detached right away
            builderInputSurface?.let { sendRepeatingLocked(it) }
        }
    }

    /** Zoom from the last TotalCaptureResult, 0f when not known yet. */
    fun getAppliedZoom(): Float = appliedZoom

    /**
     * Ramps the zoom from the current value to target at rate doublings per second
     * (linear in log2, same as ZoomScale.rampDurationMs in the app).
     * One step per real camera frame, on the camera thread - independent of the main thread.
     * Requires setZoomTrackingEnabled(true). Returns false when it cannot start (no session).
     *
     * Each step is its own single-frame capture: the camera consumes queued captures in order, one per
     * frame, so no step is lost or repeated (replacing the repeating request per step did both, when
     * two capture results arrived within one frame). The step is rate x measured frame duration.
     * The camera takes requests a few frames ahead, so the queue starts at the pipeline depth; a started
     * ramp frame queues the next one. The repeating request always holds the last queued step, so a
     * frame the queue doesn't cover holds the zoom (the queue then grows by one) and the zoom stays on
     * the target after the ramp. A new animateZoom continues after the frames already queued, so the
     * ramp never jumps back.
     */
    fun animateZoom(target: Float, rate: Float): Boolean {
        synchronized(zoomLock) {
            zoomAnimation = null
            if (!zoomTrackingEnabled || zoomLevel <= 0f || rate <= 0f) return false
            val info = getZoomInfoLocked() ?: return false
            val to = target.coerceIn(info.range.lower, info.range.upper)
            if (zoomRampRequests.isEmpty()) zoomRampFrameStarted = false
            zoomRampQueueSize = max(zoomRampQueueSize, info.pipelineMaxDepth)
                .coerceIn(ZOOM_RAMP_QUEUED_FRAMES_MIN, ZOOM_RAMP_QUEUED_FRAMES_MAX)
            zoomAnimation = ZoomAnimation(to, log2(to.toDouble()), rate)
            fillZoomRampQueueLocked()
            val started = zoomRampRequests.isNotEmpty()
            if (!started) zoomAnimation = null
            return started
        }
    }

    /** Stops the ramp. Ramp frames already queued still play, the zoom then stays on the last of them. */
    fun stopZoomAnimation() {
        synchronized(zoomLock) {
            zoomAnimation = null
        }
    }

    fun isZoomAnimating(): Boolean {
        synchronized(zoomLock) {
            return zoomAnimation != null
        }
    }

    private fun needsCaptureCallback(): Boolean {
        return faceDetectionEnabled || frameCapturedCallback != null || customCaptureCompletedCallback != null
            || zoomTrackingEnabled || focusScanActive
    }

    private fun onZoomCameraOpening(handler: Handler) {
        synchronized(zoomLock) {
            cameraThreadHandler = handler
            zoomInfo = null
        }
    }

    private fun onZoomCameraClosing() {
        synchronized(zoomLock) {
            zoomAnimation = null
            zoomInfo = null
            appliedZoom = 0f
            //queued captures die with the session, their callbacks never come
            zoomRampRequests.clear()
            cancelFocusHoldLocked()
            focusPoint = null
            frameDurationNs = 0.0
            lastSensorTimestamp = 0L
        }
    }

    private fun onZoomCaptureCompleted(result: CaptureResult) {
        if (!zoomTrackingEnabled) return
        synchronized(zoomLock) {
            val applied = readAppliedZoom(result, getZoomInfoLocked())
            if (applied > 0f) appliedZoom = applied
        }
    }

    /**
     * Sensor started a frame: if it is a ramp frame, the next one is queued. Done here, not on
     * completion, which comes a few frames later.
     */
    private fun onZoomCaptureStarted(request: CaptureRequest, sensorTimestamp: Long) {
        if (!zoomTrackingEnabled) return
        synchronized(zoomLock) {
            //deltas only, so the sensor time base doesn't matter
            updateFrameDurationLocked(sensorTimestamp)
            //identity: CaptureRequest.equals compares settings, the repeating request can be equal
            val index = zoomRampRequests.indexOfFirst { it === request }
            if (index >= 0) {
                zoomRampRequests.removeAt(index)
                zoomRampFrameStarted = true
            }
            else if (zoomAnimation != null && zoomRampFrameStarted && zoomRampQueueSize < ZOOM_RAMP_QUEUED_FRAMES_MAX) {
                //the queue ran dry, this frame repeated the last ramp frame
                zoomRampQueueSize++
            }
            fillZoomRampQueueLocked()
        }
    }

    private fun onZoomCaptureFailed(request: CaptureRequest) {
        if (!zoomTrackingEnabled) return
        synchronized(zoomLock) {
            val index = zoomRampRequests.indexOfFirst { it === request }
            if (index >= 0) {
                zoomRampRequests.removeAt(index)
                fillZoomRampQueueLocked()
            }
        }
    }

    private fun updateFrameDurationLocked(sensorTimestamp: Long) {
        val delta = (sensorTimestamp - lastSensorTimestamp).toDouble()
        if (lastSensorTimestamp > 0L && delta > 0.0) {
            frameDurationNs = if (frameDurationNs <= 0.0) {
                delta
            } else {
                val bounded = min(delta, frameDurationNs * FRAME_DURATION_MAX_GROWTH)
                frameDurationNs + (bounded - frameDurationNs) * FRAME_DURATION_SMOOTHING
            }
        }
        lastSensorTimestamp = sensorTimestamp
    }

    private fun fillZoomRampQueueLocked() {
        while (zoomAnimation != null && zoomRampRequests.size < zoomRampQueueSize) {
            if (!queueZoomRampFrameLocked()) zoomAnimation = null
        }
    }

    /**
     * Queues the next ramp frame one step after the last queued one (zoomLevel), and makes it the
     * repeating request too: the camera falls back to the repeating request only once all queued
     * captures are taken. Ends the animation on the exact target. Returns false when it can't be sent.
     */
    private fun queueZoomRampFrameLocked(): Boolean {
        val animation = zoomAnimation ?: return false
        val builderInputSurface = this.builderInputSurface ?: return false
        val session = this.cameraCaptureSession ?: return false
        val info = getZoomInfoLocked() ?: return false
        val durationNs = if (frameDurationNs > 0.0) frameDurationNs else NANOS_PER_SECOND / max(1, fps)
        val stepLog = animation.rate * durationNs / NANOS_PER_SECOND
        val currentLog = log2(zoomLevel.toDouble())
        val nextLog = if (animation.toLog > currentLog) {
            min(currentLog + stepLog, animation.toLog)
        } else {
            max(currentLog - stepLog, animation.toLog)
        }
        val finished = nextLog == animation.toLog
        val level = if (finished) animation.target else 2.0.pow(nextLog).toFloat().coerceIn(info.range.lower, info.range.upper)
        if (!applyZoomToBuilder(builderInputSurface, info, level)) return false
        followZoomWithFocusPointLocked(builderInputSurface, info)
        return try {
            val frameRequest = builderInputSurface.build()
            session.capture(frameRequest, cb, cameraThreadHandler)
            zoomRampRequests.add(frameRequest)
            zoomLevel = level
            if (finished) zoomAnimation = null
            sendRepeatingLocked(builderInputSurface)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            false
        }
    }


    /** zoomLevel changes only when the request really went to the capture session. */
    private fun setZoomLevelLocked(level: Float): Boolean {
        val builderInputSurface = this.builderInputSurface ?: return false
        val info = getZoomInfoLocked() ?: return false
        val l = level.coerceIn(info.range.lower, info.range.upper)
        if (!applyZoomToBuilder(builderInputSurface, info, l)) return false
        followZoomWithFocusPointLocked(builderInputSurface, info)
        val sent = sendRepeatingLocked(builderInputSurface)
        if (sent) zoomLevel = l
        return sent
    }

    /** Same computation as the zoom setter, with characteristics from cache. */
    private fun applyZoomToBuilder(builder: CaptureRequest.Builder, info: ZoomInfo, level: Float): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && info.useZoomRatio) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, level)
            return true
        }
        val rect = info.activeArray ?: return false
        //This ratio is the ratio of cropped Rect to Camera's original(Maximum) Rect
        val ratio = 1f / level
        //croppedWidth and croppedHeight are the pixels cropped away, not pixels after cropped
        val croppedWidth = rect.width() - (rect.width().toFloat() * ratio).roundToInt()
        val croppedHeight = rect.height() - (rect.height().toFloat() * ratio).roundToInt()
        //Finally, zoom represents the zoomed visible area
        val zoom = Rect(
            croppedWidth / 2, croppedHeight / 2, rect.width() - croppedWidth / 2,
            rect.height() - croppedHeight / 2
        )
        builder.set(CaptureRequest.SCALER_CROP_REGION, zoom)
        return true
    }

    private fun sendRepeatingLocked(builder: CaptureRequest.Builder): Boolean {
        val cameraCaptureSession = this.cameraCaptureSession ?: return false
        return try {
            cameraCaptureSession.setRepeatingRequest(
                builder.build(),
                if (needsCaptureCallback()) cb else null,
                cameraThreadHandler
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            false
        }
    }

    /**
     * Zoom from the capture result, read the same way it was set: CONTROL_ZOOM_RATIO on the
     * zoom ratio path (legacy HALs report a fixed 1.0 there), otherwise active array / crop region.
     */
    private fun readAppliedZoom(result: CaptureResult, info: ZoomInfo?): Float {
        if (info == null) return 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && info.useZoomRatio) {
            return result.get(CaptureResult.CONTROL_ZOOM_RATIO) ?: 0f
        }
        val activeArray = info.activeArray ?: return 0f
        val crop = result.get(CaptureResult.SCALER_CROP_REGION) ?: return 0f
        if (crop.width() <= 0) return 0f
        return activeArray.width().toFloat() / crop.width()
    }

    /** Filled lazily once per opened camera, cleared in openCameraId and closeCamera. */
    private fun getZoomInfoLocked(): ZoomInfo? {
        val cached = zoomInfo
        if (cached != null && cached.cameraId == cameraId) return cached
        val id = cameraId
        val characteristics = try {
            cameraManager.getCameraCharacteristics(id)
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            return null
        }
        //same conditions as zoomRange and the zoom setter
        val level = characteristics.secureGet(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
        val useZoomRatio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && level != CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
        var range: Range<Float>? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && useZoomRatio) {
            range = characteristics.secureGet(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        }
        if (range == null) {
            val maxZoom = characteristics.secureGet(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            range = Range(1f, maxZoom)
        }
        val afModes = characteristics.secureGet(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        val continuousFocusMode = when {
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            else -> null
        }
        var preCorrectionArray: Rect? = null
        var distortionCorrectionSupported = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            preCorrectionArray = characteristics.secureGet(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            distortionCorrectionSupported = characteristics.secureGet(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                ?.any { it != CameraMetadata.DISTORTION_CORRECTION_MODE_OFF } == true
        }
        val info = ZoomInfo(
            id, range, useZoomRatio,
            characteristics.secureGet(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            characteristics.secureGet(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH)?.toInt() ?: 0,
            preCorrectionArray,
            characteristics.secureGet(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
            characteristics.secureGet(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
            continuousFocusMode,
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO),
            distortionCorrectionSupported,
            characteristics.secureGet(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            characteristics.secureGet(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        )
        zoomInfo = info
        return info
    }

    @JvmOverloads
    fun closeCamera(resetSurface: Boolean = true) {
        isExposureLockEnabled = false
        isWhiteBalanceLockEnabled = false
        isLanternEnabled = false
        zoomLevel = 1.0f
        onZoomCameraClosing()
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        cameraDevice?.close()
        cameraDevice = null
        if (resetSurface) {
            surfaceEncoder?.release()
            surfaceEncoder = null
            builderInputSurface = null
        }
        isPrepared = false
        isRunning = false
    }

    fun addImageListener(width: Int, height: Int, format: Int, maxImages: Int, autoClose: Boolean, listener: ImageCallback) {
        val wasRunning = isRunning
        closeCamera(false)
        this.imageReader?.close()
        val imageThread = HandlerThread("$TAG imageThread")
        imageThread.start()
        val imageReader = ImageReader.newInstance(width, height, format, maxImages)
        imageReader.setOnImageAvailableListener({ reader: ImageReader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                listener.onImageAvailable(image)
                if (autoClose) image.close()
            }
        }, Handler(imageThread.looper))
        this.imageReader = imageReader
        val surface = surfaceEncoder
        if (wasRunning && surface != null) {
            prepareSameSurface(surface)
            openLastCamera()
        }
    }

    fun removeImageListener() {
        val imageReader = this.imageReader ?: return
        val wasRunning = isRunning
        if (wasRunning) closeCamera(false)
        imageReader.close()
        this.imageReader = null
        val surface = surfaceEncoder
        if (wasRunning && surface != null) {
            prepareSameSurface(surface)
            openLastCamera()
        }
    }

    @JvmOverloads
    fun getCameraIdForFacing(facing: Facing, cameraManager: CameraManager = this.cameraManager): String {
        val selectedFacing = if (facing == Facing.BACK) CameraMetadata.LENS_FACING_BACK else CameraMetadata.LENS_FACING_FRONT
        val ids = cameraManager.cameraIdList
        for (cameraId in ids) {
            val cameraFacing = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)
            if (cameraFacing != null && cameraFacing == selectedFacing) {
                return cameraId
            }
        }
        if (ids.isEmpty()) throw CameraOpenException("Camera no detected")
        return ids[0]
    }

    private fun getFacingByCameraId(cameraManager: CameraManager, cameraId: String): Facing {
        try {
            for (id in cameraManager.cameraIdList) {
                if (id == cameraId) {
                    val cameraFacing = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)
                    return if (cameraFacing == CameraMetadata.LENS_FACING_BACK) Facing.BACK
                    else Facing.FRONT
                }
            }
            return Facing.BACK
        } catch (_: Exception) {
            return Facing.BACK
        }
    }

    @Suppress("DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun createCaptureSession(
        cameraDevice: CameraDevice,
        surfaces: List<Surface>,
        onConfigured: (CameraCaptureSession) -> Unit,
        onConfiguredFailed: (CameraCaptureSession) -> Unit,
        handler: Handler?
    ) {
        val callback = object: CameraCaptureSession.StateCallback() {
            override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                onConfigured(cameraCaptureSession)
            }

            override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                onConfiguredFailed(cameraCaptureSession)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val configurations = surfaces.map { OutputConfiguration(it) }
            configurations.forEach { it.setPhysicalCameraId(physicalCameraId) }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                configurations,
                Executors.newSingleThreadExecutor(),
                callback
            )
            cameraDevice.createCaptureSession(config)
        } else {
            cameraDevice.createCaptureSession(surfaces, callback, handler)
        }
    }
}