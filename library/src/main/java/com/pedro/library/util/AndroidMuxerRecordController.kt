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
package com.pedro.library.util

import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.pedro.common.AudioCodec
import com.pedro.common.TimeUtils
import com.pedro.common.VideoCodec
import com.pedro.common.clone
import com.pedro.common.frame.MediaFrame
import com.pedro.common.toMediaCodecBufferInfo
import com.pedro.library.base.recording.AsyncBaseRecordController
import com.pedro.library.base.recording.RecordController
import com.pedro.library.base.recording.RecordController.RecordTracks
import com.pedro.library.base.recording.RecordSwitchRouter
import com.pedro.library.base.recording.RecordSwitchRouter.Action
import com.pedro.library.base.recording.RecordSwitchRouter.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

/**
 * Created by pedro on 08/03/19.
 * Class to control video recording with MediaMuxer.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
open class AndroidMuxerRecordController : AsyncBaseRecordController() {
  private companion object {
    const val MUXER_JOB_WAIT_MS = 5000L
  }

  private var mediaMuxer: MediaMuxer? = null
  private var videoFormat: MediaFormat? = null
  private var audioFormat: MediaFormat? = null
  private val outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
  private var videoTrack: Int = -1
  private var audioTrack: Int = -1
  @Volatile
  private var currentPath: String? = null
  private var router = RecordSwitchRouter<MediaFrame>(true)
  @Volatile
  private var switchRequest: SwitchRequest? = null
  /** Set when stop gave up on a stalled muxer coroutine: its late switch steps only release what they hold. */
  @Volatile
  private var abandoned = false
  private var closing: ClosingFile? = null
  /** The last finalizer of a chain: each one waits for the previous, so the callbacks keep the order of the files. */
  @Volatile
  private var finalizerJob: Job? = null
  //a failing finalizer must not cancel the ones after it
  private val finalizerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** A switch asked by the user. routed and switchDelayUs are only touched by the muxer coroutine. */
  private class SwitchRequest(
    val muxer: MediaMuxer,
    val path: String,
    val videoTrack: Int,
    val audioTrack: Int,
    val listener: RecordController.SwitchListener,
    val requestTimeUs: Long
  ) {
    var routed = false
    var switchDelayUs = 0L
    var splitElapsedMs = 0L
  }

  private class ClosingFile(val muxer: MediaMuxer?, val path: String?, val videoTrack: Int, val audioTrack: Int)

  @Throws(IOException::class)
  override fun startRecordImp(
    path: String,
    listener: RecordController.Listener?,
    tracks: RecordTracks
  ) {
    if (getAudioCodec() != AudioCodec.AAC && getAudioCodec() != AudioCodec.HE_AAC) {
      throw IOException("Unsupported AudioCodec: " + getAudioCodec().name)
    }
    if (getVideoCodec() == VideoCodec.VP8 || getVideoCodec() == VideoCodec.VP9) {
      throw IOException("Unsupported VideoCodec: " + getVideoCodec().name)
    }
    mediaMuxer = MediaMuxer(path, outputFormat)
    currentPath = path
    resetSwitchState(tracks)
    if (tracks == RecordTracks.AUDIO && audioFormat != null) init()
  }

  @RequiresApi(api = Build.VERSION_CODES.O)
  @Throws(IOException::class)
  override fun startRecordImp(
    fd: FileDescriptor,
    listener: RecordController.Listener?,
    tracks: RecordTracks
  ) {
    if (getAudioCodec() != AudioCodec.AAC && getAudioCodec() != AudioCodec.HE_AAC) {
      throw IOException("Unsupported AudioCodec: " + getAudioCodec().name)
    }
    if (getVideoCodec() == VideoCodec.VP8 || getVideoCodec() == VideoCodec.VP9) {
      throw IOException("Unsupported VideoCodec: " + getVideoCodec().name)
    }
    mediaMuxer = MediaMuxer(fd, outputFormat)
    currentPath = null
    resetSwitchState(tracks)
    if (tracks == RecordTracks.AUDIO && audioFormat != null) init()
  }

  override fun stopRecordImp() {
    //the base class waits only 1 s for the muxer coroutine, the switch state is not safe to touch while it runs
    val muxerFinished = waitForUnfinishedMuxerJob()
    runBlocking { finalizerJob?.join() }
    finalizerJob = null
    if (muxerFinished) {
      finishSwitchOnStop()
    } else {
      abandoned = true
      listener?.onError(IllegalStateException("Muxer coroutine did not finish within ${MUXER_JOB_WAIT_MS}ms on stop, the switch state is left as it is"))
    }
    videoTrack = -1
    audioTrack = -1
    try {
      mediaMuxer?.stop()
    } catch (e: Exception) {
      listener?.onError(e)
    }
    releaseMuxer(mediaMuxer)
    mediaMuxer = null
    currentPath = null
  }

  override fun shouldCatchUpAudioOnStop(): Boolean =
    recordStatus == RecordController.Status.RECORDING && tracks == RecordTracks.ALL

  /** @return true when the muxer coroutine is done, so the switch state can be touched. */
  private fun waitForUnfinishedMuxerJob(): Boolean {
    val unfinished = unfinishedMuxerJob
    var finished = true
    if (unfinished != null) {
      Log.w(AsyncBaseRecordController.TAG, "muxer coroutine still running on stop, waiting for it")
      finished = runBlocking { withTimeoutOrNull(MUXER_JOB_WAIT_MS.milliseconds) { unfinished.join(); true } } == true
    }
    return finished
  }

  @Throws(IOException::class)
  override fun switchRecord(path: String, listener: RecordController.SwitchListener): Boolean {
    val video = videoFormat
    val audio = audioFormat
    val canSwitch = recordStatus == RecordController.Status.RECORDING && currentPath != null &&
        tracks != RecordTracks.AUDIO && switchRequest == null &&
        video != null && (audio != null || tracks == RecordTracks.VIDEO)
    if (canSwitch && video != null) {
      var muxer: MediaMuxer? = null
      try {
        muxer = MediaMuxer(path, outputFormat)
        val newVideoTrack = muxer.addTrack(video)
        val newAudioTrack = if (tracks == RecordTracks.VIDEO || audio == null) -1 else muxer.addTrack(audio)
        muxer.start()
        switchRequest = SwitchRequest(muxer, path, newVideoTrack, newAudioTrack, listener, TimeUtils.getCurrentTimeMicro())
      } catch (e: Exception) {
        releaseMuxer(muxer)
        File(path).delete()
        if (e is IOException || e is IllegalStateException) throw e
        throw IOException("Can't prepare the next record file", e)
      }
    }
    return canSwitch
  }

  override fun setVideoFormat(videoFormat: MediaFormat) {
    this.videoFormat = videoFormat
  }

  override fun setAudioFormat(audioFormat: MediaFormat) {
    this.audioFormat = audioFormat
    if (tracks == RecordTracks.AUDIO && recordStatus == RecordController.Status.STARTED) {
      init()
    }
  }

  override fun resetFormats() {
    videoFormat = null
    audioFormat = null
  }

  private fun resetSwitchState(tracks: RecordTracks) {
    router = RecordSwitchRouter(tracks == RecordTracks.ALL)
    abandoned = false
    switchRequest = null
    closing = null
  }

  /**
   * release() stops a started muxer first and that fails for a muxer without samples (a cancelled switch) or on a
   * full disk. Since Android 11 the muxer is stopped by then, so the second release frees it.
   *
   * @return the first exception release() threw, null when it went through
   */
  private fun releaseMuxer(muxer: MediaMuxer?): Exception? {
    var error: Exception? = null
    try {
      muxer?.release()
    } catch (e: Exception) {
      Log.w(AsyncBaseRecordController.TAG, "MediaMuxer release failed", e)
      error = e
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
          muxer?.release()
        } catch (second: Exception) {
          Log.w(AsyncBaseRecordController.TAG, "MediaMuxer second release failed", second)
        }
      }
    }
    return error
  }

  private fun init() {
    if (tracks != RecordTracks.VIDEO) audioTrack = mediaMuxer?.addTrack(audioFormat!!) ?: -1
    mediaMuxer?.start()
    recordStatus = RecordController.Status.RECORDING
    listener?.onStatusChange(recordStatus)
  }

  private suspend fun write(muxer: MediaMuxer?, track: Int, frame: MediaFrame, timestampUs: Long) {
    if (track == -1) return
    try {
      muxer?.writeSampleData(track, frame.data, frame.info.copy(timestamp = timestampUs).toMediaCodecBufferInfo())
      bitrateManager?.calculateBitrate(frame.info.size * 8L)
    } catch (e: Exception) {
      listener?.onError(e)
    }
  }

  private fun routeRequestedSwitch() {
    val request = switchRequest
    if (request != null && !request.routed && router.requestSwitch()) request.routed = true
  }

  private suspend fun handle(actions: List<Action<MediaFrame>>) {
    actions.forEach { action ->
      when (action) {
        is Action.Write -> {
          val closingFile = closing
          if (action.target == Target.ACTIVE) {
            write(mediaMuxer, if (action.frame.type == MediaFrame.Type.VIDEO) videoTrack else audioTrack, action.frame, action.timestampUs)
          } else if (closingFile != null) {
            write(closingFile.muxer, if (action.frame.type == MediaFrame.Type.VIDEO) closingFile.videoTrack else closingFile.audioTrack, action.frame, action.timestampUs)
          }
        }
        is Action.StartNext -> startNextFile(action.splitTimestampUs)
        is Action.FinishPrevious -> finishPreviousFile()
      }
    }
  }

  private fun startNextFile(splitTimestampUs: Long) {
    val request = switchRequest ?: return
    if (abandoned) {
      releaseMuxer(request.muxer)
      File(request.path).delete()
      switchRequest = null
      return
    }
    request.switchDelayUs = TimeUtils.getCurrentTimeMicro() - request.requestTimeUs
    request.splitElapsedMs = TimeUtils.getCurrentTimeMillis()
    closing = ClosingFile(mediaMuxer, currentPath, videoTrack, audioTrack)
    mediaMuxer = request.muxer
    videoTrack = request.videoTrack
    audioTrack = request.audioTrack
    currentPath = request.path
    Log.i(AsyncBaseRecordController.TAG, "record switched to ${request.path}, split at ${splitTimestampUs}us, delay ${request.switchDelayUs}us")
  }

  private fun finishPreviousFile() {
    val file = closing ?: return
    val request = switchRequest ?: return
    closing = null
    if (abandoned) {
      switchRequest = null
      releaseMuxer(file.muxer)
      return
    }
    //the request is done as soon as the router is idle, a new switch may be requested while this file is finished
    switchRequest = null
    val previous = finalizerJob
    finalizerJob = finalizerScope.launch {
      previous?.join()
      finishFile(file, request)
    }
  }

  private fun finishFile(file: ClosingFile, request: SwitchRequest) {
    if (abandoned) {
      releaseMuxer(file.muxer)
      return
    }
    var error: Exception? = null
    try {
      file.muxer?.stop()
    } catch (e: Exception) {
      error = e
    }
    val releaseError = releaseMuxer(file.muxer)
    if (error == null) error = releaseError
    try {
      if (abandoned) return
      request.listener.onRecordSwitched(file.path ?: "", request.path, request.switchDelayUs, request.splitElapsedMs, error)
    } catch (e: Exception) {
      Log.e(AsyncBaseRecordController.TAG, "switch listener failed", e)
    }
  }

  /** Called from stopRecordImp, after the muxer coroutine and the finalizers finished. */
  private fun finishSwitchOnStop() {
    val request = switchRequest ?: return
    val draining = closing
    if (draining != null) {
      closing = null
      switchRequest = null
      finishFile(draining, request)
    } else if (!request.routed || router.isWaitingForKeyFrame()) {
      runBlocking { handle(router.cancelSwitch()) }
      releaseMuxer(request.muxer)
      File(request.path).delete()
      Log.i(AsyncBaseRecordController.TAG, "record switch cancelled")
      switchRequest = null
    }
  }

  override suspend fun onWriteFrame(frame: MediaFrame) {
    when (frame.type) {
      MediaFrame.Type.VIDEO -> {
        if (recordStatus == RecordController.Status.STARTED && videoFormat != null && (audioFormat != null || tracks == RecordTracks.VIDEO)) {
          if (frame.info.isKeyFrame || isKeyFrame(frame.data)) {
            myRequestKeyFrame = null
            videoTrack = mediaMuxer?.addTrack(videoFormat!!) ?: -1
            //the first file starts at its first keyframe: the audio and video before it is dropped
            router.startAt(frame.info.timestamp)
            init()
          } else if (myRequestKeyFrame != null) {
            myRequestKeyFrame?.onRequestKeyFrame()
            myRequestKeyFrame = null
          }
        } else if (recordStatus == RecordController.Status.RESUMED && (frame.info.isKeyFrame
              || isKeyFrame(frame.data))
        ) {
          recordStatus = RecordController.Status.RECORDING
          listener?.onStatusChange(recordStatus)
        }
        if (recordStatus == RecordController.Status.RECORDING && tracks != RecordTracks.AUDIO) {
          routeRequestedSwitch()
          val isKey = router.isWaitingForKeyFrame() && (frame.info.isKeyFrame || isKeyFrame(frame.data))
          handle(router.onVideo(frame, frame.info.timestamp, isKey))
        }
      }
      MediaFrame.Type.AUDIO -> {
        if (recordStatus == RecordController.Status.RECORDING && tracks != RecordTracks.VIDEO) {
          routeRequestedSwitch()
          //the pooled buffer goes back to the pool right after this call, a held frame needs its own copy
          val audio = if (router.isWaitingForKeyFrame()) frame.copy(data = frame.data.clone()) else frame
          handle(router.onAudio(audio, frame.info.timestamp))
        }
      }
    }
  }
}