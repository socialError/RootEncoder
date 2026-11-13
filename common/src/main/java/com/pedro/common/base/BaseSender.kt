package com.pedro.common.base

import android.util.Log
import com.pedro.common.BitrateManager
import com.pedro.common.BufferPool
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamBlockingQueue
import com.pedro.common.clone
import com.pedro.common.StreamingStatsMonitor
import com.pedro.common.frame.MediaFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

abstract class BaseSender(
    protected val connectChecker: ConnectChecker,
    protected val TAG: String
) {

    companion object {
        /**
         * Longest wait for a keyframe after a dropped video frame (frame timestamps, us). Then video
         * is sent again even without a keyframe, so a keyframe that never comes can't freeze it.
         */
        internal const val KEYFRAME_WAIT_LIMIT_US = 5_000_000L
    }

    @Volatile
    protected var running = false

    private val queue = StreamBlockingQueue(400)
    private val bufferPool = BufferPool()

    protected val audioFramesSent = AtomicLong(0)
    protected val videoFramesSent = AtomicLong(0)
    private val droppedAudioFrames = AtomicLong(0)
    private val droppedVideoFrames = AtomicLong(0)

    //a video frame was dropped, so video is dropped until the next keyframe
    @Volatile
    private var waitingForKeyFrame = false
    private var keyFrameWaitStartTs = 0L

    private val bitrateManager: BitrateManager = BitrateManager(connectChecker)
    private val streamingStatsMonitor: StreamingStatsMonitor = StreamingStatsMonitor(connectChecker)
    protected var isEnableLogs = true
    private var job: Job? = null
    protected val scope = CoroutineScope(Dispatchers.IO)

    protected val bytesSend = AtomicLong(0)
    protected val bytesSendPerSecond = AtomicLong(0)

    abstract fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?)
    abstract fun setAudioInfo(sampleRate: Int, isStereo: Boolean)
    protected abstract suspend fun onRun()
    protected abstract suspend fun stopImp(clear: Boolean = true)

    /**
     * When the queue is full the frame is dropped. Video frames reference the previous ones, so
     * after a dropped video frame the next ones are dropped too until a keyframe: the receiver
     * shows a short freeze instead of a broken picture until the next keyframe. Audio frames
     * don't depend on each other and keep going.
     */
    fun sendMediaFrame(buffer: ByteBuffer, info: MediaFrame.Info, type: MediaFrame.Type) {
        if (!running) return
        if (type == MediaFrame.Type.VIDEO && waitingForKeyFrame) {
            if (!info.isKeyFrame && info.timestamp - keyFrameWaitStartTs < KEYFRAME_WAIT_LIMIT_US) {
                droppedVideoFrames.incrementAndGet()
                return
            }
            waitingForKeyFrame = false
            Log.i(TAG, "Wait for a keyframe ended, keyframe: ${info.isKeyFrame}")
        }
        val data = bufferPool.acquire(buffer.limit())
        val mediaFrame = MediaFrame(buffer.clone(data), info, type)
        if (!queue.trySend(mediaFrame)) {
            bufferPool.release(mediaFrame.data)
            when (type) {
                MediaFrame.Type.VIDEO -> {
                    Log.i(TAG, "Video frame discarded, discarding video until the next keyframe")
                    droppedVideoFrames.incrementAndGet()
                    keyFrameWaitStartTs = info.timestamp
                    waitingForKeyFrame = true
                }
                MediaFrame.Type.AUDIO -> {
                    Log.i(TAG, "Audio frame discarded")
                    droppedAudioFrames.incrementAndGet()
                }
            }
        }
    }

    /**
     * Take the next frame from the queue, hand it to [consume] and recycle its buffer once
     * consumed. Senders must read frames only through this method, the buffer is reused right
     * after [consume] returns.
     */
    protected suspend fun consumeFrame(consume: suspend (MediaFrame) -> Unit) {
        val mediaFrame = runInterruptible { queue.take() }
        try {
            consume(mediaFrame)
        } finally {
            bufferPool.release(mediaFrame.data)
        }
    }

    suspend fun start() {
        running = false
        job?.cancelAndJoin()
        bitrateManager.reset()
        queue.clear { bufferPool.release(it.data) }
        waitingForKeyFrame = false
        streamingStatsMonitor.reset()
        running = true
        job = scope.launch {
            val bitrateTask = async {
                while (scope.isActive && running) {
                    try {
                        val bytesThisSecond = bytesSendPerSecond.getAndSet(0)
                        //bytes to bits
                        bitrateManager.calculateBitrate(bytesThisSecond * 8)
                        streamingStatsMonitor.collect(
                            queueBytesOut = queue.getTotalSize(),
                            bytesOutPerSecond = bytesThisSecond,
                            totalBytesOut = bytesSend.get(),
                            smoothedBitrate = bitrateManager.getSmoothedBitrate(),
                            queueCongestionPercent = queueUsagePercent(),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        //never let a reporting failure (e.g. no Main dispatcher, checker callback
                        //throwing) take down the send loop
                        Log.e(TAG, "bitrate/stats reporting failed", e)
                    }
                    delay(timeMillis = 1000)
                }
            }
            onRun()
        }
    }

    suspend fun stop(clear: Boolean = true, unlockNeeded: suspend () -> Unit = {}) {
        running = false
        stopImp(clear)
        resetSentAudioFrames()
        resetSentVideoFrames()
        resetDroppedAudioFrames()
        resetDroppedVideoFrames()
        resetBytesSend()
        val stopped = withTimeoutOrNull(1000.milliseconds) { job?.cancelAndJoin() } != null
        if (!stopped) {
            unlockNeeded()
            withTimeoutOrNull(1000.milliseconds) { job?.cancelAndJoin() }
        }
        job = null
        queue.clear { bufferPool.release(it.data) }
        waitingForKeyFrame = false
        bufferPool.clear()
    }

    @Throws(IllegalArgumentException::class)
    fun hasCongestion(percentUsed: Float = 20f): Boolean {
        if (percentUsed !in 0.0..100.0) throw IllegalArgumentException("the value must be in range 0 to 100")
        return queueUsagePercent() >= percentUsed
    }

    private fun queueUsagePercent(): Float {
        val size = queue.getSize().toFloat()
        val remaining = queue.remainingCapacity().toFloat()
        val capacity = size + remaining
        return if (capacity <= 0f) 0f else (size / capacity) * 100f
    }

    fun resizeCache(newSize: Int) {
        if (newSize < queue.getSize()) {
            throw RuntimeException("Can't fit current cache inside new cache size")
        }
        queue.capacity = newSize
    }

    fun getCacheSize(): Int = queue.capacity

    fun getItemsInCache(): Int = queue.getSize()

    fun getQueueBytesOut(): Long = queue.getTotalSize()

    fun clearCache() {
        queue.clear { bufferPool.release(it.data) }
    }

    fun getSentAudioFrames(): Long = audioFramesSent.get()

    fun getSentVideoFrames(): Long = videoFramesSent.get()

    fun getDroppedAudioFrames(): Long = droppedAudioFrames.get()

    fun getDroppedVideoFrames(): Long = droppedVideoFrames.get()

    fun getBytesSend(): Long = bytesSend.get()

    fun resetSentAudioFrames() {
        audioFramesSent.set(0)
    }

    fun resetSentVideoFrames() {
        videoFramesSent.set(0)
    }

    fun resetDroppedAudioFrames() {
        droppedAudioFrames.set(0)
    }

    fun resetDroppedVideoFrames() {
        droppedVideoFrames.set(0)
    }

    fun setLogs(enable: Boolean) {
        isEnableLogs = enable
    }

    fun setBitrateExponentialFactor(factor: Float) {
        bitrateManager.exponentialFactor = factor
    }

    fun getBitrateExponentialFactor() = bitrateManager.exponentialFactor

    fun setDelay(delay: Long) {
        queue.setCacheTime(delay)
    }

    fun resetBytesSend() {
        bytesSend.set(0)
    }
}