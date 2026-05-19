// SPDX-FileCopyrightText: 2025 LingLang
//
// SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.linglang

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*

/**
 * SyncService — Handles push/pull sync cycle for Edge↔Cloud.
 *
 * Flow:
 * 1. POST local rows where lastModified > lastSyncTime
 * 2. Cloud merges (last-write-wins per row)
 * 3. Returns all rows where lastModified > lastSyncTime
 * 4. Edge upserts returned rows
 *
 * Uses Retrofit2 + Moshi for HTTP/JSON.
 * Tracks lastSyncTime per table in SharedPreferences.
 */
object SyncService {

    private const val TAG = "SyncService"
    private const val PREFS_NAME = "linglang_sync"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_AUTH_USER_ID = "auth_user_id"
    private const val KEY_LAST_SYNC_PREFIX = "last_sync_"

    // ========================================================================
    // Retrofit API interface
    // ========================================================================

    interface LingLangApi {
        @POST("/api/v1/sync")
        suspend fun sync(@Body request: SyncRequest): SyncResponse

        @POST("/api/v1/auth/login")
        suspend fun login(@Body request: AuthRequest): AuthResponse

        @POST("/api/v1/auth/register")
        suspend fun register(@Body request: AuthRequest): AuthResponse
    }

    // ========================================================================
    // Sync status (observable)
    // ========================================================================

    @Volatile
    var status: SyncStatus = SyncStatus.IDLE
        private set

    private val statusListeners = mutableListOf<(SyncStatus) -> Unit>()

