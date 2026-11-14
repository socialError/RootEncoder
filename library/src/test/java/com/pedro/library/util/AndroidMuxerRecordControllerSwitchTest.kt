package com.pedro.library.util

import android.media.MediaCodec
import android.media.MediaFormat
import com.pedro.library.base.recording.RecordController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android media classes are stubs here, so the muxer writes nothing. The tests cover the switch
 * bookkeeping: which callbacks fire, when, and what is cleaned up.
 */
class AndroidMuxerRecordControllerSwitchTest {

  private class Switched(val finished: String, val next: String, val error: Exception?)

  /** Counts the frames the muxer coroutine is done with, so a test can wait for them. */
  private class TestController : AndroidMuxerRecordController() {
    @Volatile
    var frameLatch = CountDownLatch(0)

    override fun onFrameRecycled() {
      frameLatch.countDown()
    }
  }

  @get:Rule
  val folder = TemporaryFolder()

  private lateinit var pathA: String
  private lateinit var pathB: String
  private lateinit var controller: TestController
  private val switched = mutableListOf<Switched>()
  private var switchLatch = CountDownLatch(1)
  private val switchListener = RecordController.SwitchListener { finishedPath, newPath, _, _, error ->
    switched.add(Switched(finishedPath, newPath, error))
    switchLatch.countDown()
  }

  @Before
  fun setUp() {
    pathA = File(folder.root, "a.mp4").path
    pathB = File(folder.root, "b.mp4").path
    controller = TestController()
    controller.setVideoFormat(MediaFormat())
    controller.setAudioFormat(MediaFormat())
    controller.startRecord(pathA, null, RecordController.RecordTracks.ALL)
    sendAndWait { sendVideo(1_000_000, true) }
    assertEquals(RecordController.Status.RECORDING, controller.getStatus())
  }

  @After
  fun tearDown() {
    controller.stopRecord()
  }

  private fun sendAndWait(send: () -> Unit) {
    controller.frameLatch = CountDownLatch(1)
    send()
    assertTrue(controller.frameLatch.await(WAIT_MS, TimeUnit.MILLISECONDS))
  }

  private fun sendVideo(ts: Long, key: Boolean) {
    val info = MediaCodec.BufferInfo().apply {
      offset = 0
      size = FRAME_SIZE
      presentationTimeUs = ts
      flags = if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
    }
    controller.recordVideo(ByteBuffer.wrap(ByteArray(FRAME_SIZE)), info)
  }

  private fun sendAudio(ts: Long) {
    val info = MediaCodec.BufferInfo().apply {
      offset = 0
      size = FRAME_SIZE
      presentationTimeUs = ts
      flags = 0
    }
    controller.recordAudio(ByteBuffer.wrap(ByteArray(FRAME_SIZE)), info)
  }

  @Test
  fun `GIVEN a switch WHEN keyframe and later audio arrive THEN the listener gets old and new path`() {
    assertTrue(controller.switchRecord(pathB, switchListener))
    sendVideo(2_000_000, false)
    sendVideo(3_000_000, true)
    sendAudio(3_100_000)

    assertTrue(switchLatch.await(WAIT_MS, TimeUnit.MILLISECONDS))
    assertEquals(pathA, switched[0].finished)
    assertEquals(pathB, switched[0].next)
    assertNull(switched[0].error)
  }

  @Test
  fun `GIVEN a pending switch WHEN a second one is requested THEN it is refused`() {
    assertTrue(controller.switchRecord(pathB, switchListener))
    assertFalse(controller.switchRecord(File(folder.root, "c.mp4").path, switchListener))
  }

  @Test
  fun `GIVEN a finished switch WHEN another is requested THEN it is accepted`() {
    assertTrue(controller.switchRecord(pathB, switchListener))
    sendVideo(2_000_000, false)
    sendVideo(3_000_000, true)
    sendAudio(3_100_000)
    assertTrue(switchLatch.await(WAIT_MS, TimeUnit.MILLISECONDS))

    assertTrue(controller.switchRecord(File(folder.root, "c.mp4").path, switchListener))
  }

  @Test
  fun `GIVEN a switch before its keyframe WHEN stop THEN no callback and the new file is deleted`() {
    File(pathB).writeText("x")
    assertTrue(controller.switchRecord(pathB, switchListener))
    sendAndWait { sendVideo(2_000_000, false) }
    controller.stopRecord()

    assertTrue(switched.isEmpty())
    assertFalse(File(pathB).exists())
  }

  @Test
  fun `GIVEN a switch after its keyframe WHEN stop THEN the callback ran before stop returned`() {
    assertTrue(controller.switchRecord(pathB, switchListener))
    sendAndWait { sendVideo(2_000_000, false) }
    sendAndWait { sendVideo(3_000_000, true) }
    controller.stopRecord()

    assertEquals(0, switchLatch.count)
    assertEquals(pathA, switched[0].finished)
  }

  private companion object {
    const val FRAME_SIZE = 16
    const val WAIT_MS = 2000L
  }
}
