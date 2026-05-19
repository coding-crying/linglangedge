package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.linglang.ContextManager
import com.google.ai.edge.gallery.linglang.LangMetadataParser
import com.google.ai.edge.gallery.linglang.ParsedResponse
import com.google.ai.edge.gallery.proto.UserData
import com.google.ai.edge.gallery.tts.StreamingTtsPlayer
import com.google.ai.edge.gallery.tts.VadRecorder
import com.google.ai.edge.litertlm.Contents
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "LingLangTutor"

/** Supported target languages for the LingLang tutor. */
enum class TutorLanguage(
  val displayName: String,
  val code: String,
  val locale: Locale,
  val systemPromptSuffix: String,
  val kokoroVoiceId: String,
) {
  ENGLISH("English", "en", Locale("en"), """
You are a friendly English language tutor helping a learner practice conversation.

Rules:
- Have natural conversations in English on any topic the learner wants to discuss.
- Keep your responses short (1-3 sentences) to maintain a natural back-and-forth rhythm.
- If the learner makes a grammar or vocabulary mistake, gently provide the correct form and continue the conversation.
- Don't just repeat or transcribe what the learner said — always respond with new content as a conversation partner.
- After each response, include a <lang-metadata> block with JSON:
  <lang-metadata>
  {"words_used": ["word1","word2"], "errors": [{"word":"wrong_word","type":"grammar|vocabulary|pronunciation","expected":"correct_form","got":"what_they_said"}], "fluency": "fluid|hesitant|paused"}
  </lang-metadata>
- If no errors, return an empty errors array.
- Only comment on actual mistakes, don't invent errors.
  """.trimIndent(), "en-US-heart-kokoro"),
}