    fun addStatusListener(listener: (SyncStatus) -> Unit) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: (SyncStatus) -> Unit) {
        statusListeners.remove(listener)
    }

    private fun updateStatus(newStatus: SyncStatus) {
        status = newStatus
        statusListeners.forEach { it(newStatus) }
    }

    // ========================================================================
    // Authentication
    // ========================================================================

    fun isAuthenticated(context: Context): Boolean {
        val prefs = getPrefs(context)
        val token = prefs.getString(KEY_AUTH_TOKEN, null)
        return !token.isNullOrBlank()
    }

    fun getAuthToken(context: Context): String? {
        return getPrefs(context).getString(KEY_AUTH_TOKEN, null)
    }

    fun getUserId(context: Context): String? {
        return getPrefs(context).getString(KEY_AUTH_USER_ID, null)
    }

    /**
     * Authenticate with email/password. Stores token and userId on success.
     */
    suspend fun authenticate(context: Context, email: String, password: String): AuthResponse {
        val api = buildApi(context)
        val response = api.login(AuthRequest(email = email, password = password))

        // Persist auth info
        getPrefs(context).edit()
            .putString(KEY_AUTH_TOKEN, response.token)
            .putString(KEY_AUTH_USER_ID, response.userId)
            .apply()

        return response
    }

    /**
     * Register a new account. Stores token and userId on success.
     */
    suspend fun register(context: Context, email: String, password: String): AuthResponse {
        val api = buildApi(context)
        val response = api.register(AuthRequest(email = email, password = password))

        getPrefs(context).edit()
            .putString(KEY_AUTH_TOKEN, response.token)
            .putString(KEY_AUTH_USER_ID, response.userId)
            .apply()

        return response
    }

    fun logout(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_AUTH_USER_ID)
            .apply()
    }

    // ========================================================================
    // Last sync time tracking (per table)
    // ========================================================================

    fun getLastSyncTime(context: Context, tableName: String): Long {
        return getPrefs(context).getLong(KEY_LAST_SYNC_PREFIX + tableName, 0L)
    }

    fun setLastSyncTime(context: Context, tableName: String, timestamp: Long) {
        getPrefs(context).edit()
            .putLong(KEY_LAST_SYNC_PREFIX + tableName, timestamp)
            .apply()
    }

    /**
     * Reset all sync timestamps (e.g., after re-auth)
     */
    fun resetAllSyncTimes(context: Context) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
        for ((key, _) in prefs.all) {
            if (key.startsWith(KEY_LAST_SYNC_PREFIX)) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    // ========================================================================
    // Main sync cycle
    // ========================================================================

    data class SyncResult(
        val tablesPushed: Map<String, Int>,     // tableName -> count of rows pushed
        val tablesPulled: Map<String, Int>,      // tableName -> count of rows pulled
        val serverTimestamp: Long,
        val errors: List<String>,
    )

    /**
     * Execute a full push/pull sync cycle.
     *
     * 1. Export local rows where lastModified > lastSyncTime for each syncable table
     * 2. POST to cloud
     * 3. Cloud merges (last-write-wins) and returns rows where lastModified > lastSyncTime
     * 4. Upsert returned rows locally
     * 5. Update lastSyncTime for each table
     *
     * @param context Android context
     * @param serverUrl Base URL of the cloud server (e.g., "https://api.linglang.app")
     * @param userId User ID to sync
     * @param contextManager DB layer for import/export
     * @param tables List of table names to sync (defaults to all syncable tables)
     */
    suspend fun sync(
        context: Context,
        serverUrl: String,
        userId: String,
        contextManager: ContextManager,
        tables: List<String> = SYNCABLE_TABLES,
    ): SyncResult {
        updateStatus(SyncStatus.SYNCING)

        val tablesPushed = mutableMapOf<String, Int>()
        val tablesPulled = mutableMapOf<String, Int>()
        val errors = mutableListOf<String>()
        var serverTimestamp = 0L

        try {
            val api = buildApi(context, serverUrl)

            // 1. Build request — export rows changed since last sync per table
            val tablesMap = mutableMapOf<String, List<SyncRow>>()
            val lastSyncTimestamps = mutableMapOf<String, Long>()

            for (table in tables) {
                val lastSync = getLastSyncTime(context, table)
                lastSyncTimestamps[table] = lastSync

                val rowMaps = contextManager.exportRowsForSync(table, lastSync)
                val syncRows = mapToSyncRows(table, rowMaps)
                tablesMap[table] = syncRows
                tablesPushed[table] = syncRows.size
            }

            // 2. POST to cloud
            val request = SyncRequest(
                userId = userId,
                tables = tablesMap,
                lastSyncTimestamps = lastSyncTimestamps,
            )

            val response = api.sync(request)
            serverTimestamp = response.serverTimestamp

            // 3. Upsert returned rows
            for ((table, syncedRows) in response.merged) {
                val rowMaps = syncRowsToMaps(table, syncedRows)
                contextManager.importRowsFromSync(table, rowMaps)
                tablesPulled[table] = rowMaps.size

                // Update last sync time for this table
                setLastSyncTime(context, table, serverTimestamp)
            }

            updateStatus(SyncStatus.SUCCESS)

        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            errors.add(e.message ?: "Unknown sync error")
            updateStatus(SyncStatus.ERROR)
        }

        return SyncResult(
            tablesPushed = tablesPushed,
            tablesPulled = tablesPulled,
            serverTimestamp = serverTimestamp,
            errors = errors,
        )
    }

    // ========================================================================
    // Syncable tables
    // ========================================================================

    val SYNCABLE_TABLES = listOf(
        ContextManager.TABLE_USER_VOCABULARY,
        ContextManager.TABLE_ACTIVE_GOALS,
        ContextManager.TABLE_USER_NOTES,
        ContextManager.TABLE_SESSION_SUMMARIES,
        ContextManager.TABLE_WORDS,
        ContextManager.TABLE_REVIEW_LOGS,
    )

    // ========================================================================
    // Map generic row maps to typed SyncRow models
    // ========================================================================

    private fun mapToSyncRows(tableName: String, rows: List<Map<String, Any?>>): List<SyncRow> {
        return rows.mapNotNull { row ->
            when (tableName) {
                ContextManager.TABLE_USER_VOCABULARY -> SyncVocabRow(
                    id = (row["id"] as? Long)?.toInt() ?: 0,
                    userId = row["user_id"] as? String ?: "",
                    wordId = (row["word_id"] as? Long)?.toInt() ?: 0,
                    difficulty = (row["difficulty"] as? Double) ?: 0.0,
                    stability = (row["stability"] as? Double) ?: 0.0,
                    retrievability = (row["retrievability"] as? Double) ?: 0.0,
                    nextReview = (row["next_review"] as? Long) ?: 0L,
                    reps = (row["reps"] as? Long)?.toInt() ?: 0,
                    lapses = (row["lapses"] as? Long)?.toInt() ?: 0,
                    elapsedDays = (row["elapsed_days"] as? Long)?.toInt() ?: 0,
                    scheduledDays = (row["scheduled_days"] as? Long)?.toInt() ?: 0,
                    lastErrorType = row["last_error_type"] as? String,
                    lastPronunciationScore = row["last_pronunciation_score"] as? Double,
                    encounterCount = (row["encounter_count"] as? Long)?.toInt() ?: 0,
                    language = row["language"] as? String ?: "",
                    state = (row["state"] as? Long)?.toInt() ?: 0,
                    lastModified = (row["last_modified"] as? Long) ?: 0L,
                )
                ContextManager.TABLE_ACTIVE_GOALS -> SyncGoalRow(
                    id = (row["id"] as? Long)?.toInt() ?: 0,
                    userId = row["user_id"] as? String ?: "",
                    goalType = row["goal_type"] as? String ?: "",
                    targetWordId = (row["target_word_id"] as? Long)?.toInt(),
                    targetErrorType = row["target_error_type"] as? String,
                    priority = (row["priority"] as? Long)?.toInt() ?: 5,
                    status = row["status"] as? String ?: "active",
                    successCount = (row["success_count"] as? Long)?.toInt() ?: 0,
                    failCount = (row["fail_count"] as? Long)?.toInt() ?: 0,
                    requiredSuccesses = (row["required_successes"] as? Long)?.toInt() ?: 2,
                    language = row["language"] as? String ?: "",
                    createdAt = (row["created_at"] as? Long) ?: 0L,
                    lastModified = (row["last_modified"] as? Long) ?: 0L,
                )
                ContextManager.TABLE_USER_NOTES -> SyncNoteRow(
                    id = (row["id"] as? Long)?.toInt() ?: 0,
                    userId = row["user_id"] as? String ?: "",
                    language = row["language"] as? String ?: "",
                    category = row["category"] as? String ?: "",
                    content = row["content"] as? String ?: "",
                    supersededById = (row["superseded_by_id"] as? Long)?.toInt(),
                    createdAt = (row["created_at"] as? Long) ?: 0L,
                    lastModified = (row["last_modified"] as? Long) ?: 0L,
                )
                ContextManager.TABLE_SESSION_SUMMARIES -> SyncSummaryRow(
                    id = (row["id"] as? Long)?.toInt() ?: 0,
                    userId = row["user_id"] as? String ?: "",
                    language = row["language"] as? String ?: "",
                    topics = row["topics"] as? String,
                    errorsSummary = row["errors_summary"] as? String,
                    nextSessionHint = row["next_session_hint"] as? String,
                    wordsIntroduced = row["words_introduced"] as? String,
                    wordsStruggled = row["words_struggled"] as? String,
                    sessionStart = (row["session_start"] as? Long) ?: 0L,
                    sessionEnd = (row["session_end"] as? Long) ?: 0L,
                    lastModified = (row["last_modified"] as? Long) ?: 0L,
                )
                ContextManager.TABLE_WORDS -> SyncWordsRow(
                    id = (row["id"] as? Long)?.toInt() ?: 0,
                    word = row["word"] as? String ?: "",
                    language = row["language"] as? String ?: "",
                    cefrLevel = row["cefr_level"] as? String ?: "",
                    pos = row["pos"] as? String,
                    lemma = row["lemma"] as? String,
                    frequencyRank = (row["frequency_rank"] as? Long)?.toInt(),
                    definition = row["definition"] as? String,
                    lastModified = (row["last_modified"] as? Long) ?: 0L,
                )
                else -> null
            }
        }
    }

    private fun syncRowsToMaps(tableName: String, rows: List<SyncRow>): List<Map<String, Any?>> {
        return rows.map { row ->
            when (row) {
                is SyncVocabRow -> mapOf(
                    "id" to row.id,
                    "user_id" to row.userId,
                    "word_id" to row.wordId,
                    "difficulty" to row.difficulty,
                    "stability" to row.stability,
                    "retrievability" to row.retrievability,
                    "next_review" to row.nextReview,
                    "reps" to row.reps,
                    "lapses" to row.lapses,
                    "elapsed_days" to row.elapsedDays,
                    "scheduled_days" to row.scheduledDays,
                    "last_error_type" to row.lastErrorType,
                    "last_pronunciation_score" to row.lastPronunciationScore,
                    "encounter_count" to row.encounterCount,
                    "language" to row.language,
                    "state" to row.state,
                    "last_modified" to row.lastModified,
                )
                is SyncGoalRow -> mapOf(
                    "id" to row.id,
                    "user_id" to row.userId,
                    "goal_type" to row.goalType,
                    "target_word_id" to row.targetWordId,
                    "target_error_type" to row.targetErrorType,
                    "priority" to row.priority,
                    "status" to row.status,
                    "success_count" to row.successCount,
                    "fail_count" to row.failCount,
                    "required_successes" to row.requiredSuccesses,
                    "language" to row.language,
                    "created_at" to row.createdAt,
                    "last_modified" to row.lastModified,
                )
                is SyncNoteRow -> mapOf(
                    "id" to row.id,
                    "user_id" to row.userId,
                    "language" to row.language,
                    "category" to row.category,
                    "content" to row.content,
                    "superseded_by_id" to row.supersededById,
                    "created_at" to row.createdAt,
                    "last_modified" to row.lastModified,
                )
                is SyncSummaryRow -> mapOf(
                    "id" to row.id,
                    "user_id" to row.userId,
                    "language" to row.language,
                    "topics" to row.topics,
                    "errors_summary" to row.errorsSummary,
                    "next_session_hint" to row.nextSessionHint,
                    "words_introduced" to row.wordsIntroduced,
                    "words_struggled" to row.wordsStruggled,
                    "session_start" to row.sessionStart,
                    "session_end" to row.sessionEnd,
                    "last_modified" to row.lastModified,
                )
                is SyncWordsRow -> mapOf(
                    "id" to row.id,
                    "word" to row.word,
                    "language" to row.language,
                    "cefr_level" to row.cefrLevel,
                    "pos" to row.pos,
                    "lemma" to row.lemma,
                    "frequency_rank" to row.frequencyRank,
                    "definition" to row.definition,
                    "last_modified" to row.lastModified,
                )
                else -> emptyMap<String, Any?>()
            }
        }
    }

    // ========================================================================
    // Retrofit builder
    // ========================================================================

    private var cachedApi: LingLangApi? = null
    private var cachedBaseUrl: String? = null

    @Synchronized
    fun buildApi(context: Context, serverUrl: String? = null): LingLangApi {
        val baseUrl = serverUrl ?: getDefaultServerUrl(context)

        if (cachedApi != null && cachedBaseUrl == baseUrl) {
            return cachedApi!!
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val token = getAuthToken(context)
                val requestBuilder = original.newBuilder()
                if (!token.isNullOrBlank()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }
                chain.proceed(requestBuilder.build())
            }
            .build()

        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        cachedBaseUrl = baseUrl
        cachedApi = retrofit.create(LingLangApi::class.java)
        return cachedApi!!
    }

    private fun getDefaultServerUrl(context: Context): String {
        val prefs = getPrefs(context)
        return prefs.getString("server_url", "https://api.linglang.app") ?: "https://api.linglang.app"
    }

    fun setServerUrl(context: Context, url: String) {
        getPrefs(context).edit().putString("server_url", url).apply()
        // Invalidate cached API
        cachedApi = null
        cachedBaseUrl = null
    }

    // ========================================================================
    // SharedPreferences
    // ========================================================================

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}