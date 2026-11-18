package com.noveltts.ai;

/**
 * 小說生成的 prompt 契約。
 *
 * <p>與 {@link PromptBuilder}（分析既有章節）不同，這裡是「從零寫出新章節」：
 * 輸入是使用者的想法、章節位置與字數範圍，輸出是該章的完整正文。
 *
 * <p>設計重點：
 * <ul>
 *   <li>逐章生成而非一次生成整本 —— 長輸出容易逾時或被截斷，
 *       逐章也能讓使用者中途停下來。</li>
 *   <li>把前一章的结尾與整體大綱帶進 prompt，維持劇情連貫。</li>
 *   <li>字數範圍是「目標」而非「硬限制」：模型無法保證精準命中，
 *       但明確的上下限比單一數字有效得多。</li>
 * </ul>
 */
public final class GenerationPromptBuilder {

    private GenerationPromptBuilder() {
    }

    /** 生成系統提示詞。 */
    public static final String SYSTEM_PROMPT = """
            你是一位小說作家。依據使用者提供的設定創作小說章節正文。

            【輸出規則】
            1. 只輸出章節正文，不要輸出解釋、備註或 markdown 程式碼圍欄。
            2. 不要輸出章節標題（標題由系統另外處理），直接從正文第一句開始寫。
            3. 用繁體中文書寫。
            4. 正文要能獨立閱讀：場景、人物動作、對話都要具體，
               不要用「接下來發生了許多事」這類概括帶過。
            5. 對話要自然，標點正確；同一角色的稱呼與語氣在全章保持一致。
            6. 嚴格遵守指定的字數範圍：落在範圍內，最好接近中值。
            """;

    /**
     * 組出單一章節的生成 prompt。
     *
     * @param idea 使用者的想法／設定
     * @param chapterNumber 第幾章（從 1 開始）
     * @param totalChapters 總章數
     * @param minChars 字數下限
     * @param maxChars 字數上限
     * @param outline 整體大綱（可為 null）
     * @param previousEnding 上一章結尾摘錄（可為 null）
     * @param extra 額外指示（可為 null）
     */
    public static String chapterPrompt(String idea,
                                       int chapterNumber,
                                       int totalChapters,
                                       int minChars,
                                       int maxChars,
                                       String outline,
                                       String previousEnding,
                                       String extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("【創作設定】\n");
        sb.append(idea).append('\n');
        sb.append('\n');

        sb.append("【本章任務】\n");
        sb.append("這是第 ").append(chapterNumber).append(" 章，共 ")
                .append(totalChapters).append(" 章。\n");
        sb.append("本章篇幅：").append(minChars).append(" 至 ").append(maxChars)
                .append(" 字（以繁體中文字元計），請落在這個範圍內。\n");
        if (extra != null && !extra.isBlank()) {
            sb.append("額外指示：").append(extra.trim()).append('\n');
        }
        sb.append('\n');

        if (outline != null && !outline.isBlank()) {
            sb.append("【整體大綱】\n");
            sb.append(outline.trim()).append('\n');
            sb.append('\n');
        }

        if (previousEnding != null && !previousEnding.isBlank()) {
            sb.append("【上一章結尾】\n");
            sb.append(previousEnding.trim()).append('\n');
            sb.append("請從這裡自然接續，不要重複上一章已經寫過的內容。\n");
            sb.append('\n');
        }

        sb.append("請開始寫本章正文。");
        return sb.toString();
    }

    /**
     * 組出大綱的生成 prompt。
     *
     * @param idea 使用者的想法
     * @param chapterCount 章節數
     * @param minChars 每章字數下限
     * @param maxChars 每章字數上限
     */
    public static String outlinePrompt(String idea,
                                        int chapterCount,
                                        int minChars,
                                        int maxChars) {
        return "【創作設定】\n"
                + idea + "\n\n"
                + "【任務】\n"
                + "請先擬一份全書大綱，包含 " + chapterCount + " 章，每章一行，格式為：\n"
                + "第N章 章節標題 —— 一句話說明這章發生什麼\n\n"
                + "每章篇幅約 " + minChars + " 至 " + maxChars + " 字。\n"
                + "章節之間要有因果推進，不要重複同一件事。\n"
                + "只輸出大綱本身，不要寫正文。";
    }
}
