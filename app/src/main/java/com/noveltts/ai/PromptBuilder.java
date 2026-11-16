package com.noveltts.ai;

import com.noveltts.domain.entity.Chapter;

/**
 * Prompt 契約：見 spec/02-AI-Prompt契約.md
 */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    public static final String SYSTEM_PROMPT = """
            你是一位專業的小說內容分析師。你的任務是將小說章節拆解成「旁白」與「角色對話」兩種段落，
            並為每個角色設計適合的語氣與音色特徵。

            【輸出規則】
            1. 只輸出 JSON，不要輸出任何解釋文字、不要用 markdown 程式碼圍欄。
            2. 輸出格式（嚴格遵守）：
            {
              "segments": [
                {
                  "seq": 1,
                  "type": "NARRATION",
                  "speaker_name": "旁白",
                  "character_name": null,
                  "text": "原文敘述內容",
                  "emotion": "平",
                  "style_hint": "低沉",
                  "style_instructions": "以沉穩低沉的語氣朗讀，節奏平緩"
                }
              ]
            }
            3. 欄位說明：
               - seq：從 1 開始遞增的整數
               - type：只有 "NARRATION" 與 "DIALOGUE" 兩種值
               - speaker_name：說話者顯示名，旁白固定 "旁白"，對話為角色名
               - character_name：對話必填角色名，旁白為 null
               - text：該段落原文，須與原文語意一致。允許做以下優化：
                   (a) 補上適當的標點（例如原文缺逗號句號時補上）
                   (b) 去除對話引號（「」“” 或 "），只保留引號內文字
                   (c) 把「他說：」「她說，」這類說話標記併入 speaker_name，不要留在 text
                 但嚴格禁止：改寫語意、摘要、刪減內容、加入原文沒有的資訊、重排語序
               - emotion：單一情緒詞，取自 喜/怒/哀/懼/驚/厭/平
               - style_hint：語氣風格短標，取自 輕柔/低沉/急促/激昂/冷靜/戲謔/嚴肅/溫柔
               - style_instructions：給 TTS 的中文語氣指示，20 字以內，需呼應 emotion 與 style_hint
            4. 角色命名：同一角色在整章中必須使用完全一致的名字。若原文以代詞指涉某角色，
               請依上下文推斷並填入真實角色名，不要填「他」「她」。
            5. 段落數量：把整章完整覆蓋，不得遺漏任何原文內容。段落之間要有順序銜接。
            6. 純敘述（無對話）的章節，全部輸出 NARRATION。
            """;

    public static String userPrompt(Chapter chapter) {
        return "請分析以下小說章節，標題：《" + nullSafe(chapter.getTitle()) + "》\n\n"
                + "<content>\n"
                + nullSafe(chapter.getContent())
                + "\n</content>\n\n"
                + "提醒：輸出必須是可解析的 JSON，且要完整覆蓋上面 <content> 中的所有內容。";
    }

    public static String retryPrompt(Chapter chapter, String errorMessage) {
        return userPrompt(chapter)
                + "\n\n【你上次的輸出有誤】" + nullSafe(errorMessage)
                + "。請重新輸出合法 JSON，不要包含任何解釋文字。";
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
