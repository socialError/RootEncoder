package com.pedro.library.base.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopAudioCatchUpTest {

  @Test
  fun `GIVEN no video was sent WHEN closed THEN it is caught up right away`() {
    val catchUp = StopAudioCatchUp()
    assertEquals(StopAudioCatchUp.NO_TIMESTAMP, catchUp.closeVideo())
    assertTrue(catchUp.isCaughtUp())
  }

  @Test
  fun `GIVEN video was sent WHEN audio is behind THEN it is not caught up until audio reaches the video`() {
    val catchUp = StopAudioCatchUp()
    catchUp.onVideoSent(1000)
    catchUp.onVideoSent(2000)
    assertEquals(2000, catchUp.closeVideo())
    catchUp.onAudioWritten(1900)
    assertFalse(catchUp.isCaughtUp())
    catchUp.onAudioWritten(2000)
    assertTrue(catchUp.isCaughtUp())
  }

  @Test
  fun `GIVEN closed video WHEN asked THEN video is not accepted`() {
    val catchUp = StopAudioCatchUp()
    assertTrue(catchUp.acceptsVideo())
    catchUp.closeVideo()
    assertFalse(catchUp.acceptsVideo())
  }

  @Test
  fun `GIVEN audio written before the close WHEN closed THEN older audio does not count back`() {
    val catchUp = StopAudioCatchUp()
    catchUp.onAudioWritten(5000)
    catchUp.onVideoSent(6000)
    catchUp.closeVideo()
    assertFalse(catchUp.isCaughtUp())
    catchUp.onAudioWritten(4000)
    assertFalse(catchUp.isCaughtUp())
  }

  @Test
  fun `GIVEN a burst of audio WHEN video is closed THEN audio before the end is written and the rest dropped`() {
    val catchUp = StopAudioCatchUp()
    catchUp.onVideoSent(2000)
    catchUp.closeVideo()
    assertTrue(catchUp.acceptsAudio(1900))
    catchUp.onAudioWritten(1900)
    assertFalse(catchUp.isCaughtUp())
    assertFalse(catchUp.acceptsAudio(2000))
    assertFalse(catchUp.acceptsAudio(2020))
    assertTrue(catchUp.isCaughtUp())
  }

  @Test
  fun `GIVEN the video is not closed WHEN audio arrives THEN it is always accepted`() {
    val catchUp = StopAudioCatchUp()
    catchUp.onVideoSent(2000)
    assertTrue(catchUp.acceptsAudio(5000))
  }
}
