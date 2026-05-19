// SPDX-FileCopyrightText: 2025 LingLang
//
// SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.linglang

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * ContextManager — SQLite DB layer for LingLangEdge.
 *
 * Ports essential logic from agents/src/lib/context.ts, targeting SQLite on-device.
 * Tables follow schema-uni.ts field names with timestamps as epoch Longs.
 *
 * Requires: Fsrs.kt (fsrsReview, voiceToGrade, FSRSGrade, FSRSCard, FSRSResult)
 *           CefrWordList.kt (CefrWordRepository for word candidates)
 */
class ContextManager(private val appContext: Context) : SQLiteOpenHelper(
    appContext, DATABASE_NAME, null, DATABASE_VERSION
) {

    companion object {
        private const val TAG = "ContextManager"
        private const val DATABASE_NAME = "linglang.db"
        private const val DATABASE_VERSION = 1

        // Table names (matching schema-uni.ts)
        const val TABLE_USERS = "users"
        const val TABLE_WORDS = "words"
        const val TABLE_USER_VOCABULARY = "user_vocabulary"
        const val TABLE_REVIEW_LOGS = "review_logs"
        const val TABLE_ACTIVE_GOALS = "active_goals"
        const val TABLE_SESSION_SUMMARIES = "session_summaries"
        const val TABLE_USER_NOTES = "user_notes"
        const val TABLE_CEFR_WORD_LISTS = "cefr_word_lists"

        // Note categories from context.ts
        val NOTE_CATEGORIES = listOf("preference", "level", "frustration", "goal", "engagement")
        const val MAX_NOTES_PER_CATEGORY = 3

        // Function POS to skip for remediation goals
        val FUNCTION_POS = setOf("PRON", "CONJ", "PREP", "DET", "ART", "NUM", "INTJ")

        const val MAX_ACTIVE_GOALS = 3
    }

    // ========================================================================
    // SQLiteOpenHelper — schema creation
    // ========================================================================

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_USERS (
                id TEXT PRIMARY KEY,
                native_language TEXT NOT NULL DEFAULT 'en',
                target_language TEXT NOT NULL DEFAULT 'ru',
                proficiency_level TEXT NOT NULL DEFAULT 'A1',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_WORDS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word TEXT NOT NULL,
                language TEXT NOT NULL,
                cefr_level TEXT NOT NULL,
                pos TEXT,
                lemma TEXT,
                frequency_rank INTEGER,
                definition TEXT,
                last_modified INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_USER_VOCABULARY (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT NOT NULL,
                word_id INTEGER NOT NULL,
                difficulty REAL NOT NULL DEFAULT 0,
                stability REAL NOT NULL DEFAULT 0,
                retrievability REAL NOT NULL DEFAULT 0,
                next_review INTEGER NOT NULL,
                reps INTEGER NOT NULL DEFAULT 0,
                lapses INTEGER NOT NULL DEFAULT 0,
                elapsed_days INTEGER NOT NULL DEFAULT 0,
                scheduled_days INTEGER NOT NULL DEFAULT 0,
                last_error_type TEXT,
                last_pronunciation_score REAL,
                encounter_count INTEGER NOT NULL DEFAULT 0,
                language TEXT NOT NULL,
                state INTEGER NOT NULL DEFAULT 0,
                native_substitution_count INTEGER NOT NULL DEFAULT 0,
                last_review INTEGER,
                created_at INTEGER NOT NULL DEFAULT (strftime('%s','now')),
                last_modified INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_REVIEW_LOGS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT NOT NULL,
                word_id INTEGER NOT NULL,
                rating INTEGER NOT NULL,
                state TEXT NOT NULL,
                elapsed_days INTEGER NOT NULL,
                scheduled_days INTEGER NOT NULL,
                error_type TEXT,
                pronunciation_score REAL,
                cefr_level TEXT,
                session_type TEXT DEFAULT 'conversation',
                review_time INTEGER NOT NULL,
                last_modified INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_ACTIVE_GOALS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT NOT NULL,
                goal_type TEXT NOT NULL,
                target_word_id INTEGER,
                target_error_type TEXT,
                priority INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'active',
                success_count INTEGER NOT NULL DEFAULT 0,
                fail_count INTEGER NOT NULL DEFAULT 0,
                required_successes INTEGER NOT NULL DEFAULT 2,
                language TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                last_modified INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SESSION_SUMMARIES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT NOT NULL,
                language TEXT NOT NULL,
                topics TEXT,
                errors_summary TEXT,
                next_session_hint TEXT,
                words_introduced TEXT,
                words_struggled TEXT,
                session_start INTEGER NOT NULL,
                session_end INTEGER NOT NULL,
                last_modified INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_USER_NOTES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT NOT NULL,
                language TEXT NOT NULL,
                category TEXT NOT NULL,
                content TEXT NOT NULL,
                superseded_by_id INTEGER,
                created_at INTEGER NOT NULL,
                last_modified INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_CEFR_WORD_LISTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                language TEXT NOT NULL,
                cefr_level TEXT NOT NULL,
                word TEXT NOT NULL,
                lemma TEXT,
                pos TEXT,
                definition TEXT,
                frequency_rank INTEGER
            )
        """.trimIndent())

        // Indexes
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_uv_user_lang ON $TABLE_USER_VOCABULARY(user_id, language)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_uv_next_review ON $TABLE_USER_VOCABULARY(user_id, next_review)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_uv_last_modified ON $TABLE_USER_VOCABULARY(user_id, last_modified)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_goals_user_status ON $TABLE_ACTIVE_GOALS(user_id, status, priority)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_user_category ON $TABLE_USER_NOTES(user_id, category)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_summaries_user ON $TABLE_SESSION_SUMMARIES(user_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_words_lang_freq ON $TABLE_WORDS(language, frequency_rank)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations go here
        if (oldVersion < 2) {
            // Example: db.execSQL("ALTER TABLE ...")
        }
    }

    // ========================================================================
    // Helper: readable/writable convenience
    // ========================================================================

    private fun now(): Long = System.currentTimeMillis() / 1000

    // ========================================================================
    // getInitialContext — Fetch due reviews, new word candidates, isNewUser flag
    // ========================================================================

    data class InitialContext(
        val userId: String,
        val targetLanguage: String,
        val nativeLanguage: String,
        val isNewUser: Boolean,
        val reviewList: String,
        val newWords: String,
    )

    fun getInitialContext(userId: String): InitialContext {
        val db = readableDatabase
        val nowEpoch = now()

        // 1. Get user target/native language
        val userCursor = db.query(
            TABLE_USERS,
            arrayOf("target_language", "native_language"),
            "id = ?",
            arrayOf(userId),
            null, null, null
        )

        var targetLang = "ru"
        var nativeLang = "en"
        userCursor.use { cursor ->
            if (cursor.moveToFirst()) {
                targetLang = cursor.getString(0) ?: "ru"
                nativeLang = cursor.getString(1) ?: "en"
            }
        }

        // 2. Fetch due reviews (top 5 target-language)
        val dueCursor = db.rawQuery(
            """
            SELECT w.lemma, w.word, w.definition, uv.next_review
            FROM $TABLE_USER_VOCABULARY uv
            JOIN $TABLE_WORDS w ON uv.word_id = w.id
            WHERE uv.user_id = ? AND uv.language = ? AND uv.next_review <= ?
            ORDER BY uv.next_review ASC
            LIMIT 20
            """.trimIndent(),
            arrayOf(userId, targetLang, nowEpoch.toString())
        )

        val reviewItems = mutableListOf<String>()
        dueCursor.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < 5) {
                val lemma = cursor.getString(0) ?: cursor.getString(1)
                val translation = cursor.getString(2) ?: ""
                reviewItems.add("$lemma ($translation)")
                count++
            }
        }
        val reviewList = reviewItems.ifEmpty { listOf("None") }.joinToString(", ")

        // 3. New word candidates — top 3 by frequency not yet started (CEFR word list)
        val startedIds = mutableSetOf<Int>()
        val startedCursor = db.query(
            TABLE_USER_VOCABULARY,
            arrayOf("word_id"),
            "user_id = ?",
            arrayOf(userId),
            null, null, null
        )
        startedCursor.use { cursor ->
            while (cursor.moveToNext()) {
                startedIds.add(cursor.getInt(0))
            }
        }

        // Also exclude words already in active goals
        val activeGoalCursor = db.query(
            TABLE_ACTIVE_GOALS,
            arrayOf("target_word_id"),
            "user_id = ? AND status = ?",
            arrayOf(userId, "active"),
            null, null, null
        )
        val activeGoalIds = mutableSetOf<Int>()
        activeGoalCursor.use { cursor ->
            while (cursor.moveToNext()) {
                val tid = cursor.getInt(0)
                if (tid > 0) activeGoalIds.add(tid)
            }
        }

        val excludeIds = startedIds + activeGoalIds
        val excludePlaceholders = excludeIds.take(999).joinToString(",")

        // Try CEFR word lists first, then words table
        val newWordsList = mutableListOf<String>()
        if (excludeIds.isNotEmpty()) {
            val cefrCursor = db.rawQuery(
                """
                SELECT lemma, word, definition FROM $TABLE_CEFR_WORD_LISTS
                WHERE language = ? AND id NOT IN ($excludePlaceholders)
                ORDER BY frequency_rank ASC
                LIMIT 3
                """.trimIndent(),
                arrayOf(targetLang)
            )
            cefrCursor.use { cursor ->
                while (cursor.moveToNext() && newWordsList.size < 3) {
                    val lemma = cursor.getString(0) ?: cursor.getString(1)
                    val definition = cursor.getString(2) ?: ""
                    newWordsList.add("$lemma ($definition)")
                }
            }
        }
        // Fallback: try words table if CEFR didn't fill enough
        if (newWordsList.size < 3) {
            val wordsQuery = if (excludeIds.isNotEmpty()) {
                """
                SELECT lemma, word, definition FROM $TABLE_WORDS
                WHERE language = ? AND id NOT IN ($excludePlaceholders)
                ORDER BY frequency_rank ASC
                LIMIT 3
                """.trimIndent()
            } else {
                """
                SELECT lemma, word, definition FROM $TABLE_WORDS
                WHERE language = ?
                ORDER BY frequency_rank ASC
                LIMIT 3
                """.trimIndent()
            }
            val wordsCursor = db.rawQuery(wordsQuery, arrayOf(targetLang))
            wordsCursor.use { cursor ->
                while (cursor.moveToNext() && newWordsList.size < 3) {
                    val lemma = cursor.getString(0) ?: cursor.getString(1)
                    val definition = cursor.getString(2) ?: ""
                    newWordsList.add("$lemma ($definition)")
                }
            }
        }
        val newWordsStr = newWordsList.ifEmpty { listOf("None") }.joinToString(", ")

        // 4. isNewUser flag
        val hasVocabCursor = db.query(
            TABLE_USER_VOCABULARY,
            arrayOf("id"),
            "user_id = ?",
            arrayOf(userId),
            null, null, null, "1"
        )
        val isNewUser = !hasVocabCursor.use { it.moveToFirst() }

        return InitialContext(
            userId = userId,
            targetLanguage = targetLang,
            nativeLanguage = nativeLang,
            isNewUser = isNewUser,
            reviewList = reviewList,
            newWords = newWordsStr,
        )
    }

    fun getInitialContextString(userId: String): String {
        val ctx = getInitialContext(userId)
        return """
            |User ID: ${ctx.userId}
            |Target Language: ${ctx.targetLanguage}
            |Native Language: ${ctx.nativeLanguage}
            |NEW_USER: ${ctx.isNewUser}
            |
            |Vocabulary to Review (DUE by FSRS): ${ctx.reviewList}
            |New Vocabulary to Introduce (by frequency): ${ctx.newWords}
            |
            |Note: This snapshot was taken at session start. Use your tools to check current vocab state during the session.
        """.trimMargin()
    }

    // ========================================================================
    // updateGoals — Multi-goal seeking cycle
    // ========================================================================

    data class AnalysisError(
        val lemma: String,
        val grammarRule: String? = null,
        val grammarExample: String? = null,
    )

    fun updateGoals(userId: String, recentErrors: List<AnalysisError>? = null): String? {
        val db = writableDatabase
        val nowEpoch = now()

        // Get user language
        var targetLang = "ru"
        val userCursor = db.query(TABLE_USERS, arrayOf("target_language"), "id = ?", arrayOf(userId), null, null, null)
        userCursor.use { if (it.moveToFirst()) targetLang = it.getString(0) ?: "ru" }

        // 1. Complete goals where state >= 2 and reps >= 2 in vocabulary
        val activeGoalsCursor = db.query(
            TABLE_ACTIVE_GOALS,
            arrayOf("id", "target_word_id", "goal_type"),
            "user_id = ? AND status = ?",
            arrayOf(userId, "active"),
            null, null, "priority ASC"
        )

        val goalsToComplete = mutableListOf<Int>()
        activeGoalsCursor.use { cursor ->
            while (cursor.moveToNext()) {
                val goalId = cursor.getInt(0)
                val targetWordId = cursor.getInt(1)

                // Check user vocabulary for this word
                val vocabCursor = db.query(
                    TABLE_USER_VOCABULARY,
                    arrayOf("state", "reps", "last_review"),
                    "user_id = ? AND word_id = ?",
                    arrayOf(userId, targetWordId.toString()),
                    null, null, null
                )
                vocabCursor.use { vc ->
                    if (vc.moveToFirst()) {
                        val state = vc.getInt(0)
                        val reps = vc.getInt(1)
                        val lastReview = vc.getLong(2)

                        // Goal creation time
                        val goalCreatedCursor = db.query(
                            TABLE_ACTIVE_GOALS, arrayOf("created_at"),
                            "id = ?", arrayOf(goalId.toString()), null, null, null
                        )
                        val createdAt = goalCreatedCursor.use { gc ->
                            if (gc.moveToFirst()) gc.getLong(0) else 0L
                        }

                        if (state >= 2 && reps >= 2 && lastReview > createdAt) {
                            goalsToComplete.add(goalId)
                        }
                    }
                }
            }
        }

        // Mark completed goals
        for (goalId in goalsToComplete) {
            val values = ContentValues().apply {
                put("status", "completed")
                put("last_modified", nowEpoch)
            }
            db.update(TABLE_ACTIVE_GOALS, values, "id = ?", arrayOf(goalId.toString()))
        }

        // 2. Add remediation goals from recent errors (skip function POS)
        if (recentErrors != null && recentErrors.isNotEmpty()) {
            for (error in recentErrors) {
                // Find the word to check POS
                val wordCursor = db.query(
                    TABLE_WORDS,
                    arrayOf("id", "pos"),
                    "lemma = ? AND language = ?",
                    arrayOf(error.lemma, targetLang),
                    null, null, null, "1"
                )

                var wordId: Int? = null
                var wordPos: String? = null
                wordCursor.use { cursor ->
                    if (cursor.moveToFirst()) {
                        wordId = cursor.getInt(0)
                        wordPos = cursor.getString(1)
                    }
                }

                if (wordId == null) continue
                if (wordPos != null && FUNCTION_POS.contains(wordPos)) continue

                // Check no active goal already for this word
                val existingGoalCursor = db.query(
                    TABLE_ACTIVE_GOALS,
                    arrayOf("id"),
                    "user_id = ? AND status = ? AND target_word_id = ?",
                    arrayOf(userId, "active", wordId.toString()),
                    null, null, null
                )
                val alreadyHasGoal = existingGoalCursor.use { it.moveToFirst() }
                if (alreadyHasGoal) continue

                // Create remediation goal
                val grammarContext = error.grammarRule?.let { rule ->
                    error.grammarExample?.let { ex -> """{"rule":"$rule","example":"$ex"}""" }
                        ?: """{"rule":"$rule"}"""
                }

                val values = ContentValues().apply {
                    put("user_id", userId)
                    put("goal_type", "remediation")
                    put("target_word_id", wordId)
                    put("target_error_type", error.grammarRule)
                    put("priority", 1)
                    put("status", "active")
                    put("language", targetLang)
                    put("created_at", nowEpoch)
                    put("last_modified", nowEpoch)
                }
                db.insert(TABLE_ACTIVE_GOALS, null, values)
            }
        }

        // 3. Fill to MAX_ACTIVE_GOALS with vocab goals
        var currentActive = countActiveGoals(db, userId)
        if (currentActive < MAX_ACTIVE_GOALS) {
            // Get started word IDs
            val startedIds = mutableSetOf<Int>()
            val startedCursor = db.query(TABLE_USER_VOCABULARY, arrayOf("word_id"), "user_id = ?", arrayOf(userId), null, null, null)
            startedCursor.use { while (it.moveToNext()) startedIds.add(it.getInt(0)) }

            // Get active goal target IDs
            val activeTargets = mutableSetOf<Int>()
            val activeCursor2 = db.query(TABLE_ACTIVE_GOALS, arrayOf("target_word_id"), "user_id = ? AND status = ?", arrayOf(userId, "active"), null, null, null)
            activeCursor2.use { while (it.moveToNext()) activeTargets.add(it.getInt(0)) }

            val excludeIds = (startedIds + activeTargets).take(999)
            val excludePlaceholders = excludeIds.joinToString(",")

            // Find first unstarted word by frequency
            val unstartedCursor = if (excludeIds.isNotEmpty()) {
                db.rawQuery(
                    "SELECT id, lemma, definition FROM $TABLE_WORDS WHERE language = ? AND id NOT IN ($excludePlaceholders) ORDER BY frequency_rank ASC LIMIT 1",
                    arrayOf(targetLang)
                )
            } else {
                db.rawQuery(
                    "SELECT id, lemma, definition FROM $TABLE_WORDS WHERE language = ? ORDER BY frequency_rank ASC LIMIT 1",
                    arrayOf(targetLang)
                )
            }

            unstartedCursor.use { cursor ->
                if (cursor.moveToFirst() && currentActive < MAX_ACTIVE_GOALS) {
                    val newWordId = cursor.getInt(0)
                    val values = ContentValues().apply {
                        put("user_id", userId)
                        put("goal_type", "vocab")
                        put("target_word_id", newWordId)
                        put("priority", 5)
                        put("status", "active")
                        put("language", targetLang)
                        put("created_at", nowEpoch)
                        put("last_modified", nowEpoch)
                    }
                    db.insert(TABLE_ACTIVE_GOALS, null, values)
                }
            }
        }

        // 4. Build goal message — ALL active goals
        val allActiveCursor = db.query(
            TABLE_ACTIVE_GOALS,
            arrayOf("goal_type", "target_word_id"),
            "user_id = ? AND status = ?",
            arrayOf(userId, "active"),
            null, null, "priority ASC"
        )

        val goalMessages = mutableListOf<String>()
        allActiveCursor.use { cursor ->
            while (cursor.moveToNext()) {
                val goalType = cursor.getString(0)
                val targetWordId = cursor.getInt(1)

                val wordCursor = db.query(
                    TABLE_WORDS,
                    arrayOf("lemma", "definition"),
                    "id = ?",
                    arrayOf(targetWordId.toString()),
                    null, null, null
                )
                wordCursor.use { wc ->
                    if (wc.moveToFirst()) {
                        val lemma = wc.getString(0) ?: ""
                        val translation = wc.getString(1) ?: ""
                        if (goalType == "remediation") {
                            goalMessages.add("STRUGGLING: \"$lemma\" ($translation) — the learner keeps making errors with this word. Weave practice into the conversation naturally, don't drill it explicitly.")
                        } else if (goalType == "vocab") {
                            goalMessages.add("NEW WORD: \"$lemma\" ($translation) — introduce it when the conversation naturally touches on the topic. Don't force it.")
                        }
                    }
                }
            }
        }

        return goalMessages.ifEmpty { null }?.joinToString("\n")
    }

    private fun countActiveGoals(db: SQLiteDatabase, userId: String): Int {
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_ACTIVE_GOALS WHERE user_id = ? AND status = ?",
            arrayOf(userId, "active")
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    // ========================================================================
    // User Notes — durable learner insights (capped per category)
    // ========================================================================

    data class UserNote(
        val id: Int,
        val category: String,
        val content: String,
        val source: String,
        val supersededById: Int?,
    )

    fun writeNote(userId: String, category: String, content: String, source: String = "observed"): Int? {
        if (!NOTE_CATEGORIES.contains(category)) return null
        if (content.isBlank() || content.length > 200) return null

        val db = writableDatabase
        val nowEpoch = now()

        // Get user's target language
        var language = "ru"
        val userCursor = db.query(TABLE_USERS, arrayOf("target_language"), "id = ?", arrayOf(userId), null, null, null)
        userCursor.use { if (it.moveToFirst()) language = it.getString(0) ?: "ru" }

        // Check existing notes in this category
        val existingCursor = db.query(
            TABLE_USER_NOTES,
            arrayOf("id", "content", "superseded_by_id"),
            "user_id = ? AND category = ? AND superseded_by_id IS NULL",
            arrayOf(userId, category),
            null, null, "created_at ASC"
        )

        val existingNotes = mutableListOf<Triple<Int, String, Int?>>()
        existingCursor.use { cursor ->
            while (cursor.moveToNext()) {
                existingNotes.add(Triple(cursor.getInt(0), cursor.getString(1), null as Int?))
            }
        }

        // Dedup: skip if content is nearly identical or contains/is contained by existing
        val trimmedContent = content.trim().lowercase()
        for ((_, existingContent, _) in existingNotes) {
            val existingLower = existingContent.lowercase()
            if (existingLower == trimmedContent) return null
            if (existingLower.length > 10 && trimmedContent.length > 10 &&
                (existingLower.contains(trimmedContent) || trimmedContent.contains(existingLower))
            ) {
                return null
            }
        }

        // Cap at MAX_NOTES_PER_CATEGORY — supersede the oldest
        var supersededId: Int? = null
        if (existingNotes.size >= MAX_NOTES_PER_CATEGORY) {
            supersededId = existingNotes.firstOrNull()?.first
        }

        // Insert new note
        val values = ContentValues().apply {
            put("user_id", userId)
            put("language", language)
            put("category", category)
            put("content", content.trim())
            put("superseded_by_id", null as Int?)
            put("created_at", nowEpoch)
            put("last_modified", nowEpoch)
        }
        val newId = db.insert(TABLE_USER_NOTES, null, values)

        // Link superseded note to the new one
        if (supersededId != null && newId > 0) {
            val updateValues = ContentValues().apply {
                put("superseded_by_id", newId.toInt())
                put("last_modified", nowEpoch)
            }
            db.update(TABLE_USER_NOTES, updateValues, "id = ?", arrayOf(supersededId.toString()))
        }

        return if (newId > 0) newId.toInt() else null
    }

    fun getUserNotes(userId: String): List<UserNote> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_USER_NOTES,
            arrayOf("id", "category", "content", "superseded_by_id"),
            "user_id = ? AND superseded_by_id IS NULL",
            arrayOf(userId),
            null, null, "created_at ASC"
        )

        val notes = mutableListOf<UserNote>()
        // Note: source column doesn't exist in schema-uni.ts; is implicit via category
        cursor.use { c ->
            while (c.moveToNext()) {
                notes.add(UserNote(
                    id = c.getInt(0),
                    category = c.getString(1),
                    content = c.getString(2),
                    source = "",
                    supersededById = null,
                ))
            }
        }
        return notes
    }

    fun getNotesContext(userId: String): String {
        val notes = getUserNotes(userId)
        if (notes.isEmpty()) return ""
        return "Learner Notes:\n" + notes.joinToString("\n") { "- [${it.category}] ${it.content}" }
    }

    // ========================================================================
    // Session Summaries — cross-session memory
    // ========================================================================

    data class SessionSummaryData(
        val sessionStart: Long,
        val sessionEnd: Long,
        val topics: String?,
        val errorsSummary: String?,
        val wordsIntroduced: String?,
        val wordsStruggled: String?,
        val nextSessionHint: String?,
    )

    fun writeSessionSummary(userId: String, data: SessionSummaryData): Long {
        val db = writableDatabase
        val nowEpoch = now()

        // Get language
        var language = "ru"
        val userCursor = db.query(TABLE_USERS, arrayOf("target_language"), "id = ?", arrayOf(userId), null, null, null)
        userCursor.use { if (it.moveToFirst()) language = it.getString(0) ?: "ru" }

        val values = ContentValues().apply {
            put("user_id", userId)
            put("language", language)
            put("topics", data.topics)
            put("errors_summary", data.errorsSummary)
            put("next_session_hint", data.nextSessionHint)
            put("words_introduced", data.wordsIntroduced)
            put("words_struggled", data.wordsStruggled)
            put("session_start", data.sessionStart)
            put("session_end", data.sessionEnd)
            put("last_modified", nowEpoch)
        }
        return db.insert(TABLE_SESSION_SUMMARIES, null, values)
    }

    fun getSummariesContext(userId: String): String {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SESSION_SUMMARIES,
            null,
            "user_id = ?",
            arrayOf(userId),
            null, null,
            "session_end DESC",
            "3"
        )

        val summaries = mutableListOf<SessionSummaryData>()
        cursor.use { c ->
            while (c.moveToNext()) {
                summaries.add(SessionSummaryData(
                    sessionStart = c.getLong(c.getColumnIndexOrThrow("session_start")),
                    sessionEnd = c.getLong(c.getColumnIndexOrThrow("session_end")),
                    topics = c.getString(c.getColumnIndexOrThrow("topics")),
                    errorsSummary = c.getString(c.getColumnIndexOrThrow("errors_summary")),
                    wordsIntroduced = c.getString(c.getColumnIndexOrThrow("words_introduced")),
                    wordsStruggled = c.getString(c.getColumnIndexOrThrow("words_struggled")),
                    nextSessionHint = c.getString(c.getColumnIndexOrThrow("next_session_hint")),
                ))
            }
        }

        if (summaries.isEmpty()) return ""

        // Compute session gap
        val lastEndedEpoch = summaries.first().sessionEnd
        val nowEpoch = now()
        val gapMs = (nowEpoch - lastEndedEpoch) * 1000
        val gapHours = (gapMs / 3_600_000).toInt()
        val gapStr = when {
            gapHours < 1 -> "just now"
            gapHours < 24 -> "${gapHours} hour${if (gapHours != 1) "s" else ""} ago"
            else -> {
                val gapDays = gapHours / 24
                "${gapDays} day${if (gapDays != 1) "s" else ""} ago"
            }
        }

        val lines = summaries.map { s ->
            // Format epoch to date string
            val date = java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(s.sessionEnd * 1000))
            val durationMin = ((s.sessionEnd - s.sessionStart) / 60).toInt().coerceAtLeast(1)
            var line = "- $date (${durationMin}min): ${s.errorsSummary ?: "No errors noted"}"
            if (s.nextSessionHint != null) line += " Next: ${s.nextSessionHint}"
            line
        }

        return "Recent Sessions (last was $gapStr):\n" + lines.joinToString("\n")
    }

    // ========================================================================
    // updateSRSFromAnalysis — FSRS update from conversation analysis
    // ========================================================================

    data class LexemeAnalysis(
        val lemma: String,
        val pos: String,
        val performance: String,   // "correct_use", "wrong_use", "recall_fail", "scaffolded", "native_substitution"
        val language: String? = null,
    )

    data class SRSUpdateResult(
        val wordId: Int,
        val oldState: Int,
        val newState: Int,
        val grade: Int,
    )

    /**
     * Update SRS state from a list of lexeme analyses from a conversation turn.
     * Handles native substitutions by incrementing nativeSubstitutionCount on target words.
     * Uses Fsrs.kt for FSRS algorithm.
     */
    fun updateSRSFromAnalysis(userId: String, metadata: List<LexemeAnalysis>): List<SRSUpdateResult> {
        val db = writableDatabase
        val nowEpoch = now()
        val updates = mutableListOf<SRSUpdateResult>()

        // Get user languages
        var targetLang = "ru"
        var nativeLang = "en"
        val userCursor = db.query(TABLE_USERS, arrayOf("target_language", "native_language"), "id = ?", arrayOf(userId), null, null, null)
        userCursor.use { cursor ->
            if (cursor.moveToFirst()) {
                targetLang = cursor.getString(0) ?: "ru"
                nativeLang = cursor.getString(1) ?: "en"
            }
        }

        for (item in metadata) {
            val isNativeWord = item.performance == "native_substitution" ||
                (item.language != null && item.language != targetLang && item.language == nativeLang)

            if (isNativeWord) {
                // === NATIVE LANGUAGE SUBSTITUTION ===
                // Find or create the target-language equivalent and apply grade 1
                val targetWord = findOrCreateWord(db, item.lemma, item.pos, targetLang)
                if (targetWord != null) {
                    // Apply penalty (grade 1 = Again)
                    val result = applyFSRSGrade(db, userId, targetWord, 1, nowEpoch)
                    if (result != null) {
                        // Increment native_substitution_count
                        db.execSQL(
                            "UPDATE $TABLE_USER_VOCABULARY SET native_substitution_count = native_substitution_count + 1, last_modified = ? WHERE user_id = ? AND word_id = ?",
                            arrayOf(nowEpoch.toString(), userId, targetWord.toString())
                        )
                        updates.add(result)
                    }
                }
                continue
            }

            // === TARGET LANGUAGE WORD ===
            // Find matching word — exact POS first, then GENERAL, then lemma-only
            var wordId = findWordId(db, item.lemma, item.pos, targetLang)
                ?: findWordId(db, item.lemma, "GENERAL", targetLang)
                ?: findWordIdByLemma(db, item.lemma, targetLang)

            if (wordId == null) {
                // Auto-create word entry
                wordId = createWord(db, item.lemma, item.pos.ifBlank { "GENERAL" }, targetLang, nowEpoch)
            }

            if (wordId != null) {
                // Map performance to FSRS grade
                val grade = performanceToGrade(item.performance)
                val result = applyFSRSGrade(db, userId, wordId, grade, nowEpoch)
                if (result != null) {
                    // Increment encounter_count
                    db.execSQL(
                        "UPDATE $TABLE_USER_VOCABULARY SET encounter_count = encounter_count + 1, last_modified = ? WHERE user_id = ? AND word_id = ?",
                        arrayOf(nowEpoch.toString(), userId, wordId.toString())
                    )
                    updates.add(result)
                }
            }
        }

        return updates
    }

    // ========================================================================
    // FSRS helpers (delegates to Fsrs.kt)
    // ========================================================================

    private fun performanceToGrade(performance: String): Int {
        return when (performance) {
            "recall_fail", "wrong_use", "native_substitution" -> 1  // Again
            "scaffolded" -> 2                                          // Hard
            "correct_use" -> 3                                         // Good
            else -> 3
        }
    }

    /**
     * Apply an FSRS grade to a user's vocabulary entry for a word.
     * Looks up the current vocab state, applies Fsrs.fsrsReview(), and updates the row.
     * Creates a new vocab entry if none exists.
     */
    private fun applyFSRSGrade(
        db: SQLiteDatabase, userId: String, wordId: Int, grade: Int, nowEpoch: Long
    ): SRSUpdateResult? {
        // Look up current vocabulary state
        val vocabCursor = db.query(
            TABLE_USER_VOCABULARY,
            arrayOf("id", "state", "difficulty", "stability", "elapsed_days", "scheduled_days", "reps", "lapses", "next_review"),
            "user_id = ? AND word_id = ?",
            arrayOf(userId, wordId.toString()),
            null, null, null
        )

        // Get language for the word
        val wordCursor = db.query(TABLE_WORDS, arrayOf("language"), "id = ?", arrayOf(wordId.toString()), null, null, null)
        var language = "ru"
        wordCursor.use { if (it.moveToFirst()) language = it.getString(0) ?: "ru" }

        val vocabExists = vocabCursor.use { it.moveToFirst() }

        if (vocabExists) {
            val vocabId = vocabCursor.getInt(0)
            val oldState = vocabCursor.getInt(1)
            val difficulty = vocabCursor.getDouble(2)
            val stability = vocabCursor.getDouble(3)
            val elapsedDays = vocabCursor.getInt(4)
            val scheduledDays = vocabCursor.getInt(5)
            val reps = vocabCursor.getInt(6)
            val lapses = vocabCursor.getInt(7)
            val nextReview = vocabCursor.getLong(8)

            // Calculate elapsed days since last review
            val elapsedSinceReview = if (nextReview > 0) {
                ((nowEpoch - nextReview) / 86400).toInt().coerceAtLeast(0)
            } else 0

            // Build FSRS card
            val card = Fsrs.FSRSCard(
                state = oldState,
                difficulty = difficulty,
                stability = stability,
                elapsedDays = elapsedSinceReview,
                scheduledDays = scheduledDays,
                reps = reps,
                lapses = lapses,
            )

            val result = Fsrs.fsrsReview(card, grade)

            // Update row
            val values = ContentValues().apply {
                put("state", result.state)
                put("difficulty", result.difficulty)
                put("stability", result.stability)
                put("scheduled_days", result.scheduledDays)
                put("next_review", result.dueEpochSeconds)
                put("reps", result.reps)
                put("lapses", result.lapses)
                put("elapsed_days", elapsedSinceReview)
                put("last_review", nowEpoch)
                put("last_modified", nowEpoch)
            }
            db.update(TABLE_USER_VOCABULARY, values, "id = ?", arrayOf(vocabId.toString()))

            return SRSUpdateResult(wordId = wordId, oldState = oldState, newState = result.state, grade = grade)
        } else {
            // New vocab entry
            val card = Fsrs.FSRSCard(
                state = 0, difficulty = 0.0, stability = 0.0,
                elapsedDays = 0, scheduledDays = 0, reps = 0, lapses = 0,
            )
            val result = Fsrs.fsrsReview(card, grade)

            val values = ContentValues().apply {
                put("user_id", userId)
                put("word_id", wordId)
                put("language", language)
                put("state", result.state)
                put("difficulty", result.difficulty)
                put("stability", result.stability)
                put("next_review", result.dueEpochSeconds)
                put("reps", result.reps)
                put("lapses", result.lapses)
                put("scheduled_days", result.scheduledDays)
                put("elapsed_days", 0)
                put("encounter_count", 1)
                put("last_review", nowEpoch)
                put("last_modified", nowEpoch)
            }
            db.insert(TABLE_USER_VOCABULARY, null, values)

            return SRSUpdateResult(wordId = wordId, oldState = 0, newState = result.state, grade = grade)
        }
    }

    // ========================================================================
    // Word lookups / creation
    // ========================================================================

    private fun findWordId(db: SQLiteDatabase, lemma: String, pos: String, language: String): Int? {
        val cursor = db.query(
            TABLE_WORDS, arrayOf("id"),
            "lemma = ? AND pos = ? AND language = ?",
            arrayOf(lemma, pos, language),
            null, null, null, "1"
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else null }
    }

    private fun findWordIdByLemma(db: SQLiteDatabase, lemma: String, language: String): Int? {
        val cursor = db.query(
            TABLE_WORDS, arrayOf("id"),
            "lemma = ? AND language = ?",
            arrayOf(lemma, language),
            null, null, null, "1"
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else null }
    }

    private fun createWord(db: SQLiteDatabase, lemma: String, pos: String, language: String, nowEpoch: Long): Int? {
        val values = ContentValues().apply {
            put("word", lemma)
            put("language", language)
            put("cefr_level", "A1")  // Default for auto-created words
            put("pos", pos)
            put("lemma", lemma)
            put("definition", "")
            put("last_modified", nowEpoch)
        }
        val id = db.insert(TABLE_WORDS, null, values)
        return if (id > 0) id.toInt() else null
    }

    private fun findOrCreateWord(db: SQLiteDatabase, lemma: String, pos: String, language: String): Int? {
        return findWordId(db, lemma, pos, language)
            ?: findWordIdByLemma(db, lemma, language)
            ?: createWord(db, lemma, pos, language, now())
    }

    // ========================================================================
    // Calibration seeding — bridge 3-tier calibration into FSRS
    // ========================================================================

    data class CalibrationResult(
        val knownWordsSeeded: Int,
        val newWordsSeeded: Int,
        val totalWordsSeeded: Int,
    )

    /**
     * Seed vocabulary from calibration results.
     *
     * For each lemma the learner recognized during the rapid word-recognition
     * phase, creates a user_vocabulary entry in Review state (Leitner box 3
     * mapping: state=2, stability=7.0, difficulty=4.0, scheduledDays=7,
     * reps=3, lapses=0, next_review = now + 7 days).
     *
     * For CEFR words at or below [cefrLevelHint] that were NOT recognized,
     * also creates vocabulary entries but at state=0 (New) with default FSRS
     * values — giving FSRS a starting point for the learner's full vocabulary.
     *
     * @param userId         Current user ID
     * @param language        Target language (ISO 639-1, e.g. "ru")
     * @param knownLemmas    Lemmas the learner recognized during calibration
     * @param cefrLevelHint  Self-reported or estimated CEFR level (e.g. "A2")
     * @return CalibrationResult with counts of known / new / total words seeded
     */
    fun seedFromCalibration(
        userId: String,
        language: String,
        knownLemmas: List<String>,
        cefrLevelHint: String,
    ): CalibrationResult {
        val db = writableDatabase
        val nowEpoch = now()
        val sevenDaysSeconds = 7L * 24 * 60 * 60

        var knownCount = 0
        var newCount = 0

        val knownSet = knownLemmas.toSet()

        db.beginTransaction()
        try {
            // --- 1. Seed KNOWN words as Review (Leitner box 3 mapping) ---
            for (lemma in knownSet) {
                // Find word in words table; fall back to CEFR word lists, then create
                var wordId = findWordIdByLemma(db, lemma, language)
                if (wordId == null) {
                    wordId = findCefrWordListIdByLemma(db, lemma, language)
                        ?.let { ensureWordFromCefr(db, it, language, nowEpoch) }
                }
                if (wordId == null) {
                    wordId = createWord(db, lemma, "GENERAL", language, nowEpoch)
                }
                if (wordId == null) continue

                // Skip if vocabulary entry already exists for this user+word
                if (vocabEntryExists(db, userId, wordId)) continue

                val values = ContentValues().apply {
                    put("user_id", userId)
                    put("word_id", wordId)
                    put("difficulty", 4.0)
                    put("stability", 7.0)
                    put("retrievability", 0.0)
                    put("next_review", nowEpoch + sevenDaysSeconds)
                    put("reps", 3)
                    put("lapses", 0)
                    put("elapsed_days", 0)
                    put("scheduled_days", 7)
                    put("encounter_count", 1)
                    put("language", language)
                    put("state", 2) // Review
                    put("last_review", nowEpoch)
                    put("last_modified", nowEpoch)
                }
                db.insert(TABLE_USER_VOCABULARY, null, values)
                knownCount++
            }

            // --- 2. Seed UNRECOGNIZED CEFR words (at or below hint level) as New ---
            val levelOrder = mapOf("A1" to 1, "A2" to 2, "B1" to 3, "B2" to 4, "C1" to 5, "C2" to 6)
            val hintLevel = levelOrder[cefrLevelHint.uppercase()] ?: 1
            val applicableLevels = levelOrder.filter { it.value <= hintLevel }.keys

            for (level in applicableLevels) {
                val cefrCursor = db.query(
                    TABLE_CEFR_WORD_LISTS,
                    arrayOf("lemma", "pos", "word", "cefr_level", "frequency_rank", "definition"),
                    "language = ? AND cefr_level = ?",
                    arrayOf(language, level),
                    null, null, "frequency_rank ASC"
                )

                cefrCursor.use { cursor ->
                    while (cursor.moveToNext()) {
                        val lemma = cursor.getString(0) ?: continue
                        if (lemma in knownSet) continue // already seeded as known

                        val pos = cursor.getString(1) ?: "GENERAL"
                        var wordId = findWordIdByLemma(db, lemma, language)
                        if (wordId == null) {
                            wordId = createWord(db, lemma, pos, language, nowEpoch)
                        }
                        if (wordId == null) continue

                        if (vocabEntryExists(db, userId, wordId)) continue

                        val values = ContentValues().apply {
                            put("user_id", userId)
                            put("word_id", wordId)
                            put("difficulty", 0.0)
                            put("stability", 0.0)
                            put("retrievability", 0.0)
                            put("next_review", nowEpoch)
                            put("reps", 0)
                            put("lapses", 0)
                            put("elapsed_days", 0)
                            put("scheduled_days", 0)
                            put("encounter_count", 0)
                            put("language", language)
                            put("state", 0) // New
                            put("last_modified", nowEpoch)
                        }
                        db.insert(TABLE_USER_VOCABULARY, null, values)
                        newCount++
                    }
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return CalibrationResult(
            knownWordsSeeded = knownCount,
            newWordsSeeded = newCount,
            totalWordsSeeded = knownCount + newCount,
        )
    }

    /** Check whether a vocabulary entry already exists for user+word. */
    private fun vocabEntryExists(db: SQLiteDatabase, userId: String, wordId: Int): Boolean {
        val cursor = db.query(
            TABLE_USER_VOCABULARY,
            arrayOf("id"),
            "user_id = ? AND word_id = ?",
            arrayOf(userId, wordId.toString()),
            null, null, null, "1"
        )
        return cursor.use { it.moveToFirst() }
    }

    /** Find a row in cefr_word_lists by lemma+language, returning its row id. */
    private fun findCefrWordListIdByLemma(db: SQLiteDatabase, lemma: String, language: String): Int? {
        val cursor = db.query(
            TABLE_CEFR_WORD_LISTS,
            arrayOf("id"),
            "lemma = ? AND language = ?",
            arrayOf(lemma, language),
            null, null, null, "1"
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else null }
    }

    /**
     * Given a cefr_word_lists row id, ensure a corresponding entry exists in
     * the words table and return the words-table id.
     */
    private fun ensureWordFromCefr(db: SQLiteDatabase, cefrId: Int, language: String, nowEpoch: Long): Int? {
        // Read the CEFR row
        val cefrCursor = db.query(
            TABLE_CEFR_WORD_LISTS,
            arrayOf("word", "lemma", "pos", "cefr_level", "frequency_rank", "definition"),
            "id = ?",
            arrayOf(cefrId.toString()),
            null, null, null, "1"
        )

        val (word, lemma, pos, cefrLevel, freq, definition) = cefrCursor.use { c ->
            if (c.moveToFirst()) {
                Tuple6(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getString(5))
            } else return null
        }

        // Check if it already exists in words table
        val existingId = findWordIdByLemma(db, lemma ?: word, language)
        if (existingId != null) return existingId

        val values = ContentValues().apply {
            put("word", word)
            put("language", language)
            put("cefr_level", cefrLevel ?: "A1")
            put("pos", pos ?: "GENERAL")
            put("lemma", lemma ?: word)
            put("frequency_rank", freq)
            put("definition", definition ?: "")
            put("last_modified", nowEpoch)
        }
        val id = db.insert(TABLE_WORDS, null, values)
        return if (id > 0) id.toInt() else null
    }

    // Helpers for six-value destructuring since Kotlin stdlib lacks Tuple6
    private data class Tuple6<A, B, C, D, E, F>(
        val a: A, val b: B, val c: C, val d: D, val e: E, val f: F
    )

    // ========================================================================
    // CEFR word list population — bulk-load from JSON assets into SQLite
    // ========================================================================

    /**
     * Populate the words and cefr_word_lists tables from bundled CEFR JSON assets.
     *
     * Idempotent: checks whether records already exist for the given [language]
     * before inserting. Uses [CefrWordRepository] to parse the JSON files and
     * then bulk-inserts into both the `cefr_word_lists` and `words` tables.
     *
     * @param language ISO 639-1 language code (e.g. "ru")
     * @return Number of words inserted (0 if already populated)
     */
    fun populateCefrWords(language: String): Int {
        val db = writableDatabase
        val nowEpoch = now()

        // Check if already populated for this language
        val checkCursor = db.query(
            TABLE_CEFR_WORD_LISTS,
            arrayOf("id"),
            "language = ?",
            arrayOf(language),
            null, null, null, "1"
        )
        val alreadyPopulated = checkCursor.use { it.moveToFirst() }
        if (alreadyPopulated) return 0

        // Load CEFR words from assets via CefrWordRepository
        val cefrWords = CefrWordRepository.getInstance().loadForLanguage(appContext, language)
        if (cefrWords.isEmpty()) return 0

        db.beginTransaction()
        try {
            var inserted = 0
            for (cw in cefrWords) {
                // --- Insert into cefr_word_lists ---
                val cefrValues = ContentValues().apply {
                    put("language", cw.language)
                    put("cefr_level", cw.cefrLevel)
                    put("word", cw.word)
                    put("lemma", cw.lemma)
                    put("pos", cw.pos)
                    put("definition", cw.definition)
                    put("frequency_rank", cw.frequencyRank)
                }
                val cefrRowId = db.insert(TABLE_CEFR_WORD_LISTS, null, cefrValues)

                // --- Insert into words table (if not already present) ---
                val existingWordId = findWordIdByLemma(db, cw.lemma, cw.language)
                if (existingWordId == null) {
                    val wordValues = ContentValues().apply {
                        put("word", cw.word)
                        put("language", cw.language)
                        put("cefr_level", cw.cefrLevel)
                        put("pos", cw.pos)
                        put("lemma", cw.lemma)
                        put("frequency_rank", cw.frequencyRank)
                        put("definition", cw.definition)
                        put("last_modified", nowEpoch)
                    }
                    db.insert(TABLE_WORDS, null, wordValues)
                }

                inserted++
            }
            db.setTransactionSuccessful()
            return inserted
        } finally {
            db.endTransaction()
        }
    }

    // ========================================================================
    // Sync support: export / import rows for Edge↔Cloud sync
    // ========================================================================

    /**
     * Export all rows from the given table where lastModified > sinceTimestamp.
     * Returns a list of ContentValues maps suitable for SyncModels serialization.
     */
    fun exportRowsForSync(tableName: String, sinceTimestamp: Long): List<Map<String, Any?>> {
        val db = readableDatabase
        val cursor = db.query(
            tableName, null,
            "last_modified > ?",
            arrayOf(sinceTimestamp.toString()),
            null, null, "last_modified ASC"
        )

        val rows = mutableListOf<Map<String, Any?>>()
        cursor.use { c ->
            val columnNames = c.columnNames
            while (c.moveToNext()) {
                val row = mutableMapOf<String, Any?>()
                for (col in columnNames) {
                    val idx = c.getColumnIndexOrThrow(col)
                    val type = c.getType(idx)
                    row[col] = when (type) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(idx)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(idx)
                        android.database.Cursor.FIELD_TYPE_STRING -> c.getString(idx)
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        else -> c.getString(idx)
                    }
                }
                rows.add(row)
            }
        }
        return rows
    }

    /**
     * Import rows from cloud sync — upserts based on natural keys.
     * Uses last-write-wins: only updates if incoming lastModified >= existing.
     */
    fun importRowsFromSync(tableName: String, rows: List<Map<String, Any?>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (row in rows) {
                val lastModified = row["last_modified"] as? Long ?: continue

                // Check if row exists (by id)
                val existingId = row["id"] as? Long
                if (existingId != null && existingId > 0) {
                    val cursor = db.query(tableName, arrayOf("last_modified"), "id = ?", arrayOf(existingId.toString()), null, null, null)
                    val shouldUpdate = cursor.use { c ->
                        if (c.moveToFirst()) {
                            val existingTs = c.getLong(0)
                            lastModified >= existingTs  // last-write-wins
                        } else false
                    }

                    if (shouldUpdate) {
                        // Update existing row
                        val values = ContentValues().apply {
                            for ((key, value) in row) {
                                if (key != "id") {
                                    when (value) {
                                        is Long -> put(key, value)
                                        is Double -> put(key, value)
                                        is String -> put(key, value)
                                        is Int -> put(key, value)
                                        null -> putNull(key)
                                    }
                                }
                            }
                        }
                        db.update(tableName, values, "id = ?", arrayOf(existingId.toString()))
                    }
                } else {
                    // Insert new row (without explicit id, let autoincrement assign)
                    val values = ContentValues().apply {
                        for ((key, value) in row) {
                            if (key != "id") {
                                when (value) {
                                    is Long -> put(key, value)
                                    is Double -> put(key, value)
                                    is String -> put(key, value)
                                    is Int -> put(key, value)
                                    null -> putNull(key)
                                }
                            }
                        }
                    }
                    db.insert(tableName, null, values)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ========================================================================
    // User CRUD helpers
    // ========================================================================

    fun ensureUser(userId: String, nativeLanguage: String = "en", targetLanguage: String = "en") {
        val db = writableDatabase
        val cursor = db.query(TABLE_USERS, arrayOf("id"), "id = ?", arrayOf(userId), null, null, null)
        val exists = cursor.use { it.moveToFirst() }
        if (!exists) {
            val nowEpoch = now()
            val values = ContentValues().apply {
                put("id", userId)
                put("native_language", nativeLanguage)
                put("target_language", targetLanguage)
                put("proficiency_level", "A1")
                put("created_at", nowEpoch)
                put("updated_at", nowEpoch)
            }
            db.insert(TABLE_USERS, null, values)
        }
    }

    fun getUserTargetLanguage(userId: String): String {
        val db = readableDatabase
        val cursor = db.query(TABLE_USERS, arrayOf("target_language"), "id = ?", arrayOf(userId), null, null, null)
        return cursor.use { if (it.moveToFirst()) it.getString(0) ?: "ru" else "ru" }
    }

    fun getUserNativeLanguage(userId: String): String {
        val db = readableDatabase
        val cursor = db.query(TABLE_USERS, arrayOf("native_language"), "id = ?", arrayOf(userId), null, null, null)
        return cursor.use { if (it.moveToFirst()) it.getString(0) ?: "en" else "en" }
    }
}