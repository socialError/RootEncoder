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

import kotlin.concurrent.Volatile

/**
 * Decides when a record that is being stopped has got all the audio of its video: the video end is fixed at the
 * stop request and the audio is written until it reaches that time. The audio encoder and the channel are
 * always a bit behind the video, and the encoder delivers in bursts, so audio that starts at or after the video
 * end is dropped instead of written. Pure logic, no Android dependency, so it can be unit tested.
 */
internal class StopAudioCatchUp {

  companion object {
    const val NO_TIMESTAMP = Long.MIN_VALUE
  }

  @Volatile
  private var videoClosed = false
  @Volatile
  private var lastVideoSentUs = NO_TIMESTAMP
  @Volatile
  private var targetUs = NO_TIMESTAMP
  @Volatile
  private var lastAudioWrittenUs = NO_TIMESTAMP
  @Volatile
  private var audioReachedTarget = false

  fun acceptsVideo(): Boolean = !videoClosed

  fun onVideoSent(timestampUs: Long) {
    lastVideoSentUs = timestampUs
  }

  /** @return false for audio that starts at or after the video end once the video is closed: it is not written. */
  fun acceptsAudio(timestampUs: Long): Boolean {
    var accepts = true
    if (videoClosed && targetUs != NO_TIMESTAMP && timestampUs >= targetUs) {
      audioReachedTarget = true
      accepts = false
    }
    return accepts
  }

  fun onAudioWritten(timestampUs: Long) {
    if (timestampUs > lastAudioWrittenUs) lastAudioWrittenUs = timestampUs
  }

  /** Stops accepting video. @return the audio time to wait for, NO_TIMESTAMP when no video was sent. */
  fun closeVideo(): Long {
    targetUs = lastVideoSentUs
    videoClosed = true
    return targetUs
  }

  fun isCaughtUp(): Boolean = targetUs == NO_TIMESTAMP || audioReachedTarget || lastAudioWrittenUs >= targetUs
}
