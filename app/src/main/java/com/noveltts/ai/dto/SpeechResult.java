package com.noveltts.ai.dto;

/**
 * TTS 回應結果。
 */
public record SpeechResult(byte[] audio, String contentType, String generationId) {
}
