// SPDX-FileCopyrightText: 2025 LingLang
//
// SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.linglang

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ============================================================================
// Sync Status
// ============================================================================

enum class SyncStatus {
    @Json(name = "idle") IDLE,
    @Json(name = "syncing") SYNCING,
    @Json(name = "success") SUCCESS,
    @Json(name = "error") ERROR,
}

// ============================================================================
// Sync Request / Response
// ============================================================================

@JsonClass(generateAdapter = true)
data class SyncRequest(
    @Json(name = "userId") val userId: String,
    @Json(name = "tables") val tables: Map<String, List<SyncRow>>,  // tableName -> rows with lastModified
    @Json(name = "lastSyncTimestamps") val lastSyncTimestamps: Map<String, Long>,  // tableName -> lastSyncTime
)

@JsonClass(generateAdapter = true)
data class SyncResponse(
    @Json(name = "merged") val merged: Map<String, List<SyncRow>>,  // tableName -> merged rows to upsert
    @Json(name = "serverTimestamp") val serverTimestamp: Long,
)

// ============================================================================
// Generic sync row wrapper — each concrete row type is also a SyncRow
// ============================================================================

interface SyncRow {
    val lastModified: Long
}

// ============================================================================
// SyncVocabRow — mirrors user_vocabulary from schema-uni.ts
// ============================================================================

@JsonClass(generateAdapter = true)
data class SyncVocabRow(
    @Json(name = "id") val id: Int = 0,
    @Json(name = "user_id") val userId: String,
    @Json(name = "word_id") val wordId: Int,
    // FSRS v5 core
    @Json(name = "difficulty") val difficulty: Double = 0.0,
    @Json(name = "stability") val stability: Double = 0.0,
    @Json(name = "retrievability") val retrievability: Double = 0.0,
    // FSRS scheduling
    @Json(name = "next_review") val nextReview: Long = 0L,
    @Json(name = "reps") val reps: Int = 0,
    @Json(name = "lapses") val lapses: Int = 0,
    @Json(name = "elapsed_days") val elapsedDays: Int = 0,
    @Json(name = "scheduled_days") val scheduledDays: Int = 0,
    // Edge-specific signals
    @Json(name = "last_error_type") val lastErrorType: String? = null,
    @Json(name = "last_pronunciation_score") val lastPronunciationScore: Double? = null,
    @Json(name = "encounter_count") val encounterCount: Int = 0,
    // Context
    @Json(name = "language") val language: String,
    // State (maps from schema state field)
    @Json(name = "state") val state: Int = 0,
    // Sync
    @Json(name = "last_modified") override val lastModified: Long,
) : SyncRow

// ============================================================================
// SyncGoalRow — mirrors active_goals from schema-uni.ts
// ============================================================================

@JsonClass(generateAdapter = true)
data class SyncGoalRow(
    @Json(name = "id") val id: Int = 0,
    @Json(name = "user_id") val userId: String,
    @Json(name = "goal_type") val goalType: String,        // "remediation", "vocabulary", "grammar"
    @Json(name = "target_word_id") val targetWordId: Int? = null,
    @Json(name = "target_error_type") val targetErrorType: String? = null,
    @Json(name = "priority") val priority: Int = 5,
    @Json(name = "status") val status: String = "active",   // "active", "completed", "abandoned"
    @Json(name = "success_count") val successCount: Int = 0,
    @Json(name = "fail_count") val failCount: Int = 0,
    @Json(name = "required_successes") val requiredSuccesses: Int = 2,
    @Json(name = "language") val language: String,
    @Json(name = "created_at") val createdAt: Long,
    @Json(name = "last_modified") override val lastModified: Long,
) : SyncRow

// ============================================================================
// SyncNoteRow — mirrors user_notes from schema-uni.ts
// ============================================================================

@JsonClass(generateAdapter = true)
data class SyncNoteRow(
    @Json(name = "id") val id: Int = 0,
    @Json(name = "user_id") val userId: String,
    @Json(name = "language") val language: String,
    @Json(name = "category") val category: String,   // "weakness", "strength", "pattern", "tip"
    @Json(name = "content") val content: String,
    @Json(name = "superseded_by_id") val supersededById: Int? = null,
    @Json(name = "created_at") val createdAt: Long,
    @Json(name = "last_modified") override val lastModified: Long,
) : SyncRow

// ============================================================================
// SyncSummaryRow — mirrors session_summaries from schema-uni.ts
// ============================================================================

@JsonClass(generateAdapter = true)
data class SyncSummaryRow(
    @Json(name = "id") val id: Int = 0,
    @Json(name = "user_id") val userId: String,
    @Json(name = "language") val language: String,
    @Json(name = "topics") val topics: String? = null,
    @Json(name = "errors_summary") val errorsSummary: String? = null,
    @Json(name = "next_session_hint") val nextSessionHint: String? = null,
    @Json(name = "words_introduced") val wordsIntroduced: String? = null,
    @Json(name = "words_struggled") val wordsStruggled: String? = null,
    @Json(name = "session_start") val sessionStart: Long,
    @Json(name = "session_end") val sessionEnd: Long,
    @Json(name = "last_modified") override val lastModified: Long,
) : SyncRow

// ============================================================================
// SyncWordsRow — mirrors words table (static vocabulary data)
// ============================================================================

@JsonClass(generateAdapter = true)
data class SyncWordsRow(
    @Json(name = "id") val id: Int = 0,
    @Json(name = "word") val word: String,
    @Json(name = "language") val language: String,
    @Json(name = "cefr_level") val cefrLevel: String,
    @Json(name = "pos") val pos: String? = null,
    @Json(name = "lemma") val lemma: String? = null,
    @Json(name = "frequency_rank") val frequencyRank: Int? = null,
    @Json(name = "definition") val definition: String? = null,
    @Json(name = "last_modified") override val lastModified: Long,
) : SyncRow

// ============================================================================
// Auth models
// ============================================================================

@JsonClass(generateAdapter = true)
data class AuthRequest(
    @Json(name = "email") val email: String,
    @Json(name = "password") val password: String,
)

@JsonClass(generateAdapter = true)
data class AuthResponse(
    @Json(name = "token") val token: String,
    @Json(name = "userId") val userId: String,
)