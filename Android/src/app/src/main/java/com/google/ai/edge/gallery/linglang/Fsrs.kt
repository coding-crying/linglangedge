// SPDX-FileCopyrightText: 2025 LingLang
//
// SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.linglang

/**
 * FSRS (Free Spaced Repetition Scheduler) implementation for LingLangEdge.
 *
 * Ports the algorithm from agents/src/lib/fsrs.ts.
 * Maps voice conversation performance to FSRS grades (1-4) and applies
 * the FSRS v5 algorithm to compute new stability/difficulty/due date.
 *
 * All timestamps are epoch seconds (Long).
 */
object Fsrs {

    // ========================================================================
    // Types
    // ========================================================================

    data class FSRSCard(
        val state: Int,         // 0=New, 1=Learning, 2=Review, 3=Relearning
        val difficulty: Double,
        val stability: Double,
        val elapsedDays: Int,
        val scheduledDays: Int,
        val reps: Int,
        val lapses: Int,
    )

    data class FSRSResult(
        val state: Int,
        val difficulty: Double,
        val stability: Double,
        val scheduledDays: Int,
        val dueEpochSeconds: Long,   // Computed from now + scheduledDays
        val reps: Int,
        val lapses: Int,
    )

    data class FSRSParams(
        val requestRetention: Double = 0.9,
        val maximumInterval: Int = 365,
        val w0: Double = 0.4872,
        val w1: Double = 0.5818,
        val w2: Double = 1.3017,
        val w3: Double = 3.4296,
        val w4: Double = 1.2145,
        val w5: Double = 0.0492,
        val w6: Double = 1.0526,
        val w7: Double = 0.0335,
        val w8: Double = 1.0166,
        val w9: Double = 1.2074,
        val w10: Double = 0.0,
        val w11: Double = 1.6073,
        val w12: Double = 0.1477,
        val w13: Double = 1.0048,
        val w14: Double = 0.0,
        val w15: Double = 0.2987,
        val w16: Double = 0.3907,
        val w17: Double = 0.6354,
    )

    val DEFAULT_PARAMS = FSRSParams()

    // ========================================================================
    // Core FSRS Algorithm
    // ========================================================================

    /**
     * Apply an FSRS review to a card and return the updated state.
     * Grade: 1=Again, 2=Hard, 3=Good, 4=Easy
     */
    fun fsrsReview(card: FSRSCard, grade: Int, params: FSRSParams = DEFAULT_PARAMS): FSRSResult {
        val nowEpochSeconds = System.currentTimeMillis() / 1000

        if (card.state == 0) {
            // New card — initialize from parameters
            return initNewCard(grade, nowEpochSeconds, params)
        }

        val elapsedDays = card.elapsedDays.coerceAtLeast(0)
        val stability = card.stability.coerceAtLeast(0.01)
        val retrievability = Math.pow(1.0 + (elapsedDays.toDouble() / stability) * params.w13, -1.0)

        var newDifficulty = constrainDifficulty(card.difficulty - params.w5 * (grade - 3))
        // Mean reversion toward D0(4)
        newDifficulty = constrainDifficulty((1 - params.w6) * params.w4 + params.w6 * newDifficulty)

        var newStability: Double
        var newState: Int
        var newLapses = card.lapses

        if (grade == 1) {
            // Again — lapse
            newState = if (card.state == 2) 3 else 1
            newStability = Math.max(0.01, card.stability * Math.pow(params.w10, (card.lapses + 1).toDouble()))
            newLapses = card.lapses + 1
            val step = if (newState == 3) params.w16 else params.w15
            val scheduledDays = Math.round(step).toInt()
            val dueEpoch = nowEpochSeconds + (scheduledDays.toLong() * 86400)

            return FSRSResult(
                state = newState,
                difficulty = newDifficulty,
                stability = newStability,
                scheduledDays = scheduledDays,
                dueEpochSeconds = dueEpoch,
                reps = card.reps + 1,
                lapses = newLapses,
            )
        }

        // Successful review — calculate new stability
        if (card.state == 1 || card.state == 3) {
            // Learning / Relearning → graduating
            val initStab = when (grade) {
                2 -> params.w1   // Hard
                3 -> params.w2   // Good
                else -> params.w3 // Easy
            }
            newStability = initStab
        } else {
            // Review — apply stability formula
            val hardPenalty = if (grade == 2) params.w8 else 1.0
            val easyBonus = if (grade == 4) params.w9 else 1.0
            newStability = card.stability * (
                1 + Math.exp(params.w11) *
                (11 - newDifficulty) *
                Math.pow(card.stability, -params.w12) *
                (Math.exp((1 - retrievability) * params.w13) - 1) *
                hardPenalty * easyBonus
            )
        }

        newState = 2 // Review
        val interval = nextInterval(newStability, params)
        val scheduledDays = Math.round(interval).toInt().coerceAtLeast(1)
        val dueEpoch = nowEpochSeconds + (scheduledDays.toLong() * 86400)

        return FSRSResult(
            state = newState,
            difficulty = newDifficulty,
            stability = newStability,
            scheduledDays = scheduledDays,
            dueEpochSeconds = dueEpoch,
            reps = card.reps + 1,
            lapses = card.lapses,
        )
    }

