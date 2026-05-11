package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
  val isSpeaking = _isSpeaking.asStateFlow()

  private var tts: TextToSpeech? = null
  private var ttsInitialized = false

  /** Initialize Android TTS engine. Call from screen's ApplicationContext. */
  fun initTts(context: Context) {
    if (tts != null) return
    tts = TextToSpeech(context.applicationContext, { status ->
      if (status == TextToSpeech.SUCCESS) {
        ttsInitialized = true
        updateTtsLanguage()
        Log.d(TAG, "TTS initialized successfully")
      } else {
        Log.e(TAG, "TTS initialization failed: status=$status")
      }
    })
  }

  /** Update TTS language to match selected tutor language. */
  private fun updateTtsLanguage() {
    val engine = tts ?: return
    val locale = _selectedLanguage.value.locale
    val result = engine.setLanguage(locale)
    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
      Log.w(TAG, "TTS language ${locale.displayLanguage} not supported, falling back to default")
      engine.setLanguage(Locale.getDefault())
    }
    engine.setSpeechRate(0.9f) // Slightly slower for language learners
  }

  /** Change the target language and update the system prompt + TTS. */
  fun setLanguage(language: TutorLanguage, task: com.google.ai.edge.gallery.data.Task, model: Model) {
    _selectedLanguage.value = language
    updateTtsLanguage()

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

  /** Speak the last agent text message aloud using TTS. */
  fun speakLastResponse(model: Model) {
    val messages = uiState.value.messagesByModel[model.name] ?: emptyList()
    val lastAgentText = messages.lastOrNull {
      it.type == ChatMessageType.TEXT && it.side == ChatSide.AGENT
    } as? ChatMessageText ?: return

    speakText(lastAgentText.content)
  }

  /** Speak arbitrary text. */
  fun speakText(text: String) {
    val engine = tts
    if (engine == null || !ttsInitialized) {
      Log.w(TAG, "TTS not initialized, cannot speak")
      return
    }

    // Stop any ongoing speech
    engine.stop()

    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) {
        _isSpeaking.value = true
      }
      override fun onDone(utteranceId: String?) {
        _isSpeaking.value = false
      }
      override fun onError(utteranceId: String?) {
        _isSpeaking.value = false
      }
    })

    val utteranceId = "linglang_${System.currentTimeMillis()}"
    engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
  }

  /** Stop any ongoing TTS speech. */
  fun stopSpeaking() {
    tts?.stop()
    _isSpeaking.value = false
  }

  /** Clean up TTS resources. */
  fun destroyTts() {
    tts?.stop()
    tts?.shutdown()
    tts = null
    ttsInitialized = false
  }

  override fun onCleared() {
    super.onCleared()
    destroyTts()
  }
}