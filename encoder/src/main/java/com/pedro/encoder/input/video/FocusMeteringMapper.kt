package com.pedro.encoder.input.video

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Maps a tap on an aspect-fit camera preview to a metering rectangle in sensor coordinates.
 */
internal object FocusMeteringMapper {

    /** Size of the metering box as a fraction of the visible image (same default as CameraX). */
    const val REGION_FRACTION = 0.15

    /**
     * Normalized point (0..1) in sensor orientation, or null when the tap is outside the image
     * (black bars of an aspect-fit preview).
     *
     * @param imageWidth image size in sensor orientation
     * @param rotation clockwise degrees the sensor image is rotated by to look upright in the view
     * @param mirrored true for a front camera preview (mirrored horizontally on screen)
     */
    fun sensorPoint(x: Float, y: Float, viewWidth: Int, viewHeight: Int,
                    imageWidth: Int, imageHeight: Int, rotation: Int, mirrored: Boolean): Pair<Float, Float>? {
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0 ||
            !x.isFinite() || !y.isFinite()) return null
        val swapped = rotation == 90 || rotation == 270
        val shownWidth = if (swapped) imageHeight else imageWidth
        val shownHeight = if (swapped) imageWidth else imageHeight
        val scale = min(viewWidth.toFloat() / shownWidth, viewHeight.toFloat() / shownHeight)
        val width = shownWidth * scale
        val height = shownHeight * scale
        val left = (viewWidth - width) / 2f
        val top = (viewHeight - height) / 2f
        if (x < left || x > left + width || y < top || y > top + height) return null
        val viewX = (x - left) / width
        val u = if (mirrored) 1f - viewX else viewX
        val v = (y - top) / height
        return when (rotation) {
            90 -> Pair(v, 1f - u)
            180 -> Pair(1f - u, 1f - v)
            270 -> Pair(1f - v, u)
            else -> Pair(u, v)
        }
    }

    /**
     * Metering rectangle [left, top, width, height] around the normalized sensor point, inside the
     * part of the crop rectangle the output actually shows.
     */
    fun region(x: Float, y: Float, left: Int, top: Int, right: Int, bottom: Int,
               imageWidth: Int, imageHeight: Int): IntArray {
        val cropWidth = right - left
        val cropHeight = bottom - top
        require(cropWidth > 0 && cropHeight > 0 && imageWidth > 0 && imageHeight > 0)
        // Camera2 additionally crops the sensor/zoom rectangle to the output aspect ratio.
        val width = min(cropWidth.toDouble(), cropHeight.toDouble() * imageWidth / imageHeight)
        val height = min(cropHeight.toDouble(), cropWidth.toDouble() * imageHeight / imageWidth)
        val visibleLeft = left + (cropWidth - width) / 2.0
        val visibleTop = top + (cropHeight - height) / 2.0
        val minX = ceil(visibleLeft).toInt()
        val minY = ceil(visibleTop).toInt()
        val maxX = floor(visibleLeft + width).toInt()
        val maxY = floor(visibleTop + height).toInt()
        val boxWidth = (width * REGION_FRACTION).roundToInt().coerceIn(1, (maxX - minX).coerceAtLeast(1))
        val boxHeight = (height * REGION_FRACTION).roundToInt().coerceIn(1, (maxY - minY).coerceAtLeast(1))
        val centerX = visibleLeft + x.coerceIn(0f, 1f) * width
        val centerY = visibleTop + y.coerceIn(0f, 1f) * height
        return intArrayOf(
            (centerX - boxWidth / 2.0).roundToInt().coerceIn(minX, (maxX - boxWidth).coerceAtLeast(minX)),
            (centerY - boxHeight / 2.0).roundToInt().coerceIn(minY, (maxY - boxHeight).coerceAtLeast(minY)),
            boxWidth, boxHeight)
    }
}
