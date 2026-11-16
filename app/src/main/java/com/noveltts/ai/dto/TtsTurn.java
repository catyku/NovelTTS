package com.noveltts.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * TTS multi-speaker input 的單一 turn。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TtsTurn(String text, String voice, String instructions) {
}
