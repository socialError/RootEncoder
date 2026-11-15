package com.pedro.encoder

import android.media.MediaCodecInfo
import com.pedro.encoder.video.BitrateModeSelector
import com.pedro.encoder.video.VideoEncoder
import junit.framework.TestCase.assertEquals
import org.junit.Test

class BitrateModeSelectorTest {

    private val cbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
    private val vbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
    private val cq = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
    private val unknownMode = 99

    @Test
    fun `default mode uses cbr when supported`() {
        assertEquals(cbr, BitrateModeSelector.select(VideoEncoder.BITRATE_MODE_DEFAULT, false, true))
    }

    @Test
    fun `default mode sets nothing when cbr is not supported`() {
        assertEquals(VideoEncoder.BITRATE_MODE_NOT_SET, BitrateModeSelector.select(VideoEncoder.BITRATE_MODE_DEFAULT, false, false))
    }

    @Test
    fun `supported vbr is applied`() {
        assertEquals(vbr, BitrateModeSelector.select(vbr, true, true))
    }

    @Test
    fun `unsupported vbr falls back to cbr`() {
        assertEquals(cbr, BitrateModeSelector.select(vbr, false, true))
    }

    @Test
    fun `unsupported vbr without cbr sets nothing`() {
        assertEquals(VideoEncoder.BITRATE_MODE_NOT_SET, BitrateModeSelector.select(vbr, false, false))
    }

    @Test
    fun `supported cbr is applied`() {
        assertEquals(cbr, BitrateModeSelector.select(cbr, true, true))
    }

    @Test
    fun `supported cq is applied`() {
        assertEquals(cq, BitrateModeSelector.select(cq, true, true))
    }

    @Test
    fun `unknown unsupported mode falls back to cbr`() {
        assertEquals(cbr, BitrateModeSelector.select(unknownMode, false, true))
    }
}
