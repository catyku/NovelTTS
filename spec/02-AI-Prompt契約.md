# 02 — AI Prompt 契約與 TTS 請求格式

本文件定義「分析」與「合成」兩段 AI 互動的完整格式契約。
後端程式碼必須依本文件實作；若實作中需要偏離，先改本文件再改程式。

## 1. 分析階段（Chat Completions）

### 1.1 端點
```
POST https://openrouter.ai/api/v1/chat/completions
Authorization: Bearer <api_key>
HTTP-Referer: <extra_headers.HTTP-Referer 或預設>
X-Title: <extra_headers.X-Title 或預設 novel-tts>
Content-Type: application/json
```

### 1.2 請求體
```json
{
  "model": "google/gemini-3-flash-preview",
  "messages": [
    { "role": "system", "content": "<System Prompt 全文，見 1.3>" },
    { "role": "user",   "content": "<使用者提示，見 1.4>" }
  ],
  "temperature": 0.2,
  "max_tokens": 8000,
  "response_format": { "type": "json_object" }
}
```

> `temperature` 與 `max_tokens` 取自 `ai_model_setting`（可在設定頁調整）。
> `response_format` 若目標模型不支援 `json_object`，退為「純文字要求 + 後端去圍欄解析」。

### 1.3 System Prompt 全文
```text
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
      "text": "原文敘述內容，必須與原文一致，不要改寫或摘要",
      "emotion": "平",
      "style_hint": "低沉",
      "style_instructions": "以沉穩低沉的語氣朗讀，節奏平緩"
    }
  ]
}
3. 欄位說明：
   - seq：從 1 開始遞增的整數
   - type：只有 "NARRUE" 與 "DIALOGUE" 兩種值（其餘一律 "NARRATION"）
   - speaker_name：說話者顯示名，旁白固定 "旁白"，對話為角色名
   - character_name：對話必填角色名，旁白為 null
   - text：該段落原文，逐字保留（含標點），不要加引號包裹
   - emotion：單一情緒詞，取自 喜/怒/哀/懼/驚/厭/平
   - style_hint：語氣風格短標，取自 輕柔/低沉/急促/激昂/冷靜/戲謔/嚴肅/溫柔
   - style_instructions：給 TTS 的中文語氣指示，20 字以內，需呼應 emotion 與 style_hint
4. 角色命名：同一角色在整章中必須使用完全一致的名字。若原文以代詞指涉某角色，
   請依上下文推斷並填入真實角色名，不要填「他」「她」。
5. 對話引號：原文的對話引號（「」“” 或 "）在 text 中去除，只保留引號內文字。
6. 旁白段落可適度合併短句，但不得改寫、刪減或顛倒原文語序。
7. 段落數量：把整章完整覆蓋，不得遺漏任何原文內容。段落之間要有順序銜接。
8. 純敘述（無對話）的章節，全部輸出 NARRATION。
```

### 1.4 使用者提示（每章組裝）
```text
請分析以下小說章節，標題：《{chapter_title}》

<content>
{chapter_content}
</content>

提醒：輸出必須是可解析的 JSON，且要完整覆蓋上面 <content> 中的所有內容。
```

### 1.5 輸出 JSON Schema（後端驗證用）

```json
{
  "type": "object",
  "required": ["segments"],
  "properties": {
    "segments": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "required": ["seq", "type", "text"],
        "properties": {
          "seq":        { "type": "integer", "minimum": 1 },
          "type":       { "type": "string", "enum": ["NARRATION", "DIALOGUE"] },
          "speaker_name": { "type": "string" },
          "character_name": { "type": ["string", "null"] },
          "text":       { "type": "string", "minLength": 1 },
          "emotion":    { "type": "string" },
          "style_hint": { "type": "string" },
          "style_instructions": { "type": "string" }
        }
      }
    }
  }
}
```

### 1.6 後端解析與容錯流程

```
1. 取 choices[0].message.content
2. 去 markdown 圍欄：若以 ``` 開頭，取第一個 ``` 與最後一個 ``` 之間的內容
3. 若仍非合法 JSON → 找第一個 '{' 到最後一個 '}' 的片段再試一次
4. Jackson 反序列化到 SegmentAnalysisResult（未知欄位忽略）
5. 驗證：segments 非空、seq 遞增、type 合法、text 非空
6. 驗證失敗 → 組裝「錯誤回饋」重試一次：
   user message = 原提示 + "\n\n【你上次的輸出有誤】{具體錯誤訊息}，請重新輸出合法 JSON。"
