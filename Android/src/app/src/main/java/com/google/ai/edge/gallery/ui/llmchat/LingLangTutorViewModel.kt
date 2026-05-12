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
  SPANISH("Español", "es", Locale("es"), "Respond in Spanish."),
  FRENCH("Français", "fr", Locale("fr"), "Respond in French."),
  GERMAN("Deutsch", "de", Locale("de"), "Respond in German."),
  RUSSIAN("Русский", "ru", Locale("ru"), "Respond in Russian."),
  JAPANESE("日本語", "ja", Locale("ja"), "Respond in Japanese."),
  PORTUGUESE("Português", "pt", Locale("pt"), "Respond in Portuguese."),
  CHINESE("中文", "zh", Locale("zh"), "Respond in simplified Chinese."),
  KOREAN("한국어", "ko", Locale("ko"), "Respond in Korean."),
  ITALIAN("Italiano", "it", Locale("it"), "Respond in Italian."),
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