package com.spandan.app.oximetry

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import android.util.Range

/**
 * [Segment 34] Task 1 capability probe: reads the front camera's
 * [CameraCharacteristics] once and logs every key the oximetry-capture
 * design branches on, under the logcat tag `SPANDAN_CAPS`
 * (`adb logcat -s SPANDAN_CAPS`). The design decision itself is the pure
 * [OximetryMath.decideCapturePlan].
 */
object CameraCapabilityProbe {

    private const val TAG = "SPANDAN_CAPS"

    data class Capabilities(
        val cameraId: String,
        val hardwareLevel: String,
        val hasManualSensor: Boolean,
        val hasManualPostProcessing: Boolean,
        val hasReadSensorSettings: Boolean,
        val toneMapModes: List<Int>,
        val hasContrastCurve: Boolean,
        val maxCurvePoints: Int?,
        val aeLockAvailable: Boolean,
        val awbLockAvailable: Boolean,
        val exposureRangeNs: Range<Long>?,
        val sensitivityRange: Range<Int>?,
        val targetFpsRanges: List<Range<Int>>,
        val afModes: List<Int>,
        val minFocusDistance: Float?,
        val oisModes: List<Int>,
        val timestampSourceRealtime: Boolean,
        val maxFrameDurationNs: Long?,
        /** AE exposure-compensation range in steps, and the size of one step
         *  in EV -- the only exposure lever Branch B has. */
        val aeCompensationRange: Range<Int>?,
        val aeCompensationStepEv: Double,
        val plan: OximetryMath.CapturePlan
    ) {
        /** Fixed 30 fps if the camera offers [30,30]; otherwise the fixed
         *  range with the highest fps; otherwise the widest range ending at
         *  the highest max. Null if the camera reports none. */
        fun chosenFpsRange(): Range<Int>? {
            targetFpsRanges.firstOrNull { it.lower == 30 && it.upper == 30 }?.let { return it }
            targetFpsRanges.filter { it.lower == it.upper }.maxByOrNull { it.upper }?.let { return it }
            return targetFpsRanges.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { -it.lower })
        }
    }

    fun frontCameraId(context: Context): String? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        }
    }

    fun probe(context: Context, cameraId: String): Capabilities {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val c = manager.getCameraCharacteristics(cameraId)

        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
        val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val manualPost = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps
        val readSettings = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS in caps

        val toneModes = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toList() ?: emptyList()
        val contrastCurve = CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in toneModes
        val aeLock = c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false
        val awbLock = c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false

        val capabilities = Capabilities(
            cameraId = cameraId,
            hardwareLevel = hardwareLevelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
            hasManualSensor = manualSensor,
            hasManualPostProcessing = manualPost,
            hasReadSensorSettings = readSettings,
            toneMapModes = toneModes,
            hasContrastCurve = contrastCurve,
            maxCurvePoints = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS),
            aeLockAvailable = aeLock,
            awbLockAvailable = awbLock,
            exposureRangeNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            sensitivityRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            targetFpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList(),
            afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList(),
            minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            oisModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList() ?: emptyList(),
            timestampSourceRealtime = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            maxFrameDurationNs = c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION),
            aeCompensationRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
            aeCompensationStepEv = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: 0.0,
            plan = OximetryMath.decideCapturePlan(manualSensor, manualPost, contrastCurve, aeLock, awbLock)
        )
        log(capabilities, caps)
        return capabilities
    }

    private fun log(k: Capabilities, rawCaps: List<Int>) {
        Log.i(TAG, "device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        Log.i(TAG, "cameraId=${k.cameraId} hardwareLevel=${k.hardwareLevel}")
        Log.i(TAG, "REQUEST_AVAILABLE_CAPABILITIES=${rawCaps.map(::capabilityName)}")
        Log.i(TAG, "MANUAL_SENSOR=${k.hasManualSensor} MANUAL_POST_PROCESSING=${k.hasManualPostProcessing} READ_SENSOR_SETTINGS=${k.hasReadSensorSettings}")
        Log.i(TAG, "TONEMAP_AVAILABLE_TONE_MAP_MODES=${k.toneMapModes.map(::toneMapName)} CONTRAST_CURVE=${k.hasContrastCurve} TONEMAP_MAX_CURVE_POINTS=${k.maxCurvePoints}")
        Log.i(TAG, "CONTROL_AE_LOCK_AVAILABLE=${k.aeLockAvailable} CONTROL_AWB_LOCK_AVAILABLE=${k.awbLockAvailable}")
        Log.i(TAG, "SENSOR_INFO_EXPOSURE_TIME_RANGE=${k.exposureRangeNs} SENSOR_INFO_SENSITIVITY_RANGE=${k.sensitivityRange} SENSOR_INFO_MAX_FRAME_DURATION=${k.maxFrameDurationNs}")
        Log.i(TAG, "CONTROL_AE_COMPENSATION_RANGE=${k.aeCompensationRange} CONTROL_AE_COMPENSATION_STEP=${k.aeCompensationStepEv}EV")
        Log.i(TAG, "CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES=${k.targetFpsRanges} chosen=${k.chosenFpsRange()}")
        Log.i(TAG, "CONTROL_AF_AVAILABLE_MODES=${k.afModes} LENS_INFO_MINIMUM_FOCUS_DISTANCE=${k.minFocusDistance} OIS_MODES=${k.oisModes} TIMESTAMP_SOURCE_REALTIME=${k.timestampSourceRealtime}")
        Log.i(TAG, "PLAN branch=${k.plan.branch} linearToneCurve=${k.plan.linearToneCurve}")
    }

    fun hardwareLevelName(level: Int?): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($level)"
    }

    private fun capabilityName(cap: Int): String = when (cap) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        19 -> "STREAM_USE_CASE"
        else -> "CAP_$cap"
    }

    fun toneMapName(mode: Int?): String = when (mode) {
        CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE -> "CONTRAST_CURVE"
        CameraMetadata.TONEMAP_MODE_FAST -> "FAST"
        CameraMetadata.TONEMAP_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        CameraMetadata.TONEMAP_MODE_GAMMA_VALUE -> "GAMMA_VALUE"
        CameraMetadata.TONEMAP_MODE_PRESET_CURVE -> "PRESET_CURVE"
        null -> "null"
        else -> "MODE_$mode"
    }
}