7. 重試仍失敗 → job_task.status = FAILED，記 error_message，該章不寫入段落
```

### 1.7 角色合併規則

從 segments 取出所有非 null 的 `character_name`，以 `name` 為鍵合併：
- 同名 → 同一角色
- 角色名出現在另一角色的 `aliases` → 合併為同一角色（aliases 存 `TEXT[]`）
- 合併時 `segment_count` 累加
- 角色的 `gender` / `age_group` / `personality` / `description` 由 AI 在首次出現時推測填入，
  後續同名角色不覆蓋既有值（以先到先得為準）

### 1.8 音色建議（自動預填）

建立角色時，給一個**預設音色建議**（使用者仍可改）：
依角色名稱與 `style_instructions` 推測 → 映射到 `tts_voice` 候選
（例：description 含「男」→ Charon/Fenrir；含「女」→ Kore/Aoede/Leda；
敘述者→ Kore；年輕男性→ Puck）。映射規則實作於 `VoiceSuggestionService`，
使用者未指派音色時，`/settings` 可設「自動指派」開關。

## 2. 合成階段（Audio Speech）

### 2.1 端點
```
POST https://openrouter.ai/api/v1/audio/speech
Authorization: Bearer <api_key>
Content-Type: application/json
```
回應為 **raw audio 位元組流**（非 JSON），header 含：
- `Content-Type: audio/pcm`（Gemini TTS 固定）或 `audio/mpeg`
- `X-Generation-Id: <uuid>` → 存入 `audio_file.generation_id`

錯誤時回 JSON body（`{"error": {...}}`），需依 status code 分流處理。

### 2.2 請求體（multi-speaker）
```json
{
  "model": "google/gemini-3.8-flash-tts",
  "input": [
    { "text": "他推開門，屋子裡沒有一個人。", "voice": "Kore", "instructions": "以沉穩低沉的語氣朗讀" },
    { "text": "你到底是誰？", "voice": "Charon", "instructions": "語速偏快，帶著警戒" },
    { "text": "我是來還債的。", "voice": "Charon", "instructions": "語速偏快，帶著警戒" }
  ],
  "instructions": "小說有聲書風格，發音清晰自然",
  "response_format": "pcm",
  "provider": { "zdr": true }
}
```

欄位規則：
- `input` 為陣列 = multi-speaker。每一 turn 可各自帶 `voice` 與 `instructions`
- turn 沒帶 `voice` → 用頂層 `voice`；沒帶 `instructions` → 用頂層 `instructions`
- `instructions` 支援：OpenAI TTS / Gemini TTS / Fish Audio。其他 provider 可能忽略
- `response_format`：`pcm` 或 `mp3`。**Gemini TTS 只回 pcm**，傳 mp3 會 400
- `speed`：只有部分模型支援（OpenAI TTS）。Gemini 不支援 → 預設不帶此參數

### 2.3 分組策略（multi-speaker input 如何組）

目標：一次請求涵蓋多個角色的連續對話，減少請求數與成本。

```
1. 取該章所有 segment，依 seq 排序
2. 依「說話者是否變換」切成 turn：同一說話者連續多段 → 合併為一個 turn，
   turn.text = 該說話者多段文字以 "\n" 連接
   ⚠ 說話者的判斷依據是「角色身分」（character_name / 角色名），
     不能只比 voice 與 instructions：兩個不同角色若音色與語氣恰好相同
     （常見於尚未指派音色的角色），只比 voice/instructions 會被誤合併，
     導致同一音色念出兩個人的對話。
3. 依「章節長度上限」再切分 group：
   - group 內所有 turn 的 text 總字數 ≤ 3000（保守值，留給模型 token 與 120s 音訊限制）
   - group 至少含 1 個 turn；若單一 turn 超過 3000 字，再單獨切成多組
4. 每個 group 送出一次 /audio/speech 請求
5. 各 group 產生的 PCM 依序串接（byte concat，同格式同取樣率可直接接）
6. ffmpeg 轉 MP3：
   ffmpeg -f s16le -ar 24000 -ac 1 -i out.pcm -c:a libmp3lame -b:a 128k out.mp3
