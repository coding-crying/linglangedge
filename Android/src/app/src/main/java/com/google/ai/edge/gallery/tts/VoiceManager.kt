package com.google.ai.edge.gallery.tts

import java.util.Locale

data class VoiceInfo(
    val id: String,
    val locale: Locale,
) {
    override fun toString(): String = "$id ($locale)"
}

val ALL_VOICES: List<VoiceInfo> = listOf(
    // ── English (US) ──────────────────────────────────────────────────────
    VoiceInfo("en-US-alloy-kokoro",       Locale.US),
    VoiceInfo("en-US-heart-kokoro",       Locale.US),
    VoiceInfo("en-US-nova-kokoro",        Locale.US),
    VoiceInfo("en-US-santa-kokoro",       Locale.US),
    // ── English (GB) ──────────────────────────────────────────────────────
    VoiceInfo("en-GB-alice-kokoro",        Locale.UK),
    // ── German ─────────────────────────────────────────────────────────────
    VoiceInfo("de-DE-dora-kokoro",         Locale.GERMANY),
    // ── French ─────────────────────────────────────────────────────────────
    VoiceInfo("fr-FR-siwis-kokoro",        Locale.FRANCE),
    // ── Japanese ───────────────────────────────────────────────────────────
    VoiceInfo("ja-JP-alpha-f-kokoro",      Locale.JAPAN),
    // ── Portuguese (BR) ────────────────────────────────────────────────────
    VoiceInfo("pt-BR-dora-kokoro",         Locale("pt", "BR")),
    // ── Chinese ────────────────────────────────────────────────────────────
    VoiceInfo("zh-CN-xiaoxiao-kokoro",     Locale.SIMPLIFIED_CHINESE),
    // ── Italian ────────────────────────────────────────────────────────────
    VoiceInfo("it-IT-sara-kokoro",         Locale.ITALY),
)

fun findVoiceById(id: String): VoiceInfo? = ALL_VOICES.find { it.id == id }

const val DEFAULT_VOICE_ID = "en-US-heart-kokoro"