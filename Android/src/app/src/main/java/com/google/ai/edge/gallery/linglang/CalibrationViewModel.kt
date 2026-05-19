// SPDX-FileCopyrightText: 2025 LingLang
//
// SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.linglang

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================================
// Calibration Step Enum
// ============================================================================

/**
 * Steps in the 3-tier calibration flow.
 *
 * - SELECT_LEVEL:    Tier 1 — user picks a self-reported CEFR level (A1–C2).
 * - WORD_RECOGNITION: Tier 2 — rapid word-recognition test (~20 words, ~30s).
 * - SEEDING:         Internal — FSRS is being seeded from calibration results.
 * - COMPLETE:        Calibration finished; navigate to the tutor session.
 * - CONVERSATION:    Tier 3 placeholder (handled in the main tutor session, not here).
 */
enum class CalibrationStep {
    SELECT_LEVEL,
    WORD_RECOGNITION,
    SEEDING,
    COMPLETE,
    CONVERSATION,
}

// ============================================================================
// Per-word result from Tier 2
// ============================================================================

data class WordResult(
    /** The [CefrWord] shown to the user. */
    val word: CefrWord,
    /** true = user tapped "Know", false = user tapped "Don't Know". */
    val known: Boolean,
)

// ============================================================================
// UI State
// ============================================================================

data class CalibrationUiState(
    /** Which step of the calibration flow is active. */
    val step: CalibrationStep = CalibrationStep.SELECT_LEVEL,

    /** CEFR level selected in Tier 1. null until the user picks one. */
    val selectedLevel: CefrLevel? = null,

    /** Words sampled for the Tier 2 recognition test. */
    val wordList: List<CefrWord> = emptyList(),

    /** Index of the word currently displayed in Tier 2. */
    val currentIndex: Int = 0,

    /** Accumulated per-word results as the user progresses through Tier 2. */
    val results: List<WordResult> = emptyList(),

    /** Number of words the user marked as "known" so far. */
    val knownCount: Int = 0,

    /** Number of words the user marked as "don't know" so far. */
    val unknownCount: Int = 0,

    /** Total number of words to show in Tier 2 (default 20). */
    val totalWords: Int = CalibrationViewModel.DEFAULT_CALIBRATION_WORDS,

    /** Result from seeding, once complete. */
    val seedResult: ContextManager.CalibrationResult? = null,

    /** Error message if something goes wrong. */
    val error: String? = null,
)

// ============================================================================
// ViewModel
// ============================================================================

class CalibrationViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CalibrationViewModel"
        /** Default number of words shown in the rapid recognition round. */
        const val DEFAULT_CALIBRATION_WORDS = 20
    }

    // ---- Dependencies -------------------------------------------------------

    private val contextManager: ContextManager by lazy {
        ContextManager(application)
    }
    private val wordRepository: CefrWordRepository = CefrWordRepository.getInstance()

    // ---- State --------------------------------------------------------------

    private val _uiState = MutableStateFlow(CalibrationUiState())
    val uiState: StateFlow<CalibrationUiState> = _uiState.asStateFlow()

    // Convenience accessors for UI binding
    val calibrationStep: StateFlow<CalibrationStep> get() = _calibrationStep
    val selectedLevel: StateFlow<CefrLevel?> get() = _selectedLevel
    val wordList: StateFlow<List<CefrWord>> get() = _wordList
    val currentIndex: StateFlow<Int> get() = _currentIndex

    private val _calibrationStep = MutableStateFlow(CalibrationStep.SELECT_LEVEL)
    private val _selectedLevel = MutableStateFlow<CefrLevel?>(null)
    private val _wordList = MutableStateFlow<List<CefrWord>>(emptyList())
    private val _currentIndex = MutableStateFlow(0)

    // ---- User ID (defaults to "default"; set before starting if needed) ----

    var userId: String = "default"
    var targetLanguage: String = "ru"
    var nativeLanguage: String = "en"

    // ========================================================================
    // Tier 1: Level selection
    // ========================================================================

    /**
     * Called when the user selects a CEFR level in Tier 1.
     *
     * Immediately transitions to Tier 2, loading word lists in the background.
     * Words are drawn from levels ≤ selected level, sorted by frequency,
     * preferring words at the selected level.
     */
    fun selectLevel(level: CefrLevel) {
        _selectedLevel.value = level
        _calibrationStep.value = CalibrationStep.WORD_RECOGNITION

        _uiState.value = _uiState.value.copy(
            step = CalibrationStep.WORD_RECOGNITION,
            selectedLevel = level,
        )

        loadWordsForLevel(level)
    }

    /**
     * Load CEFR words for the rapid recognition round.
     *
     * Strategy: pick ~[DEFAULT_CALIBRATION_WORDS] words distributed across
     * levels up to and including the selected level, with heavier weight
     * toward the selected level itself. This gives a fair estimate of
     * whether the user really belongs at that level.
     */
    private fun loadWordsForLevel(level: CefrLevel) {
        viewModelScope.launch {
            try {
                val words = withContext(Dispatchers.IO) {
                    val allWords = wordRepository.loadForLanguage(
                        getApplication(), targetLanguage
                    )

                    if (allWords.isEmpty()) {
                        Log.w(TAG, "No CEFR words found for language=$targetLanguage")
                        return@withContext emptyList<CefrWord>()
                    }

                    // Select words from levels ≤ selected level, biased toward
                    // the selected level itself.
                    val eligibleLevels = CefrLevel.entries
                        .filter { it.ordinal <= level.ordinal }

                    // 60% of slots from the selected level, 40% spread across lower
                    val slotsFromSelected = (DEFAULT_CALIBRATION_WORDS * 0.6).toInt()
                    val slotsFromLower = DEFAULT_CALIBRATION_WORDS - slotsFromSelected

                    val selectedLevelWords = allWords
                        .filter { CefrLevel.fromCode(it.cefrLevel) == level }
                        .shuffled()
                        .sortedBy { it.frequencyRank }
                        .take(slotsFromSelected)

                    val lowerLevelWords = allWords
                        .filter {
                            CefrLevel.fromCode(it.cefrLevel) in eligibleLevels &&
                                CefrLevel.fromCode(it.cefrLevel) != level
                        }
                        .shuffled()
                        .sortedBy { it.frequencyRank }
                        .take(slotsFromLower)

                    // Combine, deduplicate by lemma, and shuffle so the user
                    // doesn't see a strict frequency ordering
                    (selectedLevelWords + lowerLevelWords)
                        .distinctBy { it.lemma }
                        .shuffled()
                        .take(DEFAULT_CALIBRATION_WORDS)
                }

                _wordList.value = words
                _currentIndex.value = 0

                _uiState.value = _uiState.value.copy(
                    wordList = words,
                    currentIndex = 0,
                    results = emptyList(),
                    knownCount = 0,
                    unknownCount = 0,
                    totalWords = words.size,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading calibration words", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to load words: ${e.message}"
                )
            }
        }
    }

    // ========================================================================
    // Tier 2: Word recognition
    // ========================================================================

    /**
     * Record the user's response for the current word and advance to the next.
     *
     * @param known true if the user tapped "Know", false if "Don't Know".
     */
    fun markWord(known: Boolean) {
        val currentWords = _wordList.value
        val currentIdx = _currentIndex.value

        if (currentIdx >= currentWords.size) return

        val word = currentWords[currentIdx]
        val result = WordResult(word = word, known = known)

        val currentResults = _uiState.value.results + result
        val newKnownCount = _uiState.value.knownCount + (if (known) 1 else 0)
        val newUnknownCount = _uiState.value.unknownCount + (if (!known) 1 else 0)
        val nextIndex = currentIdx + 1

        _currentIndex.value = nextIndex

        _uiState.value = _uiState.value.copy(
            currentIndex = nextIndex,
            results = currentResults,
            knownCount = newKnownCount,
            unknownCount = newUnknownCount,
        )

        // If all words are done, move to seeding
        if (nextIndex >= currentWords.size) {
            finishWordRecognition()
        }
    }

    /** Convenience: mark current word as known. */
    fun markKnown() = markWord(known = true)

    /** Convenience: mark current word as unknown. */
    fun markUnknown() = markWord(known = false)

    /**
     * Skip the word recognition phase entirely.
     * Useful if the user wants to skip and just seed from the selected level.
     */
    fun skipWordRecognition() {
        finishWordRecognition()
    }

    /**
     * Complete Tier 2 and transition to seeding phase.
     * Collects known lemmas from results and calls seedFromCalibration.
     */
    private fun finishWordRecognition() {
        _calibrationStep.value = CalibrationStep.SEEDING
        _uiState.value = _uiState.value.copy(step = CalibrationStep.SEEDING)

        val results = _uiState.value.results
        val level = _selectedLevel.value ?: CefrLevel.A1

        // Extract lemmas of words the user recognized
        val knownLemmas = results
            .filter { it.known }
            .map { it.word.lemma }

        viewModelScope.launch {
            try {
                val seedResult = withContext(Dispatchers.IO) {
                    contextManager.seedFromCalibration(
                        userId = userId,
                        language = targetLanguage,
                        knownLemmas = knownLemmas,
                        cefrLevelHint = level.code,
                    )
                }

                Log.d(
                    TAG,
                    "Calibration seeding complete: known=${seedResult.knownWordsSeeded}, " +
                        "new=${seedResult.newWordsSeeded}, total=${seedResult.totalWordsSeeded}"
                )

                _calibrationStep.value = CalibrationStep.COMPLETE

                _uiState.value = _uiState.value.copy(
                    step = CalibrationStep.COMPLETE,
                    seedResult = seedResult,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error seeding from calibration", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to seed vocabulary: ${e.message}"
                )
            }
        }
    }

    // ========================================================================
    // Tier 3: Conversation placeholder
    // ========================================================================

    /**
     * Transition to Tier 3 (calibration conversation).
     *
     * Note: Tier 3 is handled in the main tutor session, so this merely
     * advances the step flag so the UI can navigate to the tutor intent.
     * Call this after the UI has confirmed navigation to the tutor.
     */
    fun startConversationPhase() {
        _calibrationStep.value = CalibrationStep.CONVERSATION
        _uiState.value = _uiState.value.copy(step = CalibrationStep.CONVERSATION)
    }

    // ========================================================================
    // Reset / retry
    // ========================================================================

    /**
     * Reset calibration to the beginning.
     * Useful if the user wants to re-do calibration or changes target language.
     */
    fun reset() {
        _calibrationStep.value = CalibrationStep.SELECT_LEVEL
        _selectedLevel.value = null
        _wordList.value = emptyList()
        _currentIndex.value = 0

        _uiState.value = CalibrationUiState()
    }

    /**
     * Clear any error state and retry the current operation.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}