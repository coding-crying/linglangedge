package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.proto.UserData
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "LingLangTutor"

/** Mapping from TutorLanguage to Kokoro voice configuration. */
private val TutorLanguage.kokoroVoice: KokoroVoiceConfig
  get() = when (this) {
    TutorLanguage.SPANISH -> KokoroVoiceConfig.ES
    TutorLanguage.FRENCH -> KokoroVoiceConfig.FR
    TutorLanguage.GERMAN -> KokoroVoiceConfig.DE
    TutorLanguage.JAPANESE -> KokoroVoiceConfig.JA
    TutorLanguage.PORTUGUESE -> KokoroVoiceConfig.PT
    TutorLanguage.CHINESE -> KokoroVoiceConfig.ZH
    TutorLanguage.KOREAN -> KokoroVoiceConfig.KO
    TutorLanguage.ITALIAN -> KokoroVoiceConfig.IT
  }

/** Supported target languages for the LingLang tutor. */
enum class TutorLanguage(
  val displayName: String,
  val code: String,
  val locale: Locale,
  val systemPromptSuffix: String,
) {
  SPANISH("Español", "es", Locale("es"), "You are a friendly Spanish language tutor. Respond ONLY in Spanish. Correct mistakes gently. Keep conversation natural and engaging. Ask follow-up questions to keep the learner practicing."),
  FRENCH("Français", "fr", Locale("fr"), "You are a friendly French language tutor. Respond ONLY in French. Correct mistakes gently. Keep conversation natural and engaging. Ask follow-up questions to keep the learner practicing."),
  GERMAN("Deutsch", "de", Locale("de"), "You are a friendly German language tutor. Respond ONLY in German. Correct mistakes gently. Keep conversation natural and engaging. Ask follow-up questions to keep the learner practicing."),
  JAPANESE("日本語", "ja", Locale("ja"), "You are a friendly Japanese language tutor. Respond ONLY in Japanese. Correct mistakes gently. Keep conversation natural and engaging. Ask follow-up questions to keep the learner practicing."),
  PORTUGUESE("Português", "pt", Locale("pt"), "You are a friendly Portuguese language tutor. Respond ONLY in Portuguese. Correct mistakes gently. Keep conversation natural and engaging. Ask follow-up questions to keep the learner practicing."),
  CHINESE("中文", "zh", Locale("zh"), "You are a friendly Chinese (Mandarin) language tutor. Respond ONLY in simplified Chinese. Correct mistakes gently. Keep conversation natural and engaging. Ask follow-up questions to keep the learner practicing."),
  KOREAN("한국어", "ko", Locale("ko"), "You are a friendly Korean language tutor. Respond ONLY in Korean. Correct mistakes gently. Keep conversation natural and engaging. Ask follow-up questions to keep the learner practicing."),
  ITALIAN("Italiano", "it", Locale("it"), "You are a friendly Italian language tutor. Respond ONLY in Italian. Correct mistakes gently. Keep conversation natural and engaging. Ask follow-up questions to keep the learner practicing."),
}

@HiltViewModel
class LingLangTutorViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  userDataDataStore: DataStore<UserData>,
) : LlmChatViewModelBase(systemPromptRepository, userDataDataStore) {

  private val _selectedLanguage = MutableStateFlow(TutorLanguage.SPANISH)
  val selectedLanguage = _selectedLanguage.asStateFlow()

  private val _isSpeaking = MutableStateFlow(false)
  /** Whether TTS is currently speaking. Observable by UI. */
  val isSpeaking = _isSpeaking.asStateFlow()
  /** Whether the Kokoro server is reachable (for UI indicator). */
  val isKokoroAvailable = MutableStateFlow(false)

  private var _kokoroTts: KokoroTtsService? = null
  private var _context: Context? = null

  /** Initialize TTS service. Call from screen's ApplicationContext. */
  fun initTts(context: Context) {
    if (_kokoroTts != null) return
    _context = context.applicationContext
    val tts = KokoroTtsService(context.applicationContext)
    _kokoroTts = tts
    tts.init()

    // Observe Kokoro speaking state → relay to our own StateFlow
    viewModelScope.launch {
      tts.isSpeaking.collect { speaking ->
        _isSpeaking.value = speaking
      }
    }
    // Observe server availability for UI indicator
    viewModelScope.launch {
      tts.isServerAvailable.collect { available ->
        isKokoroAvailable.value = available
        Log.d(TAG, "Kokoro server available: $available")
      }
    }
  }

  /** Change the target language and update the system prompt + TTS. */
  fun setLanguage(language: TutorLanguage, task: com.google.ai.edge.gallery.data.Task, model: Model) {
    _selectedLanguage.value = language

    // Update the system prompt to match new language
    val newPrompt = language.systemPromptSuffix
    _uiSystemPrompt.value = newPrompt
    viewModelScope.launch {
      systemPromptRepository?.updateSystemPrompt(task.id, newPrompt)
      resetSession(
        task = task,
        model = model,
        systemInstruction = com.google.ai.edge.litertlm.Contents.of(newPrompt),
        supportImage = false,
        supportAudio = true,
      )
    }
  }

  /** Speak the last agent text message aloud using Kokoro TTS. */
  fun speakLastResponse(model: Model) {
    val messages = uiState.value.messagesByModel[model.name] ?: emptyList()
    val lastAgentText = messages.lastOrNull {
      it.type == ChatMessageType.TEXT && it.side == ChatSide.AGENT
    } as? ChatMessageText ?: return

    speakText(lastAgentText.content)
  }

  /** Speak arbitrary text using Kokoro (server) or Android TTS (fallback). */
  fun speakText(text: String) {
    val lang = _selectedLanguage.value
    val tts = _kokoroTts ?: return
    viewModelScope.launch {
      tts.speak(text, lang.kokoroVoice, lang.locale)
    }
  }

  /** Stop any ongoing TTS speech. */
  fun stopSpeaking() {
    _kokoroTts?.stopSpeaking()
  }

  /** Update the Kokoro server URL. */
  fun updateKokoroServerUrl(url: String) {
    _kokoroTts?.updateServerUrl(url)
    viewModelScope.launch {
      _kokoroTts?.checkServerAvailability()
    }
  }

  /** Clean up TTS resources. */
  fun destroyTts() {
    _kokoroTts?.destroy()
  }

  override fun onCleared() {
    super.onCleared()
    destroyTts()
  }
}