7. ffprobe 量測 duration_ms
```

> Gemini TTS 單次音訊上限 120 秒，單次輸入 5000 字元。
> 上面的 3000 字上限是文字量保守估計；實作時若遇 400 錯誤，需依回應訊息自動降半重試。

### 2.4 音色與指令來源

| TTS 欄位 | 來源 |
|---|---|
| turn.voice | `segment.voice_id`（若為空 → `character.tts_voice_id` → `tts_voice.voice_id` → 設定頁指定「預設音色」） |
| turn.instructions | `character.tts_instructions` ＋ `segment.instructions`（以 `，` 串接；皆空則不帶此欄位） |
| 頂層 instructions | 設定頁「全域語氣」欄位（預設：小說有聲書風格，發音清晰自然） |
| response_format | 依模型能力決定；Gemini → `pcm`，OpenAI TTS → `mp3` |

### 2.5 錯誤處理

| 狀況 | 處置 |
|---|---|
| 429 | 讀 `Retry-After` header，否則指數退避（1s/2s/4s），最多 3 次 |
| 5xx | 指數退避重試 3 次 |
| 400 且訊息含字元/時長限制 | group 降半（3000→1500→750）後重試，最多 2 次 |
| 400 其他 | 直接標 FAILED，記錄完整錯誤 |
| 連續失敗 | 該 group 的所有 segment 標 `tts_status='FAILED'`、`error_message` 寫入原因 |

### 2.6 併發控制

- 以 `Semaphore(3)` 限制同時在飛的 TTS 請求數
- 同一小說的合成 job 序列化執行（避免同章重複合成）
- 429 退避等待期間不佔用 semaphore（先取得 → 送出 → 釋放）

## 3. 成本記錄

每次呼叫寫一筆 `usage_log`。費用來源分兩種：

| 類型 | `estimated_cost` 來源 | 準確度 |
|---|---|---|
| 分析 `kind='TEXT'` | OpenRouter 回應的 `usage.cost`（實際扣款金額） | 實際值 |
| 合成 `kind='AUDIO'` | `model_pricing` 定價 × `char_count` | 估算值 |

**分析**：Chat Completions 回應的 `usage` 會帶 `cost` 與 `cost_details`：
- `usage.cost`：OpenRouter 收取的總額。BYOK 路由時這只是轉接手續費。
- `usage.cost_details.upstream_inference_cost`：上游模型實際推論費。一般路由為 0/null，
  BYOK 路由才有值。

兩者相加才是實際總支出（`ChatUsage.totalSpend()`）。只記 `cost` 會在 BYOK 路由下
把花費記成 0。

**合成**：`/audio/speech` 端點只回傳 raw audio 位元組流，不帶費用欄位，
因此用「模型定價 × 輸入字元數」估算。定價由 `model_pricing` 表提供，
透過 `POST /api/settings/pricing/sync` 從 OpenRouter models API 同步。

- 定價來源：`GET https://openrouter.ai/api/v1/models`
  （語音模型另需 `?output_modalities=speech`，只出現在該查詢結果中）
- 單位：USD / token。TTS 模型的 `pricing.prompt` 適用於語音輸入。
- 同步後快取於 `model_pricing` 表，避免每次呼叫都打外部 API。

> 注意：`usage_log.estimated_cost` 這個欄位名沿用早期設計（當時預設留空不估），
> 但目前實際會寫入值。TEXT 是實際扣款，AUDIO 是估算值 —— 兩者語意不同，
> 查詢時需區分。

## 4. 模型建議

| 用途 | 建議 model_id | 理由 |
|---|---|---|
| 分析 | `google/gemini-3-flash-preview` | JSON 輸出穩定、成本低、context 足夠 |
| 分析（高精度） | `anthropic/claude-sonnet-4.5` | 長章節切分品質較佳，成本高 |
| 合成 | `google/gemini-3.8-flash-tts` | multi-speaker + PCM + instructions 支援最完整 |
| 合成（備援） | `google/gemini-3.1-flash-tts-preview` | 備援用；主模型失敗時自動接手 |

以上皆為 OpenRouter 上的 model_id，實際可用模型以
`GET https://openrouter.ai/api/v1/models` 回傳為準，可在設定頁自由填寫。

## 5. 失敗重試與備援模型

### 分析（Chat Completions）

`AnalysisService.callWithRetry()`：
- 429 / 5xx：指數退避重試（1s → 2s），最多 3 次嘗試
- 其他 4xx（參數錯誤、模型不存在）：直接失敗不重試 —— 重試也不會成功
- 第二次嘗試起會改用 `PromptBuilder.retryPrompt()`，把前次的錯誤訊息帶進提示，
  讓模型知道要修正什麼

### 合成（audio/speech）

`TtsService.synthesizeWithFallback()`：
1. 以主要模型呼叫，遇 429/5xx 指數退避重試（最多 3 次嘗試）
2. 仍失敗且設定了備援模型時，改用備援模型再試一次
3. 其他 4xx 不重試

備援模型由 `ai_model_setting.fallback_model_id` 指定，留空表示不啟用。
可在設定頁「備援模型 ID」欄位設定。

語音合成失敗會讓整個段落失敗，時間與金錢成本都高，因此備援模型的效益明顯
高於分析端。
