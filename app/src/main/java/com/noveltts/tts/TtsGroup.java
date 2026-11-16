package com.noveltts.tts;

import com.noveltts.domain.entity.DialogueSegment;

import java.util.List;

/**
 * 一組要合併成單次 TTS 請求的段落。
 */
public record TtsGroup(List<DialogueSegment> segments, List<Turn> turns, int charCount) {

    /**
     * @param text         該 turn 合併後的文字
     * @param voice        音色 voice_id
     * @param instructions 語氣指令
     */
    public record Turn(String text, String voice, String instructions) {
    }
}
