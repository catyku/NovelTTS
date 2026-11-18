package com.noveltts.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GenerationPromptBuilder} 的 prompt 組裝測試。
 */
class GenerationPromptBuilderTest {

    @Test
    @DisplayName("章節 prompt 帶入想法、章序與字數範圍")
    void chapterPromptContainsCoreFields() {
        String prompt = GenerationPromptBuilder.chapterPrompt(
                "一名失憶的書店老闆", 3, 10, 800, 1500, null, null, null);

        assertTrue(prompt.contains("一名失憶的書店老闆"), "應帶入創作想法");
        assertTrue(prompt.contains("第 3 章"), "應帶入章序");
        assertTrue(prompt.contains("10 章"), "應帶入總章數");
        assertTrue(prompt.contains("800"), "應帶入字數下限");
        assertTrue(prompt.contains("1500"), "應帶入字數上限");
    }

    @Test
    @DisplayName("大綱與上一章結尾有出現時才加入 prompt")
    void optionalContextOnlyWhenPresent() {
        String withoutContext = GenerationPromptBuilder.chapterPrompt(
                "想法", 1, 3, 100, 200, null, null, null);
        assertFalse(withoutContext.contains("【整體大綱】"), "沒有大綱時不應出現大綱區塊");
        assertFalse(withoutContext.contains("【上一章結尾】"), "沒有前章時不應出現前章區塊");

        String withContext = GenerationPromptBuilder.chapterPrompt(
                "想法", 1, 3, 100, 200,
                "第1章 開端 —— 他收到信", "他讀完那封信，久久沒有說話。", null);
        assertTrue(withContext.contains("【整體大綱】"), "應出現大綱區塊");
        assertTrue(withContext.contains("他收到信"), "應帶入大綱內容");
        assertTrue(withContext.contains("【上一章結尾】"), "應出現前章區塊");
        assertTrue(withContext.contains("久久沒有說話"), "應帶入前章結尾內容");
    }

    @Test
    @DisplayName("額外指示有值時才加入")
    void extraInstructionOnlyWhenPresent() {
        String without = GenerationPromptBuilder.chapterPrompt(
                "想法", 1, 3, 100, 200, null, null, null);
        assertFalse(without.contains("額外指示"), "沒有額外指示時不應出現");

        String with = GenerationPromptBuilder.chapterPrompt(
                "想法", 1, 3, 100, 200, null, null, "多用短句");
        assertTrue(with.contains("額外指示"), "應出現額外指示");
        assertTrue(with.contains("多用短句"), "應帶入額外指示內容");
    }

    @Test
    @DisplayName("大綱 prompt 帶入章數與字數範圍")
    void outlinePromptContainsChapterCount() {
        String prompt = GenerationPromptBuilder.outlinePrompt("懸疑故事", 5, 1000, 2000);
        assertTrue(prompt.contains("懸疑故事"), "應帶入想法");
        assertTrue(prompt.contains("5 章"), "應帶入章數");
        assertTrue(prompt.contains("1000"), "應帶入字數下限");
        assertTrue(prompt.contains("2000"), "應帶入字數上限");
    }

    @Test
    @DisplayName("系統提示詞要求只輸出正文且不輸出標題")
    void systemPromptRules() {
        String sys = GenerationPromptBuilder.SYSTEM_PROMPT;
        assertTrue(sys.contains("只輸出章節正文"), "應要求只輸出正文");
        assertTrue(sys.contains("不要輸出章節標題"), "應要求不輸出標題");
        assertTrue(sys.contains("繁體中文"), "應指定語言");
    }
}
