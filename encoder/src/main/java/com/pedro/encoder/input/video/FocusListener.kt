package com.pedro.encoder.input.video

/**
 * Result of the AF scan started by tapToFocus. Called on the camera thread, only when the camera
 * reports the scan as finished (not when the FOCUS_SCAN_HOLD_MS fallback ends it).
 */
fun interface FocusListener {
    /**
     * @param scanId the id tapToFocus gave the scan (Camera2Source.getFocusScanId())
     * @param success true when the lens focused on the point
     */
    fun onFocusFinished(scanId: Int, success: Boolean)
}
