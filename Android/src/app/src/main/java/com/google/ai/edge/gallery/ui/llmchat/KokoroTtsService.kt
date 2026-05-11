package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "KokoroTts"

/**
 * Kokoro TTS language/voice configuration.
 * Maps TutorLanguage to Kokoro lang_code and recommended voice names.
 */
enum class KokoroVoiceConfig(
  val langCode: String,
  val defaultVoice: String,
  val speed: Float,
) {
  EN_US("a", "af_heart", 1.0f),
  EN_GB("b", "bf_emma", 1.0f),
  ZH("z", "zf_xiaobei", 1.0f),
  JA("j", "jf_tebukuro", 1.0f),
  KO("k", "hf_alpha", 1.0f),
  ES("e", "ef_dora", 0.95f),
  FR("f", "bf_emma", 0.95f),
  DE("a", "am_adam", 0.95f),  // Kokoro uses en-us phonemizer for DE, works with German text
  IT("a", "am_adam", 0.95f),  // Same — misaki handles some Latin-alphabet languages
  PT("p", "pf_dora", 0.95f),
}

/**
 * TTS service that uses a Kokoro FastAPI server when available,
 * falling back to Android's built-in TextToSpeech when offline.
 *
 * Phase A: Server-side synthesis via HTTP (kokoro-fastapi :8880)
 * Phase B: On-device ONNX inference (future work)
 */
class KokoroTtsService(private val context: Context) {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private val _isSpeaking = MutableStateFlow(false)
  val isSpeaking = _isSpeaking.asStateFlow()

  private val _isServerAvailable = MutableStateFlow(false)
  val isServerAvailable = _isServerAvailable.asStateFlow()

  private var androidTts: TextToSpeech? = null
  private var androidTtsInitialized = false
  private var audioTrack: AudioTrack? = null

  /** Server configuration — override via build config or settings. */
  var serverUrl: String = "http://10.0.2.2:8880"  // Default: emulator localhost
    private set

  fun updateServerUrl(url: String) {
    serverUrl = url.trimEnd('/')
  }

  /** Initialize the service. Checks server availability and sets up Android fallback. */
  fun init() {
    initAndroidTts()
    serviceScope.launch { checkServerAvailability() }
  }

  private fun initAndroidTts() {
    if (androidTts != null) return
    androidTts = TextToSpeech(context.applicationContext, { status ->
      if (status == TextToSpeech.SUCCESS) {
        androidTtsInitialized = true
        Log.d(TAG, "Android TTS fallback initialized")
      } else {
        Log.e(TAG, "Android TTS fallback failed: status=$status")
      }
    })
  }

  /** Check if the Kokoro server is reachable. */
  suspend fun checkServerAvailability() {
    withContext(Dispatchers.IO) {
      try {
        val url = URL("$serverUrl/health")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        val available = conn.responseCode == 200
        _isServerAvailable.value = available
        Log.d(TAG, "Kokoro server at $serverUrl: available=$available")
      } catch (e: Exception) {
        _isServerAvailable.value = false
        Log.d(TAG, "Kokoro server unavailable: ${e.message}")
      }
    }
  }

  /**
   * Speak text aloud using Kokoro server if available, falling back to Android TTS.
   * @param text Text to speak
   * @param voiceConfig Kokoro voice configuration for language
   * @param androidLocale Fallback locale for Android TTS
   */
  suspend fun speak(
    text: String,
    voiceConfig: KokoroVoiceConfig,
    androidLocale: java.util.Locale,
  ) {
    if (text.isBlank()) return

    if (_isServerAvailable.value) {
      speakWithKokoro(text, voiceConfig)
    } else {
      speakWithAndroidTts(text, androidLocale)
    }
  }

