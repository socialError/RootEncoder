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
package com.pedro.library.base.recording

/**
 * Decides to which file each frame goes while a record switches from one file to the next at a
 * video keyframe. Pure logic, no Android dependency, so it can be unit tested.
 *
 * Timestamps in and out are the timeline of the record run. The router rebases them to the start of
 * the file they are written to. Actions must be processed in list order: "ACTIVE" is the file that is
 * active at that point of the list, which changes after [Action.StartNext].
 */
internal class RecordSwitchRouter<T>(private val hasAudio: Boolean) {

  enum class Target { ACTIVE, CLOSING }

  sealed class Action<out T> {
    data class Write<T>(val frame: T, val target: Target, val timestampUs: Long): Action<T>()
    data class StartNext(val splitTimestampUs: Long): Action<Nothing>()
    data object FinishPrevious: Action<Nothing>()
  }

  private enum class State { IDLE, WAITING_KEYFRAME, DRAINING_AUDIO }

  companion object {
    const val AUDIO_DRAIN_TIMEOUT_US = 1_000_000L
  }

  private var state = State.IDLE
  private var activeBaseUs = 0L
  private var closingBaseUs = 0L
  private var splitUs = 0L
  private var lastVideoUs = Long.MIN_VALUE
  private val held = ArrayList<Pair<T, Long>>()

  /** Makes the file of the run start at this video keyframe: earlier audio is dropped, the keyframe is written at 0. */
  fun startAt(keyFrameTimestampUs: Long) {
    activeBaseUs = keyFrameTimestampUs
    lastVideoUs = keyFrameTimestampUs
  }

  fun requestSwitch(): Boolean {
    val canSwitch = state == State.IDLE
    if (canSwitch) state = State.WAITING_KEYFRAME
    return canSwitch
  }

  fun isWaitingForKeyFrame(): Boolean = state == State.WAITING_KEYFRAME

  /** Gives the audio held for the keyframe back to the active file. Only meaningful while waiting. */
  fun cancelSwitch(): List<Action<T>> {
    val actions = ArrayList<Action<T>>()
    if (state == State.WAITING_KEYFRAME) {
      held.forEach { actions.add(Action.Write(it.first, Target.ACTIVE, it.second - activeBaseUs)) }
      held.clear()
      state = State.IDLE
    }
    return actions
  }

  fun onVideo(frame: T, timestampUs: Long, isKeyFrame: Boolean): List<Action<T>> {
    val actions = ArrayList<Action<T>>()
    when (state) {
      State.IDLE -> {
        actions.add(Action.Write(frame, Target.ACTIVE, timestampUs - activeBaseUs))
        lastVideoUs = timestampUs
      }
      State.WAITING_KEYFRAME -> {
        if (isKeyFrame) {
          splitAt(frame, timestampUs, actions)
        } else {
          flushHeldBefore(timestampUs, actions)
          actions.add(Action.Write(frame, Target.ACTIVE, timestampUs - activeBaseUs))
          lastVideoUs = timestampUs
        }
      }
      State.DRAINING_AUDIO -> {
        actions.add(Action.Write(frame, Target.ACTIVE, timestampUs - activeBaseUs))
        lastVideoUs = timestampUs
        if (timestampUs >= splitUs + AUDIO_DRAIN_TIMEOUT_US) {
          actions.add(Action.FinishPrevious)
          state = State.IDLE
        }
      }
    }
    return actions
  }

  fun onAudio(frame: T, timestampUs: Long): List<Action<T>> {
    val actions = ArrayList<Action<T>>()
    when (state) {
      State.IDLE -> {
        if (timestampUs >= activeBaseUs) {
          actions.add(Action.Write(frame, Target.ACTIVE, timestampUs - activeBaseUs))
        }
      }
      State.WAITING_KEYFRAME -> {
        if (timestampUs < lastVideoUs) {
          actions.add(Action.Write(frame, Target.ACTIVE, timestampUs - activeBaseUs))
        } else {
          held.add(frame to timestampUs)
        }
      }
      State.DRAINING_AUDIO -> {
        if (timestampUs < splitUs) {
          actions.add(Action.Write(frame, Target.CLOSING, timestampUs - closingBaseUs))
        } else {
          actions.add(Action.FinishPrevious)
          actions.add(Action.Write(frame, Target.ACTIVE, timestampUs - activeBaseUs))
          state = State.IDLE
        }
      }
    }
    return actions
  }

  private fun flushHeldBefore(timestampUs: Long, actions: MutableList<Action<T>>) {
    val iterator = held.iterator()
    while (iterator.hasNext()) {
      val item = iterator.next()
      if (item.second < timestampUs) {
        actions.add(Action.Write(item.first, Target.ACTIVE, item.second - activeBaseUs))
        iterator.remove()
      }
    }
  }

  private fun splitAt(frame: T, keyTimestampUs: Long, actions: MutableList<Action<T>>) {
    flushHeldBefore(keyTimestampUs, actions)
    actions.add(Action.StartNext(keyTimestampUs))
    closingBaseUs = activeBaseUs
    activeBaseUs = keyTimestampUs
    actions.add(Action.Write(frame, Target.ACTIVE, 0))
    lastVideoUs = keyTimestampUs
    held.forEach { actions.add(Action.Write(it.first, Target.ACTIVE, it.second - keyTimestampUs)) }
    val audioAlreadyPastSplit = held.isNotEmpty()
    held.clear()
    if (!hasAudio || audioAlreadyPastSplit) {
      actions.add(Action.FinishPrevious)
      state = State.IDLE
    } else {
      state = State.DRAINING_AUDIO
    }
    splitUs = keyTimestampUs
  }
}
