package com.pedro.library.base.recording

import com.pedro.library.base.recording.RecordSwitchRouter.Action
import com.pedro.library.base.recording.RecordSwitchRouter.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordSwitchRouterTest {

  private fun router(hasAudio: Boolean = true) = RecordSwitchRouter<String>(hasAudio)

  private fun write(frame: String, target: Target, ts: Long) = Action.Write(frame, target, ts)

  @Test
  fun `GIVEN idle WHEN video and audio THEN they pass through unchanged`() {
    val r = router()
    assertEquals(listOf(write("v", Target.ACTIVE, 100)), r.onVideo("v", 100, false))
    assertEquals(listOf(write("a", Target.ACTIVE, 90)), r.onAudio("a", 90))
  }

  @Test
  fun `GIVEN a request WHEN keyframe THEN next file starts and the keyframe is its first frame`() {
    val r = router()
    r.onVideo("v0", 0, true)
    assertTrue(r.requestSwitch())
    val actions = r.onVideo("k", 1000, true)
    assertEquals(Action.StartNext(1000), actions[0])
    assertEquals(write("k", Target.ACTIVE, 0), actions[1])
  }

  @Test
  fun `GIVEN waiting WHEN audio before the last video THEN it is written now`() {
    val r = router()
    r.onVideo("v0", 0, true)
    r.requestSwitch()
    r.onVideo("v1", 500, false)
    assertEquals(listOf(write("a", Target.ACTIVE, 400)), r.onAudio("a", 400))
  }

  @Test
  fun `GIVEN waiting WHEN audio after the last video THEN it is held and flushed before the next video`() {
    val r = router()
    r.onVideo("v0", 0, true)
    r.requestSwitch()
    r.onVideo("v1", 500, false)
    assertTrue(r.onAudio("a", 520).isEmpty())
    val actions = r.onVideo("v2", 540, false)
    assertEquals(listOf(write("a", Target.ACTIVE, 520), write("v2", Target.ACTIVE, 540)), actions)
  }

  @Test
  fun `GIVEN held audio around the keyframe WHEN keyframe THEN actions are in exact order and previous finishes`() {
    val r = router()
    r.onVideo("v0", 0, true)
    r.requestSwitch()
    r.onAudio("a1", 900)
    r.onAudio("a2", 1100)
    val actions = r.onVideo("k", 1000, true)
    assertEquals(
      listOf(
        write("a1", Target.ACTIVE, 900),
        Action.StartNext(1000),
        write("k", Target.ACTIVE, 0),
        write("a2", Target.ACTIVE, 100),
        Action.FinishPrevious
      ),
      actions
    )
  }

  @Test
  fun `GIVEN draining WHEN audio before split THEN closing file with old base, after split THEN active and finish`() {
    val r = router()
    r.onVideo("v0", 0, true)
    r.requestSwitch()
    r.onVideo("k", 1000, true)
    assertEquals(listOf(write("a1", Target.CLOSING, 950)), r.onAudio("a1", 950))
    assertEquals(
      listOf(Action.FinishPrevious, write("a2", Target.ACTIVE, 20)),
      r.onAudio("a2", 1020)
    )
    assertFalse(r.isWaitingForKeyFrame())
    assertEquals(listOf(write("v", Target.ACTIVE, 40)), r.onVideo("v", 1040, false))
  }

  @Test
  fun `GIVEN draining WHEN video passes the timeout THEN previous finishes`() {
    val r = router()
    r.onVideo("v0", 0, true)
    r.requestSwitch()
    r.onVideo("k", 1000, true)
    val ts = 1000 + RecordSwitchRouter.AUDIO_DRAIN_TIMEOUT_US
    assertEquals(
      listOf(write("v", Target.ACTIVE, RecordSwitchRouter.AUDIO_DRAIN_TIMEOUT_US), Action.FinishPrevious),
      r.onVideo("v", ts, false)
    )
  }

  @Test
  fun `GIVEN no audio WHEN keyframe THEN previous finishes right away`() {
    val r = router(false)
    r.onVideo("v0", 0, true)
    r.requestSwitch()
    assertEquals(
      listOf(Action.StartNext(1000), write("k", Target.ACTIVE, 0), Action.FinishPrevious),
      r.onVideo("k", 1000, true)
    )
  }

  @Test
  fun `GIVEN a switch in progress WHEN requestSwitch THEN false`() {
    val r = router()
    assertTrue(r.requestSwitch())
    assertFalse(r.requestSwitch())
    r.onVideo("k", 1000, true)
    assertFalse(r.requestSwitch())
  }

  @Test
  fun `GIVEN held audio WHEN cancelSwitch THEN it goes to the active file`() {
    val r = router()
    r.onVideo("v0", 0, true)
    r.requestSwitch()
    r.onAudio("a", 100)
    assertEquals(listOf(write("a", Target.ACTIVE, 100)), r.cancelSwitch())
    assertFalse(r.isWaitingForKeyFrame())
    assertTrue(r.cancelSwitch().isEmpty())
  }

  @Test
  fun `GIVEN a finished switch WHEN audio older than the new file THEN it is dropped`() {
    val r = router(false)
    r.onVideo("v0", 0, true)
    r.requestSwitch()
    r.onVideo("k", 1000, true)
    assertTrue(r.onAudio("late", 900).isEmpty())
  }

  @Test
  fun `GIVEN two switches WHEN second keyframe THEN bases chain`() {
    val r = router(false)
    r.onVideo("v0", 0, true)
    r.requestSwitch()
    r.onVideo("k1", 1000, true)
    r.requestSwitch()
    assertEquals(
      listOf(Action.StartNext(3000), write("k2", Target.ACTIVE, 0), Action.FinishPrevious),
      r.onVideo("k2", 3000, true)
    )
    assertEquals(listOf(write("v", Target.ACTIVE, 40)), r.onVideo("v", 3040, false))
  }

  @Test
  fun `GIVEN idle WHEN keyframe THEN it does not split`() {
    val r = router()
    assertEquals(listOf(write("k", Target.ACTIVE, 500)), r.onVideo("k", 500, true))
  }

  @Test
  fun `GIVEN startAt WHEN keyframe THEN it is written at 0 and earlier audio is dropped`() {
    val r = router()
    r.startAt(230)
    assertTrue(r.onAudio("early", 100).isEmpty())
    assertEquals(listOf(write("k", Target.ACTIVE, 0)), r.onVideo("k", 230, true))
    assertEquals(listOf(write("a", Target.ACTIVE, 20)), r.onAudio("a", 250))
  }

  @Test
  fun `GIVEN startAt WHEN a switch follows THEN it splits relative to the new base`() {
    val r = router(false)
    r.startAt(230)
    r.onVideo("k", 230, true)
    r.requestSwitch()
    assertEquals(
      listOf(Action.StartNext(1230), write("k2", Target.ACTIVE, 0), Action.FinishPrevious),
      r.onVideo("k2", 1230, true)
    )
  }
}