  /**
   * Synthesize speech via Kokoro server and play through AudioTrack.
   * Kokoro returns raw PCM at 24kHz (16-bit mono).
   */
  private suspend fun speakWithKokoro(text: String, voiceConfig: KokoroVoiceConfig) {
    withContext(Dispatchers.IO) {
      try {
        _isSpeaking.value = true

        val url = URL("$serverUrl/v1/audio/speech")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "audio/wav")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 30000

        // Build request body matching OpenAI-compatible API
        val body = JSONObject().apply {
          put("model", "kokoro")
          put("input", text)
          put("voice", voiceConfig.defaultVoice)
          put("speed", voiceConfig.speed.toDouble())
          put("lang_code", voiceConfig.langCode)
          put("response_format", "wav")
        }

        conn.outputStream.use { os ->
          os.write(body.toString().toByteArray(Charsets.UTF_8))
        }

        if (conn.responseCode != 200) {
          val error = conn.errorStream?.bufferedReader()?.readText() ?: "unknown"
          Log.e(TAG, "Kokoro server error ${conn.responseCode}: $error")
          // Fall back to Android TTS on server error
          _isSpeaking.value = false
          val locale = when (voiceConfig) {
            KokoroVoiceConfig.ZH -> java.util.Locale("zh")
            KokoroVoiceConfig.JA -> java.util.Locale("ja")
            KokoroVoiceConfig.KO -> java.util.Locale("ko")
            KokoroVoiceConfig.ES -> java.util.Locale("es")
            KokoroVoiceConfig.FR -> java.util.Locale("fr")
            KokoroVoiceConfig.DE -> java.util.Locale("de")
            KokoroVoiceConfig.IT -> java.util.Locale("it")
            KokoroVoiceConfig.PT -> java.util.Locale("pt")
            else -> java.util.Locale("en", "US")
          }
          speakWithAndroidTts(text, locale)
          return@withContext
        }

        // Read WAV response and play
        val wavBytes = conn.inputStream.readBytes()

        // Parse WAV header to get sample rate and audio data
        val pcmData = parseWav(wavBytes)
        if (pcmData != null) {
          playPcm(pcmData.data, pcmData.sampleRate)
        } else {
          Log.e(TAG, "Failed to parse WAV response from Kokoro")
          _isSpeaking.value = false
        }
      } catch (e: Exception) {
        Log.e(TAG, "Kokoro TTS error: ${e.message}", e)
        _isSpeaking.value = false
      }
    }
  }

  /** Parse WAV bytes into PCM data + sample rate. */
  private fun parseWav(wavBytes: ByteArray): PcmData? {
    if (wavBytes.size < 44) return null
    // RIFF header check
    val riff = String(wavBytes, 0, 4, Charsets.US_ASCII)
    if (riff != "RIFF") return null

    // Find "data" chunk
    var offset = 12  // Skip RIFF header
    while (offset < wavBytes.size - 8) {
      val chunkId = String(wavBytes, offset, 4, Charsets.US_ASCII)
      val chunkSize = littleEndianInt(wavBytes, offset + 4)
      if (chunkId == "fmt ") {
        // Parse format chunk
        offset += 8
        // We already know sample rate from Kokoro (24kHz), skip detailed parsing
        offset += chunkSize
      } else if (chunkId == "data") {
        val dataOffset = offset + 8
        val dataSize = chunkSize

        // Get sample rate from fmt chunk (we need to find it first)
        // For Kokoro: always 24kHz 16-bit mono
        return PcmData(
          data = wavBytes.copyOfRange(dataOffset, minOf(dataOffset + dataSize, wavBytes.size)),
          sampleRate = 24000
        )
      } else {
        offset += 8 + chunkSize
      }
    }
    return null
  }

  private fun littleEndianInt(bytes: ByteArray, offset: Int): Int {
    return (bytes[offset].toInt() and 0xFF) or
      ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
      ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
      ((bytes[offset + 3].toInt() and 0xFF) shl 24)
  }

  /** Play PCM audio data through AudioTrack. */
  private fun playPcm(pcmData: ByteArray, sampleRate: Int) {
    stopPlayback()

    val bufSize = pcmData.size.coerceAtMost(AudioTrack.getMinBufferSize(
      sampleRate,
      AudioFormat.CHANNEL_OUT_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(pcmData.size))

    audioTrack = AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setSampleRate(sampleRate)
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build()
      )
      .setBufferSizeInBytes(bufSize)
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()

    audioTrack?.let { track ->
      track.play()
      // Write audio data in chunks for streaming feel
      val chunkSize = 4096
      var written = 0
      while (written < pcmData.size && !Thread.currentThread().isInterrupted) {
        val end = minOf(written + chunkSize, pcmData.size)
        track.write(pcmData, written, end - written)
        written = end
      }
      // Wait for playback to finish
      try {
        Thread.sleep((pcmData.size.toLong() / (sampleRate * 2) * 1000) + 200)
      } catch (_: InterruptedException) {}
      track.stop()
      _isSpeaking.value = false
    }
  }

  /** Speak text using Android's built-in TTS as fallback. */
  private fun speakWithAndroidTts(text: String, locale: java.util.Locale) {
    val engine = androidTts
    if (engine == null || !androidTtsInitialized) {
      Log.w(TAG, "Android TTS not initialized, cannot speak")
      _isSpeaking.value = false
      return
    }

    engine.setLanguage(locale)
    engine.setSpeechRate(0.9f) // Slightly slower for language learners

    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
      override fun onDone(utteranceId: String?) { _isSpeaking.value = false }
      override fun onError(utteranceId: String?) { _isSpeaking.value = false }
    })

    val utteranceId = "linglang_${System.currentTimeMillis()}"
    engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
  }

  /** Stop any ongoing playback. */
  fun stopSpeaking() {
    audioTrack?.let {
      try { it.stop() } catch (_: Exception) {}
      try { it.release() } catch (_: Exception) {}
    }
    audioTrack = null
    androidTts?.stop()
    _isSpeaking.value = false
  }

  private fun stopPlayback() {
    audioTrack?.let {
      try { it.stop() } catch (_: Exception) {}
      try { it.release() } catch (_: Exception) {}
    }
    audioTrack = null
  }

  /** Clean up all resources. */
  fun destroy() {
    stopPlayback()
    androidTts?.stop()
    androidTts?.shutdown()
    androidTts = null
    androidTtsInitialized = false
  }

  data class PcmData(val data: ByteArray, val sampleRate: Int)
}