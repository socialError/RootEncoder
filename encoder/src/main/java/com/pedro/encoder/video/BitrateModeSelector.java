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

package com.pedro.encoder.video;

import android.media.MediaCodecInfo;

/**
 * Chooses the KEY_BITRATE_MODE applied to the video encoder. Pure logic, no Android calls.
 */
public final class BitrateModeSelector {

  private BitrateModeSelector() {
  }

  public static int select(int requestedMode, boolean requestedSupported, boolean cbrSupported) {
    int result;
    if (requestedMode != VideoEncoder.BITRATE_MODE_DEFAULT && requestedSupported) {
      result = requestedMode;
    } else if (cbrSupported) {
      result = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;
    } else {
      result = VideoEncoder.BITRATE_MODE_NOT_SET;
    }
    return result;
  }
}
