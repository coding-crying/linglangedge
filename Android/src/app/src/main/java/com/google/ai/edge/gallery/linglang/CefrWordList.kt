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

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

// ---------------------------------------------------------------------------
// Enum & Data Classes
// ---------------------------------------------------------------------------

enum class CefrLevel(val code: String) {
    A1("A1"),
    A2("A2"),
    B1("B1"),
    B2("B2"),
    C1("C1"),
    C2("C2");

    companion object {
        fun fromCode(code: String): CefrLevel =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: A1
    }
}

@Serializable
data class CefrWord(
    val word: String,
    val lemma: String,
    val pos: String,            // NOUN, VERB, ADJ, ADV, PREP, CONJ, PRON, DET, NUM, INTJ
    val cefrLevel: String,      // "A1" … "C2"
    val frequencyRank: Int,     // lower = more frequent
    val language: String,       // ISO 639-1, e.g. "ru"
    val gender: String? = null, // "masc" | "fem" | "neut" | null
    val definition: String,     // English definition
) {
    /** Stable identifier matching the schema-uni.ts `words` table lexeme id. */
    val lexemeId: String get() = "${language}_${lemma}_${pos.lowercase()}"
}

// ---------------------------------------------------------------------------
// Repository
// ---------------------------------------------------------------------------

class CefrWordRepository private constructor() {

    private val wordCache = mutableMapOf<String, List<CefrWord>>()

    private val json: Json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // ---- Loading ----------------------------------------------------------

    /**
     * Load words for a language + all CEFR levels.
     *
     * Looks for JSON files at `assets/cefr/{lang}_{level}.json`
     * (e.g. `assets/cefr/ru_a1.json`).
     *
     * Files that cannot be found or parsed are silently skipped so that
     * shipping a subset of levels is valid.
     */
    fun loadForLanguage(context: Context, languageCode: String): List<CefrWord> {
        val cacheKey = languageCode
        wordCache[cacheKey]?.let { return it }

        val allWords = mutableListOf<CefrWord>()

        for (level in CefrLevel.entries) {
            val filename = "cefr/${languageCode}_${level.code.lowercase()}.json"
            try {
                val jsonText = context.assets.open(filename).bufferedReader().use { it.readText() }
                val words: List<CefrWord> = json.decodeFromString(jsonText)
                allWords.addAll(words)
            } catch (_: Exception) {
                // File not present or malformed – skip
            }
        }

        wordCache[cacheKey] = allWords
        return allWords
    }

    private fun ensureLoaded(context: Context, languageCode: String): List<CefrWord> {
        val key = languageCode
        if (wordCache.containsKey(key)) return wordCache[key]!!
        return loadForLanguage(context, languageCode)
    }

    // ---- Word selection ---------------------------------------------------

    /**
     * Return the next [count] un-started words for [currentLevel],
     * ordered by frequency (most common first).
     *
     * @param startedLexemeIds  Set of lexeme IDs the learner has already begun.
     * @param count             Number of words to return.
     * @param currentLevel      The CEFR level to draw from; if exhausted, falls
     *                          back to the next level up.
     */
    fun getNextWords(
        context: Context,
        languageCode: String,
        startedLexemeIds: Set<String>,
        count: Int,
        currentLevel: CefrLevel = CefrLevel.A1,
    ): List<CefrWord> {
        val allWords = ensureLoaded(context, languageCode)

        // Try current level first; if fewer than `count` are available after
        // filtering, fall back to higher levels.
        val levels = CefrLevel.entries.dropWhile { it.ordinal < currentLevel.ordinal }

        val result = mutableListOf<CefrWord>()
        for (level in levels) {
            val candidates = allWords
                .filter { CefrLevel.fromCode(it.cefrLevel) == level }
                .filter { it.lexemeId !in startedLexemeIds }
                .sortedBy { it.frequencyRank }

            val remaining = count - result.size
            if (remaining <= 0) break

            result.addAll(candidates.take(remaining))
            if (result.size >= count) break
        }

        return result
    }

    // ---- Statistics -------------------------------------------------------

    /**
     * For each CEFR level, compute what fraction of its words the learner
     * has already started.
     *
     * @return Map from CEFR level code to completion ratio (0.0 – 1.0).
     */
    fun getCompletionStats(
        context: Context,
        languageCode: String,
        startedLexemeIds: Set<String>,
    ): Map<String, Float> {
        val allWords = ensureLoaded(context, languageCode)

        return CefrLevel.entries.associate { level ->
            val pool = allWords.filter { CefrLevel.fromCode(it.cefrLevel) == level }
            if (pool.isEmpty()) {
                level.code to 0f
            } else {
                val started = pool.count { it.lexemeId in startedLexemeIds }
                level.code to started.toFloat() / pool.size
            }
        }
    }

    /**
     * Decide whether the learner should graduate from [currentLevel]
     * to the next CEFR level.
     *
     * @param completedPercentage  Fraction of level words that must be started
     *                             (default 0.8 = 80 %).
     */
    fun shouldGraduate(
        context: Context,
        languageCode: String,
        startedLexemeIds: Set<String>,
        currentLevel: CefrLevel = CefrLevel.A1,
        completedPercentage: Float = 0.8f,
    ): Boolean {
        val stats = getCompletionStats(context, languageCode, startedLexemeIds)
        val completion = stats[currentLevel.code] ?: 0f
        return completion >= completedPercentage
    }

    // ---- Singleton --------------------------------------------------------

    companion object {
        @Volatile
        private var instance: CefrWordRepository? = null

        fun getInstance(): CefrWordRepository {
            return instance ?: synchronized(this) {
                instance ?: CefrWordRepository().also { instance = it }
            }
        }
    }
}