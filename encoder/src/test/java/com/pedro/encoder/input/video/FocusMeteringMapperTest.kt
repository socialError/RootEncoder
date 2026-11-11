package com.pedro.encoder.input.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusMeteringMapperTest {
    @Test fun blackBarsAreExcluded() {
        assertNull(FocusMeteringMapper.sensorPoint(100f, 540f, 2400, 1080, 1280, 720, 0, false))
        assertEquals(Pair(0f, 0.5f), FocusMeteringMapper.sensorPoint(240f, 540f, 2400, 1080, 1280, 720, 0, false))
        assertEquals(Pair(1f, 0.5f), FocusMeteringMapper.sensorPoint(2160f, 540f, 2400, 1080, 1280, 720, 0, false))
    }

    @Test fun letterboxAndInvalidLayout() {
        assertNull(FocusMeteringMapper.sensorPoint(500f, 100f, 1000, 1000, 1600, 900, 0, false))
        assertEquals(Pair(0.5f, 0.5f), FocusMeteringMapper.sensorPoint(500f, 500f, 1000, 1000, 1600, 900, 0, false))
        assertNull(FocusMeteringMapper.sensorPoint(1f, 1f, 0, 0, 1280, 720, 0, false))
        assertNull(FocusMeteringMapper.sensorPoint(Float.NaN, 1f, 2400, 1080, 1280, 720, 0, false))
    }

    @Test fun sensorUpsideDownInLandscape() {
        // back sensor mounted at 270 degrees, display rotation 90
        assertEquals(Pair(1f, 0.5f), FocusMeteringMapper.sensorPoint(240f, 540f, 2400, 1080, 1280, 720, 180, false))
    }

    @Test fun portraitRotation() {
        // the sensor top-left corner is shown in the top-right corner of a portrait view
        assertEquals(Pair(0f, 0f), FocusMeteringMapper.sensorPoint(1080f, 240f, 1080, 2400, 1280, 720, 90, false))
        assertEquals(Pair(0f, 1f), FocusMeteringMapper.sensorPoint(0f, 240f, 1080, 2400, 1280, 720, 90, false))
        assertEquals(Pair(1f, 0f), FocusMeteringMapper.sensorPoint(0f, 240f, 1080, 2400, 1280, 720, 270, false))
    }

    @Test fun relativeRotation() {
        // back sensor 90: upright in portrait after 90, aligned in landscape (display 90)
        assertEquals(90, FocusMeteringMapper.relativeRotation(90, 0, false))
        assertEquals(0, FocusMeteringMapper.relativeRotation(90, 90, false))
        assertEquals(180, FocusMeteringMapper.relativeRotation(90, 270, false))
        // back sensor 270 (mounted upside down)
        assertEquals(180, FocusMeteringMapper.relativeRotation(270, 90, false))
        assertEquals(0, FocusMeteringMapper.relativeRotation(270, 270, false))
        // front sensor 270
        assertEquals(270, FocusMeteringMapper.relativeRotation(270, 0, true))
        assertEquals(0, FocusMeteringMapper.relativeRotation(270, 90, true))
        assertEquals(180, FocusMeteringMapper.relativeRotation(270, 270, true))
    }

    @Test fun frontCameraIsMirrored() {
        assertEquals(Pair(1f, 0.5f), FocusMeteringMapper.sensorPoint(240f, 540f, 2400, 1080, 1280, 720, 0, true))
    }

    @Test fun sensorAspectCropAndOffset() {
        assertArrayEquals(intArrayOf(1700, 1331, 600, 338),
            FocusMeteringMapper.region(.5f, .5f, 0, 0, 4000, 3000, 1600, 900))
        assertArrayEquals(intArrayOf(100, 425, 600, 338),
            FocusMeteringMapper.region(0f, 0f, 100, 50, 4100, 3050, 1600, 900))
        assertArrayEquals(intArrayOf(3500, 2337, 600, 338),
            FocusMeteringMapper.region(1f, 1f, 100, 50, 4100, 3050, 1600, 900))
    }

    @Test fun legacyZoomCrop() {
        assertArrayEquals(intArrayOf(1000, 938, 300, 169),
            FocusMeteringMapper.region(0f, 0f, 1000, 750, 3000, 2250, 1600, 900))
        assertArrayEquals(intArrayOf(2700, 1893, 300, 169),
            FocusMeteringMapper.region(1f, 1f, 1000, 750, 3000, 2250, 1600, 900))
    }
}
