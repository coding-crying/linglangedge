package com.google.ai.edge.gallery.ui.llmchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.ui.theme.emptyStateContent
import com.google.ai.edge.gallery.ui.theme.emptyStateTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LingLangTutorScreen(
  modelManagerViewModel: com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LingLangTutorViewModel = hiltViewModel(),
) {
  val selectedLanguage by viewModel.selectedLanguage.collectAsState()
  val uiSystemPrompt by viewModel.uiSystemPrompt.collectAsState()
  val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)

  // Load system prompt for the LingLang task
  val task = remember {
    modelManagerViewModel.getTaskById(BuiltInTaskId.LINGLANG_TUTOR)
  }

  LaunchedEffect(task) {
    if (task != null) {
      viewModel.loadSystemPrompt(task)
    }
  }

  // Language picker dropdown state
  var languageExpanded by remember { mutableStateOf(false) }

  Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
      // Language switcher bar
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Icon(
          imageVector = Icons.Filled.Translate,
          contentDescription = "Language",
          modifier = Modifier.padding(end = 8.dp),
        )
        Text(
          text = stringResource(R.string.linglang_tutor_language_label),
          style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.width(8.dp))

        ExposedDropdownMenuBox(
          expanded = languageExpanded,
          onExpandedChange = { languageExpanded = !languageExpanded },
        ) {
          OutlinedTextField(
            value = selectedLanguage.displayName,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
          )
          ExposedDropdownMenu(
            expanded = languageExpanded,
            onDismissRequest = { languageExpanded = false },
          ) {
            TutorLanguage.entries.forEach { lang ->
              DropdownMenuItem(
                text = { Text(lang.displayName) },
                onClick = {
                  languageExpanded = false
                  if (lang != selectedLanguage && task != null) {
                    val selectedModel = modelManagerViewModel.uiState.value.selectedModel
                    if (selectedModel != null) {
                      viewModel.setLanguage(lang, task, selectedModel)
                    }
                  }
                },
              )
            }
          }
        }
      }

      // Chat UI — reuses the existing ChatViewWrapper with audio support
      ChatViewWrapper(
        viewModel = viewModel,
        modelManagerViewModel = modelManagerViewModel,
        taskId = BuiltInTaskId.LINGLANG_TUTOR,
        navigateUp = navigateUp,
        allowEditingSystemPrompt = true,
        curSystemPrompt = uiSystemPrompt,
        onSystemPromptChanged = { newPrompt ->
          val selectedModel = modelManagerViewModel.uiState.value.selectedModel
          if (task != null && selectedModel != null) {
            viewModel.applySystemPromptChange(
              task = task,
              model = selectedModel,
              newPrompt = newPrompt,
              systemPromptUpdatedMessage = systemPromptUpdatedMessage,
            )
          }
        },
        showImagePicker = false,
        showAudioPicker = true,
        emptyStateComposable = { model ->
          Box(modifier = Modifier.fillMaxSize()) {
            Column(
              modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 48.dp)
                .padding(bottom = 48.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Text(
                stringResource(R.string.linglang_tutor_emptystate_title),
                style = emptyStateTitle,
              )
              Text(
                stringResource(R.string.linglang_tutor_emptystate_content),
                style = emptyStateContent,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          }
        },
      )
    }
  }
}