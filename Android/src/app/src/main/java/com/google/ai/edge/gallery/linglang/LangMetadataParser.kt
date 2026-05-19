/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.linglang

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "LangMetadataParser"

// ---------------------------------------------------------------------------
// Data models
// ---------------------------------------------------------------------------

enum class Fluency {
    FLUID,
    HESITANT,
    PAUSED;

    companion object {
        /** Parse fluency from LLM JSON, defaulting to PAUSED on unknown values. */
        fun fromString(raw: String?): Fluency = when (raw?.trim()?.uppercase()) {
            "FLUID" -> FLUID
            "HESITANT" -> HESITANT
            else -> PAUSED
        }
    }
}

@Serializable
data class LangError(
    val word: String,
    val type: String,
    val expected: String,
    val got: String,
)

@Serializable
data class PronunciationScore(
    val word: String,
    val score: Float,   // 0.0 – 1.0
)

@Serializable
data class LangMetadata(
    @SerialName("words_used") val wordsUsed: List<String> = emptyList(),
    val errors: List<LangError> = emptyList(),
    val fluency: String = "paused",
    @SerialName("pronunciation_scores") val pronunciationScores: List<PronunciationScore> = emptyList(),
    @SerialName("new_words_encountered") val newWordsEncountered: List<String> = emptyList(),
)

/**
 * Parsed result of splitting a raw LLM response into its natural-text
 * portion (suitable for TTS) and the structured metadata block.
 */
data class ParsedResponse(
    /** Text outside the metadata tag – the portion read aloud by TTS. */
    val naturalText: String,
    /** Structured metadata extracted from the `<lang-metadata>` block. */
    val metadata: LangMetadata,
)

// ---------------------------------------------------------------------------
// VoicePerformance – bridges parsed errors into the FSRS grading subsystem
// ---------------------------------------------------------------------------

/**
 * Represents a single grading observation for the FSRS spaced-repetition
 * scheduler.  Each error the LLM detects is converted into a
 * [VoicePerformance] so the learner's recall of that lexeme can be
 * updated according to the difficulty of the error type.
 */
data class VoicePerformance(
    /** The surface-form the learner produced (or attempted). */
    val wordForm: String,
    /** High-level category – gender, agreement, case, tense, etc. */
    val errorType: String,
    /** The correct form expected by the target language. */
    val expected: String,
    /** What the learner actually said. */
    val got: String,
    /** Derived FSRS difficulty weight — harder error types get higher weight. */
    val weight: Float = 1.0f,
) {
    companion object {
        private const val TAG = "VoicePerformance"

        /** Map an error-type string to an FSRS difficulty weight. */
        fun weightForErrorType(type: String): Float = when (type.lowercase()) {
            "gender"       -> 1.4f
            "agreement"    -> 1.3f
            "case"         -> 1.5f
            "tense"        -> 1.2f
            "aspect"       -> 1.3f
            "conjugation"  -> 1.2f
            "pronunciation" -> 1.0f
            "word_order"   -> 1.1f
            else           -> 1.0f
        }

        /** Convert a [LangError] into a [VoicePerformance]. */
        fun fromLangError(error: LangError): VoicePerformance = VoicePerformance(
            wordForm  = error.word,
            errorType = error.type,
            expected  = error.expected,
            got       = error.got,
            weight    = weightForErrorType(error.type),
        )
    }
}

// ---------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------

object LangMetadataParser {

    private val METADATA_REGEX = Regex("""<lang-metadata>(.*?)</lang-metadata>""", RegexOption.DOT_MATCHES_ALL)

    /** Lenient JSON instance – the LLM may emit slightly broken JSON. */
    private val json: Json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * Parse a raw LLM response into [ParsedResponse].
     *
     * Handles:
     *  - Missing `<lang-metadata>` block → returns empty metadata.
     *  - Malformed JSON inside the block → returns empty metadata, logs warning.
     *  - Extra whitespace / newlines around the block.
     */
    fun parse(rawResponse: String): ParsedResponse {
        val match = METADATA_REGEX.find(rawResponse)

        // No metadata block at all — the entire response is natural text.
        if (match == null) {
            return ParsedResponse(
                naturalText = rawResponse.trim(),
                metadata = LangMetadata(),
            )
        }

        val metadataJson = match.groupValues[1].trim()
        val naturalText = rawResponse.replace(match.value, "").trim()

        val metadata: LangMetadata = try {
            json.decodeFromString<LangMetadata>(metadataJson).also {
                Log.d(TAG, "Parsed lang-metadata: wordsUsed=${it.wordsUsed}, errors=${it.errors.size}, " +
                    "fluency=${it.fluency}, pronunciationScores=${it.pronunciationScores.size}, " +
                    "newWordsEncountered=${it.newWordsEncountered.size}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse lang-metadata JSON, falling back to empty metadata", e)
            LangMetadata()
        }

        return ParsedResponse(naturalText = naturalText, metadata = metadata)
    }

    /**
     * Convenience: convert all errors in a [LangMetadata] into
     * [VoicePerformance] objects ready for FSRS grading.
     */
    fun toVoicePerformances(metadata: LangMetadata): List<VoicePerformance> =
        metadata.errors.map { VoicePerformance.fromLangError(it) }
}