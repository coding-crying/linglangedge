package com.google.ai.edge.gallery.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "StreamingTtsPlayer"

/**
 * Streaming TTS player that hooks into LLM token output.
 *
 * Flow:
 *   LLM tokens → onToken() → buffer into sentences
 *   → sentence queue → KokoroTTS.synthesize() on CPU
 *   → AudioTrack playback on each sentence chunk
 *
 * Audio starts playing as soon as the first sentence is synthesized,
 * while the LLM is still generating the rest.
 */
class StreamingTtsPlayer(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var kokoroTts: KokoroTTS? = null
    private val initLock = Object()

    private var voiceId: String = "en-US-heart-kokoro"
    private var speed: Float = 1.0f

    // Sentence buffering state
    private val tokenBuffer = StringBuilder()
    private val sentenceQueue = ConcurrentLinkedQueue<String>()
    private var isGenerating = false
    private var isPlaying = false

    // Audio playback
    private var audioTrack: AudioTrack? = null

    // Audio focus management
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    private val onFocusChange = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                // Another app took focus — pause playback gracefully
                stop()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Lower volume would be ideal but AudioTrack doesn't support
                // ducking easily; just keep playing at full volume for speech.
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
            }
        }
    }

    // TTS state observation
    private val _ttsState = MutableSharedFlow<TtsState>(replay = 1)
    val ttsState: SharedFlow<TtsState> = _ttsState

    sealed class TtsState {
        object Idle : TtsState()
        object Synthesizing : TtsState()
        object Playing : TtsState()
        data class Error(val message: String) : TtsState()
    }

    /**
     * Pre-load Kokoro TTS models on CPU.
     * Call this early (e.g. on screen load) so first synthesis is fast.
     */
    fun warmUp() {
        scope.launch {
            try {
                _ttsState.tryEmit(TtsState.Synthesizing)
                val tts = KokoroTTS(context)
                synchronized(initLock) {
                    kokoroTts = tts
                    initLock.notifyAll()
                }
                _ttsState.tryEmit(TtsState.Idle)
                Log.i(TAG, "KokoroTTS warmed up")
            } catch (e: Exception) {
                Log.e(TAG, "KokoroTTS warmup failed", e)
                _ttsState.tryEmit(TtsState.Error(e.message ?: "TTS init failed"))
            }
        }
    }

    fun setVoice(voiceId: String) {
        this.voiceId = voiceId
    }

    fun setSpeed(speed: Float) {
        this.speed = speed
    }

    /**
     * Feed a token from the LLM stream into the sentence buffer.
     * When a sentence boundary is detected (., !, ?), the sentence
     * is queued for TTS synthesis.
     */
    fun onToken(token: String) {
        if (!isGenerating) return
        tokenBuffer.append(token)

        // Check for sentence boundaries
        val text = tokenBuffer.toString()
        val sentenceEnd = findSentenceBoundary(text)
        if (sentenceEnd > 0) {
            val sentence = text.substring(0, sentenceEnd).trim()
            tokenBuffer.delete(0, sentenceEnd)
            if (sentence.isNotEmpty()) {
                sentenceQueue.add(sentence)
                if (!isPlaying) {
                    startPlaybackLoop()
                }
            }
        }
    }

    /**
     * Signal that LLM generation has started.
     * Flushes any previous state.
     */
    fun onGenerationStart() {
        isGenerating = true
        tokenBuffer.clear()
        sentenceQueue.clear()
        isPlaying = false
        stopAudioTrack()
    }

    /**
     * Signal that LLM generation has ended.
     * Flushes remaining buffered text as a final sentence.
     */
    fun onGenerationEnd() {
        isGenerating = false
        val remaining = tokenBuffer.toString().trim()
        tokenBuffer.clear()
        if (remaining.isNotEmpty()) {
            sentenceQueue.add(remaining)
            if (!isPlaying) {
                startPlaybackLoop()
            }
        }
    }

    /**
     * Stop all TTS and playback immediately.
     */
    fun stop() {
        isGenerating = false
        isPlaying = false
        tokenBuffer.clear()
        sentenceQueue.clear()
        stopAudioTrack()
        _ttsState.tryEmit(TtsState.Idle)
    }

    /**
     * Release all resources.
     */
    fun close() {
        stop()
        scope.cancel()
        synchronized(initLock) {
            kokoroTts?.close()
            kokoroTts = null
        }
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private fun findSentenceBoundary(text: String): Int {
        // Split on sentence AND clause boundaries so TTS starts speaking sooner.
        // Avoid splitting on abbreviations like "Mr." or "Dr." — only split after
        // punctuation followed by whitespace or end-of-string.
        val regex = Regex("""[.!?,:;—](?:\s+|$)""")
        val match = regex.find(text) ?: return 0
        return match.range.last + 1
    }

    private fun startPlaybackLoop() {
        isPlaying = true
        _ttsState.tryEmit(TtsState.Playing)
        requestAudioFocus()
        scope.launch {
            while (isActive && (sentenceQueue.isNotEmpty() || isGenerating)) {
                var sentence = sentenceQueue.poll()
                if (sentence == null) {
                    // Wait for more sentences or generation end
                    delay(50)
                    continue
                }
                // Merge short fragments (<15 chars) with the next queued chunk
                // to avoid TTS overhead on tiny audio clips like "Yes,"
                while (sentence.length < 15 && sentenceQueue.isNotEmpty()) {
                    sentence = "$sentence ${sentenceQueue.poll() ?: ""}"
                }

                try {
                    val tts = getTts() ?: continue
                    Log.d(TAG, "Synthesizing: \"$sentence\"")
                    val pcm = tts.synthesize(sentence, voiceId, speed)
                    playPcm(pcm)
                } catch (e: Exception) {
                    Log.e(TAG, "TTS synthesis failed for: \"$sentence\"", e)
                }
            }
            isPlaying = false
            _ttsState.tryEmit(TtsState.Idle)
            releaseAudioFocus()
            Log.d(TAG, "Playback loop ended")
        }
    }

    private suspend fun getTts(): KokoroTTS? {
        synchronized(initLock) {
            if (kokoroTts != null) return kokoroTts
        }
        // Wait for warmup
        return withTimeoutOrNull(10_000) {
            while (isActive) {
                synchronized(initLock) {
                    if (kokoroTts != null) return@withTimeoutOrNull kokoroTts
                    initLock.wait(500)
                }
            }
            null
        }
    }

    private fun playPcm(pcm: ShortArray) {
        if (pcm.isEmpty()) return

        // Create AudioTrack if needed (24kHz mono 16-bit)
        val track = ensureAudioTrack() ?: return

        // Start playback after first write to avoid silent buffer issues on some devices
        if (track.state == AudioTrack.STATE_INITIALIZED && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }

        // Write samples — blocks until consumed, giving natural backpressure
        val written = track.write(pcm, 0, pcm.size)
        if (written < 0) {
            Log.e(TAG, "AudioTrack.write failed: $written")
        }
    }

    @Suppress("DEPRECATION")
    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setOnAudioFocusChangeListener(onFocusChange)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                audioFocusRequest = request
                val result = audioManager.requestAudioFocus(request)
                hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                val result = audioManager.requestAudioFocus(
                    onFocusChange,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            Log.d(TAG, "Audio focus requested, granted=$hasAudioFocus")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request audio focus", e)
            // Proceed without focus — playback may still work
            hasAudioFocus = true
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseAudioFocus() {
        if (!hasAudioFocus) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { request ->
                    audioManager.abandonAudioFocusRequest(request)
                    audioFocusRequest = null
                }
            } else {
                audioManager.abandonAudioFocus(onFocusChange)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release audio focus", e)
        }
        hasAudioFocus = false
        Log.d(TAG, "Audio focus released")
    }

    private fun ensureAudioTrack(): AudioTrack? {
        if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) return audioTrack

        val bufSize = AudioTrack.getMinBufferSize(
            KOKORO_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(KOKORO_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // Don't call play() here — defer until first PCM data is written
        // to avoid silent-buffer issues on some Android devices
        audioTrack = track
        return track
    }

    private fun stopAudioTrack() {
        audioTrack?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        audioTrack = null
        releaseAudioFocus()
    }
}