@HiltViewModel
class LingLangTutorViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  userDataDataStore: DataStore<UserData>,
) : LlmChatViewModelBase(systemPromptRepository, userDataDataStore) {

  private val _selectedLanguage = MutableStateFlow(TutorLanguage.ENGLISH)
  val selectedLanguage = _selectedLanguage.asStateFlow()

  /** Clear any stale custom system prompt so the updated default always takes effect. */
  init {
    viewModelScope.launch {
      systemPromptRepository.clearCustomSystemPrompt(BuiltInTaskId.LINGLANG_TUTOR)
    }
  }

  // --- LingLang context & SRS integration ---
  companion object {
    private const val USER_ID = "user_1"
    private const val GOALS_UPDATE_INTERVAL_TURNS = 5
  }

  private var contextManager: ContextManager? = null
  private var turnCount = 0
  private var sessionStartTime: Long = 0L
  private var sessionActive = false

  /** Accumulates the full LLM response for metadata parsing after generation ends. */
  private val responseBuffer = StringBuilder()

  /** Lazy-initialize ContextManager once we have an Android context. */
  private fun ensureContextManager(): ContextManager {
    val ctx = appContext ?: throw IllegalStateException("initTts() must be called first")
    return contextManager ?: ContextManager(ctx).also { contextManager = it }
  }

  /** Build a system prompt that includes the user's learning context. */
  private fun buildContextualSystemPrompt(basePrompt: String): String {
    return try {
      val contextStr = ensureContextManager().getInitialContextString(USER_ID)
      val summariesStr = ensureContextManager().getSummariesContext(USER_ID)
      val notesStr = ensureContextManager().getNotesContext(USER_ID)
      buildString {
        append(contextStr)
        if (summariesStr.isNotBlank()) {
          append("\n\n")
          append(summariesStr)
        }
        if (notesStr.isNotBlank()) {
          append("\n\n")
          append(notesStr)
        }
        append("\n\n---\n\n")
        append(basePrompt)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to build contextual system prompt, using base", e)
      basePrompt
    }
  }

  // TTS state
  private val _ttsEnabled = MutableStateFlow(true)
  val ttsEnabled = _ttsEnabled.asStateFlow()

  private val _ttsSpeaking = MutableStateFlow(false)
  val ttsSpeaking = _ttsSpeaking.asStateFlow()

  // VAD state
  private val _isListening = MutableStateFlow(false)
  val isListening = _isListening.asStateFlow()

  private var ttsPlayer: StreamingTtsPlayer? = null
  private var vadRecorder: VadRecorder? = null
  private var appContext: Context? = null

  /** Initialize TTS. Must be called once with an application context. */
  fun initTts(context: Context) {
    if (ttsPlayer != null) return
    appContext = context.applicationContext
    val player = StreamingTtsPlayer(context.applicationContext)
    ttsPlayer = player

    // Wire the streaming hooks into the base class
    partialResultSink = { token -> onToken(token) }
    onGenerationStart = { this@LingLangTutorViewModel.onGenerationStart() }
    onGenerationEnd = { this@LingLangTutorViewModel.onGenerationEnd() }

    player.warmUp()

    // Start session: record start time and mark session active
    startSession()
  }

  /** Start a new learning session — records start time, resets turn count. */
  private fun startSession() {
    sessionStartTime = System.currentTimeMillis() / 1000
    sessionActive = true
    turnCount = 0
    responseBuffer.clear()
    Log.d(TAG, "Session started at $sessionStartTime")
  }

  /** Toggle TTS on/off. */
  fun toggleTts() {
    val enabled = !_ttsEnabled.value
    _ttsEnabled.value = enabled
    if (!enabled) {
      ttsPlayer?.stop()
      _ttsSpeaking.value = false
    }
  }

  /**
   * Metadata tag tracker for streaming TTS — suppresses `<lang-metadata>…</lang-metadata>`
   * blocks so they are never sent to the TTS engine.
   */
  private var insideMetadataTag = false

  /** Feed a streaming token to the TTS player. Strips `<lang-metadata>` blocks from TTS. */
  fun onToken(token: String) {
    // Always buffer the raw token for full-response parsing on generation end
    responseBuffer.append(token)

    if (_ttsEnabled.value) {
      // Simple metadata-stripping state machine:
      // - Outside metadata tag: pass tokens to TTS, watch for opening tag.
      // - Inside metadata tag: suppress tokens, watch for closing tag.
      val buffer = if (!insideMetadataTag) {
        val openIdx = token.indexOf("<lang-metadata>")
        if (openIdx >= 0) {
          insideMetadataTag = true
          // Everything before the tag goes to TTS
          token.substring(0, openIdx)
        } else {
          token
        }
      } else {
        val closeIdx = token.indexOf("</lang-metadata>")
        if (closeIdx >= 0) {
          insideMetadataTag = false
          // Everything after the closing tag goes to TTS
          token.substring(closeIdx + "</lang-metadata>".length)
        } else {
          "" // suppress — we're inside the metadata block
        }
      }
      if (buffer.isNotEmpty()) {
        ttsPlayer?.onToken(buffer)
      }
    }
  }

  /** Signal generation start to the TTS player. Resets metadata-stripping state. */
  fun onGenerationStart() {
    insideMetadataTag = false
    responseBuffer.clear()
    // Always forward generation lifecycle to TTS player so it tracks state
    // correctly, even if TTS is currently toggled off. Tokens are still gated
    // by _ttsEnabled, but the player needs to know when generations start/end.
    ttsPlayer?.onGenerationStart()
  }

  /**
   * Signal generation end.  Parses the accumulated response to extract
   * metadata, update SRS, and periodically update goals.
   */
  fun onGenerationEnd() {
    // Always forward — flushes any buffered text and resets player state.
    ttsPlayer?.onGenerationEnd()

    // Parse the full accumulated response for metadata
    processResponseMetadata()
  }

  /**
   * Parse the accumulated LLM response with LangMetadataParser,
   * convert errors → VoicePerformance → LexemeAnalysis, and update FSRS.
   * Also periodically calls updateGoals.
   */
  private fun processResponseMetadata() {
    val rawResponse = responseBuffer.toString()
    if (rawResponse.isBlank()) return

    // Reset buffer for next turn
    responseBuffer.clear()
    turnCount++

    val parsed: ParsedResponse
    try {
      parsed = LangMetadataParser.parse(rawResponse)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to parse LLM response metadata", e)
      return
    }

    val metadata = parsed.metadata
    if (metadata.errors.isNotEmpty()) {
      viewModelScope.launch {
        try {
          val cm = ensureContextManager()

          // Convert errors → VoicePerformance → LexemeAnalysis
          val performances = LangMetadataParser.toVoicePerformances(metadata)
          val analyses = performances.map { vp ->
            ContextManager.LexemeAnalysis(
              lemma = vp.wordForm,
              pos = "",           // POS not available from error stream
              performance = when (vp.errorType) {
                "recall_fail" -> "recall_fail"
                "native_substitution" -> "native_substitution"
                else -> "wrong_use"
              },
              language = null,
            )
          }

          // Update FSRS with analysis
          cm.updateSRSFromAnalysis(USER_ID, analyses)
          Log.d(TAG, "Updated SRS for ${analyses.size} lexeme analyses")

          // Update goals periodically
          if (turnCount % GOALS_UPDATE_INTERVAL_TURNS == 0) {
            val recentErrors = metadata.errors.map { error ->
              ContextManager.AnalysisError(
                lemma = error.word,
                grammarRule = error.type,
                grammarExample = null,
              )
            }
            val goalMsg = cm.updateGoals(USER_ID, recentErrors)
            if (goalMsg != null) {
              Log.d(TAG, "Goals updated: $goalMsg")
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error updating SRS/goals", e)
        }
      }
    } else {
      Log.d(TAG, "No errors in LLM response, turn $turnCount")
    }
  }

  /** Start VAD-based recording. Called when the mic button is pressed. */
  fun startListening() {
    if (_isListening.value) return
    _isListening.value = true

    val recorder = VadRecorder()
    vadRecorder = recorder
    recorder.start { pcmBytes ->
      _isListening.value = false
      Log.d(TAG, "VAD: recording complete, ${pcmBytes.size} bytes")
      // TODO: Convert PCM to WAV and send as audio message
      // This will be wired in the next iteration — for now the existing
      // manual record button still works as fallback.
    }
  }

  /** Stop VAD recording manually. */
  fun stopListening() {
    vadRecorder?.stop()
    _isListening.value = false
  }

  /** Change the target language and update the system prompt. */
  fun setLanguage(language: TutorLanguage, task: Task, model: Model) {
    _selectedLanguage.value = language
    ttsPlayer?.setVoice(language.kokoroVoiceId)

    // Build context-enriched system prompt
    val basePrompt = language.systemPromptSuffix
    val contextualPrompt = buildContextualSystemPrompt(basePrompt)
    _uiSystemPrompt.value = contextualPrompt
    viewModelScope.launch {
      systemPromptRepository?.updateSystemPrompt(task.id, basePrompt)
      resetSession(
        task = task,
        model = model,
        systemInstruction = Contents.of(contextualPrompt),
        supportImage = false,
        supportAudio = true,
      )
    }
  }

  override fun onCleared() {
    super.onCleared()
    ttsPlayer?.close()
    vadRecorder?.stop()

    // Write session summary on session end
    if (sessionActive && sessionStartTime > 0L) {
      endSession()
    }
  }

  /**
   * End the learning session: collect summary data and persist it.
   * Called from onCleared or when the user explicitly closes the session.
   */
  private fun endSession() {
    sessionActive = false
    val sessionEnd = System.currentTimeMillis() / 1000
    viewModelScope.launch {
      try {
        val cm = ensureContextManager()
        val summary = ContextManager.SessionSummaryData(
          sessionStart = sessionStartTime,
          sessionEnd = sessionEnd,
          topics = null,
          errorsSummary = null,
          wordsIntroduced = null,
          wordsStruggled = null,
          nextSessionHint = null,
        )
        cm.writeSessionSummary(USER_ID, summary)
        Log.d(TAG, "Session summary written for session $sessionStartTime")
      } catch (e: Exception) {
        Log.e(TAG, "Error writing session summary", e)
      }
    }
  }
}