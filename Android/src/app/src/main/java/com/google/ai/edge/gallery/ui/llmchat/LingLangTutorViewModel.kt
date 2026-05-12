package com.google.ai.edge.gallery.ui.llmchat

import android.util.Log
import androidx.datastore.core.DataStore
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.proto.UserData
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
) {
  SPANISH("Español", "es", Locale("es"), "You are a Spanish language tutor. Respond in Spanish. Correct mistakes gently and keep the conversation going."),
  FRENCH("Français", "fr", Locale("fr"), "You are a French language tutor. Respond in French. Correct mistakes gently and keep the conversation going."),
  GERMAN("Deutsch", "de", Locale("de"), "You are a German language tutor. Respond in German. Correct mistakes gently and keep the conversation going."),
  RUSSIAN("Русский", "ru", Locale("ru"), "You are a Russian language tutor. Respond in Russian. Correct mistakes gently and keep the conversation going."),
  JAPANESE("日本語", "ja", Locale("ja"), "You are a Japanese language tutor. Respond in Japanese. Correct mistakes gently and keep the conversation going."),
  PORTUGUESE("Português", "pt", Locale("pt"), "You are a Portuguese language tutor. Respond in Portuguese. Correct mistakes gently and keep the conversation going."),
  CHINESE("中文", "zh", Locale("zh"), "You are a Chinese (Mandarin) language tutor. Respond in simplified Chinese. Correct mistakes gently and keep the conversation going."),
  KOREAN("한국어", "ko", Locale("ko"), "You are a Korean language tutor. Respond in Korean. Correct mistakes gently and keep the conversation going."),
  ITALIAN("Italiano", "it", Locale("it"), "You are an Italian language tutor. Respond in Italian. Correct mistakes gently and keep the conversation going."),
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

  /** Change the target language and update the system prompt. */
  fun setLanguage(language: TutorLanguage, task: com.google.ai.edge.gallery.data.Task, model: Model) {
    _selectedLanguage.value = language

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
}