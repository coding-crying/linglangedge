package com.google.ai.edge.gallery.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.EnumSet

private const val STYLE_DIM = 256
private const val MAX_PHONEME_LENGTH = 510
const val KOKORO_SAMPLE_RATE = 24000

private const val TAG = "KokoroTTS"

fun splitSentences(text: String): List<String> {
    // Split on sentence boundaries (.!?) AND clause boundaries (,;:—)
    // so TTS starts speaking much sooner instead of waiting for a full sentence.
    val raw = text.split(Regex("(?<=[.!?,:;—])(?:\\s+|$)"))
    // Merge tiny fragments (<10 chars) back into the previous chunk to avoid
    // TTS overhead on very short audio clips. "Yes," → merge with next.
    val merged = mutableListOf<String>()
    var buf = ""
    for (chunk in raw.map { it.trim() }.filter { it.isNotEmpty() }) {
        if (buf.isEmpty()) {
            buf = chunk
        } else if (buf.length < 10) {
            buf = "$buf $chunk"
        } else {
            merged.add(buf)
            buf = chunk
        }
    }
    if (buf.isNotEmpty()) merged.add(buf)
    return merged
}

/**
 * Full Kokoro TTS pipeline.
 *
 * Usage:
 *   val tts = KokoroTTS(context)
 *   val pcm = tts.synthesize("Hello world", "en-US-heart")
 *   tts.close()
 */
class KokoroTTS(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val phonemizer: OpenPhonemizer
    private val session: OrtSession

    init {
        phonemizer = OpenPhonemizer(context, env)

        val modelFile = copyAssetToFile(context, "kokoro-quantized.onnx")
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Use NNAPI (GPU/NPU) on Android 10+ for faster inference
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                    Log.i(TAG, "Kokoro ONNX: NNAPI delegate enabled (FP16)")
                } catch (e: Exception) {
                    Log.w(TAG, "Kokoro ONNX: NNAPI not available, falling back to CPU", e)
                }
            }
        }
        session = env.createSession(modelFile.absolutePath, opts)
    }

    /**
     * Synthesize [text] using [voiceId] at [speed] (1.0 = normal).
     * Returns raw 16-bit PCM samples at [KOKORO_SAMPLE_RATE] Hz, mono.
     */
    fun synthesize(text: String, voiceId: String, speed: Float = 1.0f): ShortArray {
        val t0 = System.nanoTime()

        // 1. Phonemize
        val phonemes = phonemizer.phonemize(text)
        val tPhonemize = System.nanoTime()

        // 2. Tokenize phonemes (wraps with special token 0 on both ends)
        val tokens = OpenPhonemizerOutputTokenizer.encode(phonemes)

        // n_tokens is phoneme count without the two wrapping special tokens, capped at 509
        val nTokens = minOf(maxOf(tokens.size - 2, 0), MAX_PHONEME_LENGTH - 1)

        // 3. Load voice style embedding
        val styleData = loadVoiceStyle(voiceId, nTokens)
        val tPrep = System.nanoTime()

        // 4. Build ONNX inputs
        val inputIdsBuf = LongBuffer.wrap(LongArray(tokens.size) { tokens[it].toLong() })
        val inputIds = OnnxTensor.createTensor(env, inputIdsBuf, longArrayOf(1, tokens.size.toLong()))

        val styleBuf = FloatBuffer.wrap(styleData)
        val style = OnnxTensor.createTensor(env, styleBuf, longArrayOf(1, STYLE_DIM.toLong()))

        val speedBuf = FloatBuffer.wrap(floatArrayOf(speed))
        val speedTensor = OnnxTensor.createTensor(env, speedBuf, longArrayOf(1))

        // 5. Run inference
        val inputs = mapOf(
            "input_ids" to inputIds,
            "style"     to style,
            "speed"     to speedTensor,
        )
        val results = session.run(inputs)
        val tInference = System.nanoTime()

        // 6. Extract waveform — shape [1, N] or [N]
        val waveform: FloatArray = extractWaveform(results[0].value)

        inputIds.close()
        style.close()
        speedTensor.close()
        results.close()

        // 7. Convert float32 [-1, 1] → int16 PCM
        val pcm = FloatArray(waveform.size) { waveform[it] }.let { samples ->
            ShortArray(samples.size) { i ->
                (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
        }
        val tDone = System.nanoTime()

        val phonMs = (tPhonemize - t0) / 1_000_000
        val prepMs = (tPrep - tPhonemize) / 1_000_000
        val inferMs = (tInference - tPrep) / 1_000_000
        val totalMs = (tDone - t0) / 1_000_000
        val audioSec = pcm.size / KOKORO_SAMPLE_RATE.toFloat()
        Log.d(TAG, "synthesize(\"$text\"): phon=${phonMs}ms prep=${prepMs}ms infer=${inferMs}ms total=${totalMs}ms audio=${String.format("%.2f", audioSec)}s rtf=${String.format("%.2f", totalMs / (audioSec * 1000))}")

        return pcm
    }

    private fun loadVoiceStyle(voiceId: String, nTokens: Int): FloatArray {
        val assetPath = "voices/$voiceId.bin"
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        val floatCount = bytes.size / 4
        val allFloats = FloatArray(floatCount)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(allFloats)

        val offset = nTokens * STYLE_DIM
        return if (offset + STYLE_DIM <= floatCount) {
            allFloats.copyOfRange(offset, offset + STYLE_DIM)
        } else {
            // Fallback: use last available style vector
            val safeOffset = maxOf(0, floatCount - STYLE_DIM)
            allFloats.copyOfRange(safeOffset, safeOffset + STYLE_DIM)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractWaveform(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*>  -> {
            // Shape [1, N] → flatten
            val inner = (value as Array<FloatArray>)[0]
            inner
        }
        else -> FloatArray(0)
    }

    fun close() {
        phonemizer.close()
        session.close()
        // OrtEnvironment is a singleton — do not close it
    }
}