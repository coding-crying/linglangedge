// SPDX-FileCopyrightText: 2025 LingLang
//
// SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.linglang

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.ui.theme.GalleryTheme

// ============================================================================
// OnboardingActivity
// ============================================================================

/**
 * Entry point for new LingLang users.
 *
 * Checks [PREFS_NAME] / [KEY_CALIBRATION_DONE] to determine whether the user
 * has already completed calibration. If so, immediately redirects to
 * [MainActivity]. Otherwise, displays the three-step calibration flow:
 *
 * 1. **SELECT_LEVEL** – CEFR level picker (A1–C2 grid)
 * 2. **WORD_RECOGNITION** – rapid word test with Know / Don't Know buttons
 * 3. **COMPLETE** – summary + "Start Learning" button
 */
class OnboardingActivity : ComponentActivity() {

    private val calibrationViewModel: CalibrationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val alreadyCalibrated = prefs.getBoolean(KEY_CALIBRATION_DONE, false)

        if (alreadyCalibrated) {
            // Skip onboarding, go straight to main app
            navigateToMain()
            return
        }

        enableEdgeToEdge()

        setContent {
            GalleryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val uiState by calibrationViewModel.uiState.collectAsState()

                    // When seeding completes successfully, persist the flag and navigate
                    if (uiState.step == CalibrationStep.COMPLETE && uiState.seedResult != null) {
                        LaunchedEffect(Unit) {
                            prefs.edit()
                                .putBoolean(KEY_CALIBRATION_DONE, true)
                                .apply()
                        }
                    }

                    CalibrationFlow(
                        uiState = uiState,
                        viewModel = calibrationViewModel,
                        onStartLearning = { navigateToMain() },
                    )
                }
            }
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            // Clear the onboarding from the back stack
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    companion object {
        const val PREFS_NAME = "linglang_prefs"
        const val KEY_CALIBRATION_DONE = "calibration_done"
    }
}

// ============================================================================
// CEFR Level descriptions (used in Step 1)
// ============================================================================

private data class CefrLevelInfo(
    val level: CefrLevel,
    val title: String,
    val description: String,
)

private val CEFR_LEVELS = listOf(
    CefrLevelInfo(CefrLevel.A1, "A1", "Beginner\nBasic words & phrases"),
    CefrLevelInfo(CefrLevel.A2, "A2", "Elementary\nSimple conversations"),
    CefrLevelInfo(CefrLevel.B1, "B1", "Intermediate\nEveryday topics"),
    CefrLevelInfo(CefrLevel.B2, "B2", "Upper Intermediate\nFluent discussions"),
    CefrLevelInfo(CefrLevel.C1, "C1", "Advanced\nComplex texts"),
    CefrLevelInfo(CefrLevel.C2, "C2", "Proficient\nNear-native mastery"),
)

// ============================================================================
// Top-level calibration flow composable
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalibrationFlow(
    uiState: CalibrationUiState,
    viewModel: CalibrationViewModel,
    onStartLearning: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (uiState.step) {
                            CalibrationStep.SELECT_LEVEL -> "Choose Your Level"
                            CalibrationStep.WORD_RECOGNITION -> "Word Check"
                            CalibrationStep.SEEDING -> "Setting Up…"
                            CalibrationStep.COMPLETE -> "All Done!"
                            CalibrationStep.CONVERSATION -> "Ready!"
                        }
                    )
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AnimatedContent(
                targetState = uiState.step,
                transitionSpec = {
                    slideInHorizontally(initialOffsetX = { it }) togetherWith
                        slideOutHorizontally(targetOffsetX = { -it })
                },
                label = "calibration-step",
            ) { step ->
                when (step) {
                    CalibrationStep.SELECT_LEVEL -> SelectLevelStep(
                        uiState = uiState,
                        onSelectLevel = { viewModel.selectLevel(it) },
                    )
                    CalibrationStep.WORD_RECOGNITION -> WordRecognitionStep(
                        uiState = uiState,
                        onMarkKnown = { viewModel.markKnown() },
                        onMarkUnknown = { viewModel.markUnknown() },
                        onSkip = { viewModel.skipWordRecognition() },
                    )
                    CalibrationStep.SEEDING -> SeedingStep()
                    CalibrationStep.COMPLETE -> CompleteStep(
                        uiState = uiState,
                        onStartLearning = {
                            viewModel.startConversationPhase()
                            onStartLearning()
                        },
                    )
                    CalibrationStep.CONVERSATION -> {
                        // Transient state — the activity should have launched by now
                        SeedingStep()
                    }
                }
            }

            // Error overlay
            uiState.error?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.BottomCenter),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

// ============================================================================
// Step 1: CEFR Level Picker
// ============================================================================

@Composable
private fun SelectLevelStep(
    uiState: CalibrationUiState,
    onSelectLevel: (CefrLevel) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "What's your current level?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Pick the level that best describes your abilities. " +
                "We'll calibrate from there.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(CEFR_LEVELS) { info ->
                CefrLevelCard(
                    info = info,
                    selected = uiState.selectedLevel == info.level,
                    onClick = { onSelectLevel(info.level) },
                )
            }
        }
    }
}

@Composable
private fun CefrLevelCard(
    info: CefrLevelInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = info.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = info.description,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

// ============================================================================
// Step 2: Rapid Word Recognition
// ============================================================================

@Composable
private fun WordRecognitionStep(
    uiState: CalibrationUiState,
    onMarkKnown: () -> Unit,
    onMarkUnknown: () -> Unit,
    onSkip: () -> Unit,
) {
    val words = uiState.wordList
    val currentIndex = uiState.currentIndex
    val totalWords = if (uiState.totalWords > 0) uiState.totalWords else 1
    val progress = (currentIndex.toFloat()) / totalWords

    // Handle empty word list — still loading
    if (words.isEmpty() || currentIndex >= words.size) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Loading words…",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        return
    }

    val currentWord = words[currentIndex]
    val displayWord = currentWord.word

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Progress bar
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
        )

        Text(
            text = "${currentIndex + 1} of $totalWords",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Word card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = displayWord,
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Know / Don't Know buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(
                onClick = onMarkUnknown,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
            ) {
                Text(text = "Don't Know", style = MaterialTheme.typography.titleMedium)
            }
            Button(
                onClick = onMarkKnown,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
            ) {
                Text(text = "Know", style = MaterialTheme.typography.titleMedium)
            }
        }

        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Skip remaining")
        }
    }
}

// ============================================================================
// Step 2.5: Seeding (indeterminate progress)
// ============================================================================

@Composable
private fun SeedingStep() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            Text(
                text = "Setting up your vocabulary…",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "This won't take long.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ============================================================================
// Step 3: Completion screen
// ============================================================================

@Composable
private fun CompleteStep(
    uiState: CalibrationUiState,
    onStartLearning: () -> Unit,
) {
    val seedResult = uiState.seedResult

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "🎉 You're all set!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Summary card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Vocabulary seeded",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (seedResult != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Words you know",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "${seedResult.knownWordsSeeded}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "New words to learn",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "${seedResult.newWordsSeeded}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Total seed words",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "${seedResult.totalWordsSeeded}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                } else {
                    Text(
                        "Your vocabulary has been prepared for learning.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onStartLearning,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(
                text = "Start Learning",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}