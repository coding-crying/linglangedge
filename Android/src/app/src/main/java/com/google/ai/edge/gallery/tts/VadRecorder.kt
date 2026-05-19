package com.google.ai.edge.gallery.tts

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.konovalov.vad.webrtc.Vad
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream

private const val TAG = "VadRecorder"
private const val VAD_SAMPLE_RATE = 16000
private const val VAD_FRAME_SAMPLES = 480  // 30ms at 16kHz, must match FrameSize.FRAME_SIZE_480
private const val VAD_FRAME_BYTES = VAD_FRAME_SAMPLES * 2  // 16-bit PCM
private const val MAX_RECORDING_SECONDS = 30
private const val MAX_RECORDING_BYTES = VAD_SAMPLE_RATE * 2 * MAX_RECORDING_SECONDS
private const val SILENCE_FRAMES_TO_STOP = 30  // ~0.9s of continuous silence stops recording
private const val SPEECH_FRAMES_TO_START = 3    // ~90ms of speech to start

/**
 * Voice Activity Detection recorder. Automatically detects when the user starts
 * and stops speaking, eliminating the need for a manual record/stop button.
 *
 * Usage:
 *   val recorder = VadRecorder()
 *   recorder.start(context) { audioData -> ... }
 *   // ... later ...
 *   recorder.stop()
 */
class VadRecorder {
    private var vad: VadWebRTC? = null
    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var isSpeechDetected = false
    private var silenceFrameCount = 0
    private var speechFrameCount = 0
    private val speechBuffer = ByteArrayOutputStream()

    /** Start listening with VAD. onSpeechComplete is called with 16kHz 16-bit PCM when speech ends. */
    fun start(onSpeechComplete: (ByteArray) -> Unit) {
        stop() // Clean up any previous session

        vad = Vad.builder()
            .setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setFrameSize(FrameSize.FRAME_SIZE_480)
            .setMode(Mode.AGGRESSIVE)
            .setSpeechDurationMs(90)
            .setSilenceDurationMs(900)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(
            VAD_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            VAD_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(bufferSize, VAD_FRAME_BYTES * 4)
        )
        audioRecord = record

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            return
        }

        isSpeechDetected = false
        silenceFrameCount = 0
        speechFrameCount = 0
        speechBuffer.reset()

        record.startRecording()
        recordJob = scope.launch {
            val frameBuf = ShortArray(VAD_FRAME_SAMPLES)
            while (isActive) {
                val read = record.read(frameBuf, 0, VAD_FRAME_SAMPLES)
                if (read <= 0) continue

                val isSpeech = vad?.isSpeech(frameBuf.copyOf(read)) ?: false

                if (isSpeech) {
                    speechFrameCount++
                    silenceFrameCount = 0
                    if (speechFrameCount >= SPEECH_FRAMES_TO_START) {
                        isSpeechDetected = true
                    }
                    if (isSpeechDetected) {
                        // Write PCM bytes to buffer
                        for (s in frameBuf) {
                            speechBuffer.write(s.toInt() and 0xFF)
                            speechBuffer.write((s.toInt() shr 8) and 0xFF)
                        }
                    }
                } else {
                    silenceFrameCount++
                    speechFrameCount = 0
                    if (isSpeechDetected) {
                        // Write non-speech frame too (natural pause, not end of utterance)
                        for (s in frameBuf) {
                            speechBuffer.write(s.toInt() and 0xFF)
                            speechBuffer.write((s.toInt() shr 8) and 0xFF)
                        }
                        if (silenceFrameCount >= SILENCE_FRAMES_TO_STOP) {
                            // End of utterance
                            val audioData = speechBuffer.toByteArray()
                            if (audioData.size > VAD_FRAME_BYTES * 2) {  // At least 2 frames
                                withContext(Dispatchers.Main) {
                                    onSpeechComplete(audioData)
                                }
                            }
                            // Reset for next utterance
                            isSpeechDetected = false
                            silenceFrameCount = 0
                            speechBuffer.reset()
                        }
                    }
                }

                // Safety: stop if recording gets too long
                if (speechBuffer.size() >= MAX_RECORDING_BYTES) {
                    val audioData = speechBuffer.toByteArray()
                    withContext(Dispatchers.Main) {
                        onSpeechComplete(audioData)
                    }
                    isSpeechDetected = false
                    silenceFrameCount = 0
                    speechBuffer.reset()
                }
            }
        }
    }

    /** Stop listening and release resources. */
    fun stop() {
        recordJob?.cancel()
        recordJob = null
        try { audioRecord?.stop() } catch (_: IllegalStateException) {}
        audioRecord?.release()
        audioRecord = null
        vad?.close()
        vad = null
        isSpeechDetected = false
        speechBuffer.reset()
    }

    /** Whether VAD has detected active speech. */
    fun isSpeaking(): Boolean = isSpeechDetected
}