    private fun initNewCard(grade: Int, nowEpochSeconds: Long, params: FSRSParams): FSRSResult {
        val initialStability = when (grade) {
            1 -> params.w0
            2 -> params.w1
            3 -> params.w2
            else -> params.w3
        }
        val stability = Math.max(0.01, initialStability)
        val difficulty = constrainDifficulty(params.w4 - params.w5 * (grade - 3))

        if (grade == 1) {
            // Again on new — still learning, short step
            val scheduledDays = 1
            val dueEpoch = nowEpochSeconds + (params.w15 * 86400).toLong()
            return FSRSResult(
                state = 1,
                difficulty = difficulty,
                stability = stability,
                scheduledDays = scheduledDays,
                dueEpochSeconds = dueEpoch,
                reps = 1,
                lapses = 0,
            )
        }

        // Hard/Good/Easy on new card — graduate to review
        val interval = nextInterval(stability, params)
        val scheduledDays = Math.round(interval).toInt().coerceAtLeast(1)
        val dueEpoch = nowEpochSeconds + (scheduledDays.toLong() * 86400)

        return FSRSResult(
            state = 2,
            difficulty = difficulty,
            stability = stability,
            scheduledDays = scheduledDays,
            dueEpochSeconds = dueEpoch,
            reps = 1,
            lapses = 0,
        )
    }

    private fun nextInterval(stability: Double, params: FSRSParams): Double {
        val interval = stability * (1.0 / params.requestRetention - 1.0) / params.w13
        return Math.min(Math.max(1.0, interval), params.maximumInterval.toDouble())
    }

    private fun constrainDifficulty(d: Double): Double {
        return Math.max(1.0, Math.min(10.0, d))
    }

    // ========================================================================
    // Voice-to-FSRS grade mapping
    // ========================================================================

    /**
     * Map voice conversation performance to FSRS grade (1-4).
     *
     * Grade 1 (Again): Completely wrong, or failed even after direct correction.
     * Grade 2 (Hard):  Got it but only after nudge, or with poor pronunciation/long latency.
     * Grade 3 (Good):  Natural use at escalation level 1 — the default pass.
     * Grade 4 (Easy):  Unprompted use with fluent pronunciation.
     */
    fun voiceToGrade(performance: String, escalationLevel: Int = 1, pronunciationScore: Double? = null, durationMs: Long? = null, unprompted: Boolean = false): Int {
        return when (performance) {
            "recall_fail", "wrong_use", "native_substitution" -> {
                // Native substitution = recall failure
                if (escalationLevel == 3) 1 else 1
            }
            "scaffolded" -> 2  // Correct but prompted
            "correct_use" -> {
                when {
                    unprompted && (pronunciationScore ?: 1.0) > 0.8 -> 4  // Easy
                    escalationLevel == 2 -> 2  // Needed a nudge
                    (pronunciationScore ?: 1.0) < 0.5 -> 2  // Poor pronunciation
                    (durationMs ?: 0L) > 3000L -> 2  // Long latency
                    else -> 3  // Good — the default
                }
            }
            else -> 3  // Default to Good
        }
    }
}