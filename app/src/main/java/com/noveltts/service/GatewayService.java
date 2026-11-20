package com.noveltts.service;

import com.noveltts.ai.AiCallException;
import com.noveltts.ai.GatewayProvider;
import com.noveltts.ai.GenerationPromptBuilder;
import com.noveltts.ai.OpenRouterClient;
import com.noveltts.ai.PromptBuilder;
import com.noveltts.ai.SegmentParser;
import com.noveltts.ai.dto.ChatMessage;
import com.noveltts.ai.dto.ChatResult;
import com.noveltts.ai.dto.SegmentAnalysis;
import com.noveltts.ai.dto.SpeechResult;
import com.noveltts.ai.dto.TtsRequest;
import com.noveltts.ai.dto.TtsTurn;
import com.noveltts.config.NovelTtsProperties;
import com.noveltts.domain.entity.Chapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 無狀態 AI 閘道：伺服器只負責把請求轉送到 OpenRouter，不保存任何使用者資料。
 *
 * <p>使用情境：小說內容、角色設定、音檔全部存放在瀏覽器（IndexedDB），
 * 使用者自己的 OpenRouter API Key 存在 localStorage。瀏覽器呼叫本閘道時
 * 以 {@code X-OpenRouter-Key} 標頭帶上 Key，伺服器僅在該次請求中使用。
 *
 * <p>與既有 {@link AnalysisService} / {@link TtsService} 的差異：那些服務會把
 * 結果寫入資料庫與磁碟；本服務只回傳結果，呼叫端自行保存。
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    /** 分析呼叫的重試嘗試次數（含首次呼叫）。 */
    /**
     * AI 呼叫的最大嘗試次數。
     *
     * <p>必須配合 OpenRouterClient 的讀取逾時，總時間要小於 Cloudflare 的
     * 100 秒上限 —— 超過就會回 524，使用者看到的是一整頁 HTML 而不是
     * 可用的錯誤訊息。
     *
     * <p>逾時的處理刻意不在伺服器端重試：伺服器逾時後快速回應，
     * 由前端顯示「繼續 AI 分析」讓使用者重試那一章。這樣比在伺服器端
     * 連續重試更快回應使用者，也不會讓整個請求拖過 Cloudflare 的上限。
     */
    private static final int MAX_CHAT_ATTEMPTS = 2;

    /** 重試退避基準（毫秒），第 n 次等待 base * 2^n。 */
    private static final long RETRY_BACKOFF_BASE_MS = 1000L;

    /**
     * 呼叫端沒指定模型時的預設值。
     *
     * <p>不給預設值的話，OpenRouter 會自行挑一個模型 —— 可能挑到 reasoning 模型
     * （deepseek 等），它的思考 token 會吃光 max_tokens，導致正文變成 null。
     */
    private static final String DEFAULT_TEXT_MODEL = "google/gemini-3-flash-preview";

    private final OpenRouterClient openRouterClient;
    private final SegmentParser segmentParser;
    private final NovelTtsProperties properties;

    public GatewayService(OpenRouterClient openRouterClient,
                          SegmentParser segmentParser,
                          NovelTtsProperties properties) {
        this.openRouterClient = openRouterClient;
        this.segmentParser = segmentParser;
        this.properties = properties;
    }

    /**
     * 分析一段章節內容，回傳段落與角色。
     *
     * <p>章節標題以獨立欄位傳入，不併入 content —— AI 若把標題當成旁白會念出來。
     *
     * @param chapterTitle 章節標題（可為 null）
     * @param chapterContent 章節內文
     */
    public SegmentAnalysis analyze(GatewayProvider provider, String model,
                                   String fallbackModelId,
                                   String chapterTitle, String chapterContent,
                                   Double temperature, Integer maxTokens,
                                   Map<String, Object> extraParams) {
        if (chapterContent == null || chapterContent.isBlank()) {
            throw new IllegalArgumentException("章節內容為空");
        }
        Chapter chapter = new Chapter();
        chapter.setTitle(chapterTitle);
        chapter.setContent(chapterContent);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", PromptBuilder.SYSTEM_PROMPT));
        messages.add(new ChatMessage("user", PromptBuilder.userPrompt(chapter)));

        String content = callChatWithFallback(provider, model, fallbackModelId, messages,
                temperature, effectiveAnalyzeMaxTokens(chapterContent, maxTokens), extraParams);
        return segmentParser.parse(content);
    }

    /**
     * 分析用的 max_tokens 自動放大。
     *
     * <p>章節越長，切出的段落越多，每段在 JSON 裡的開銷也越大
     * （欄位名、跳脫字元）。實測 3000 字的章節可能需要一萬出頭的 token。
     * 用呼叫端給的 8000 常常不夠，JSON 就被截斷 —— 整章分析失敗。
     *
     * <p>估算方式：中文一字大約 1.5～2 個 token（含 JSON 結構），
     * 段落數再乘上一個係數。取兩者的較大值，並給下限與上限。
     *
     * @param chapterContent 章節內文
     * @param requested 使用者（或前端設定）指定的值；null 表示沒指定
     */
    private int effectiveAnalyzeMaxTokens(String chapterContent, Integer requested) {
        int chars = chapterContent == null ? 0 : chapterContent.length();
        // 每 40 字大約切一個段落；每段在 JSON 裡約 80 個 token
        int estimated = (int) Math.ceil(chars / 40.0 * 80);
        int floor = 8000;
        int ceiling = 32000;
        int needed = Math.min(ceiling, Math.max(floor, estimated));
        if (requested == null || requested <= 0) {
            return needed;
        }
        // 呼叫端指定得比估算少時，以估算為準 —— 指定值不足會導致截斷
        return Math.max(requested, needed);
    }

    /**
     * 生成小說大綱（純文字）。
     *
     * <p>大綱是選用的：使用者可以跳過它，直接逐章生成。
     * 有大綱時，每一章的 prompt 都會帶上它，維持全書一致性。
     */
    public String generateOutline(GatewayProvider provider, String model,
                                  String fallbackModelId,
                                  String idea, int chapterCount,
                                  int minChars, int maxChars,
                                  Double temperature, Integer maxTokens,
                                  Map<String, Object> extraParams) {
        if (idea == null || idea.isBlank()) {
            throw new IllegalArgumentException("創作想法為空");
        }
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", GenerationPromptBuilder.SYSTEM_PROMPT),
                new ChatMessage("user", GenerationPromptBuilder.outlinePrompt(
                        idea, chapterCount, minChars, maxChars)));
        // jsonResponse = false：要求 JSON 會讓模型把大綱包進字串裡
        return callChatWithFallback(provider, model, fallbackModelId, messages,
                temperature, maxTokens, extraParams, false).trim();
    }

    /**
     * 生成單一章節正文。
     *
     * @param previousEnding 上一章結尾摘錄（可為 null），維持劇情連貫
     */
    public String generateChapter(GatewayProvider provider, String model,
                                  String fallbackModelId,
                                  String idea, int chapterNumber, int totalChapters,
                                  int minChars, int maxChars,
                                  String outline, String previousEnding, String extra,
                                  Double temperature, Integer maxTokens,
                                  Map<String, Object> extraParams) {
        if (idea == null || idea.isBlank()) {
            throw new IllegalArgumentException("創作想法為空");
        }
        if (chapterNumber < 1 || chapterNumber > totalChapters) {
            throw new IllegalArgumentException("章序超出範圍: " + chapterNumber);
        }
        if (minChars < 1 || maxChars < minChars) {
            throw new IllegalArgumentException("字數範圍不合法");
        }
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", GenerationPromptBuilder.SYSTEM_PROMPT),
                new ChatMessage("user", GenerationPromptBuilder.chapterPrompt(
                        idea, chapterNumber, totalChapters, minChars, maxChars,
                        outline, previousEnding, extra)));
        // max_tokens 當硬保險：一律由字數上限換算，不採呼叫端傳入的值 ——
        // 全域設定的 maxTokens 是給「分析」用的（8000），拿來生成章節會讓輸出失控。
        //
        // 多給 40% 緩衝：reasoning 模型（deepseek、o 系列等）會把思考 token
        // 算進 max_tokens 預算，空間不夠時正文會被截斷甚至變成 null。
        int effectiveMaxTokens = (int) Math.ceil(
                GenerationPromptBuilder.maxTokensFor(maxChars, 8000) * 1.4);
        String content = callChatWithFallback(provider, model, fallbackModelId, messages,
                temperature, effectiveMaxTokens, extraParams, false).trim();
        // 保險：仍超出上限就截到最後一個完整句子。
        return GenerationPromptBuilder.trimToSentence(content, maxChars).trim();
    }

    /**
     * 語音合成，回傳 PCM 位元組。
     *
     * <p>回傳 PCM 而非 MP3：轉檔在 server 端做（見 web 層的 controller），
     * 瀏覽器不需要 ffmpeg.wasm。
     */
    public SpeechResult synthesize(GatewayProvider provider, String model,
                                   String fallbackModelId,
                                   List<TtsTurn> turns, String globalInstructions) {
        if (turns == null || turns.isEmpty()) {
            throw new IllegalArgumentException("TTS 請求沒有任何 turn");
        }
        Map<String, Object> providerParams = Map.of("zdr", true);
        TtsRequest request = TtsRequest.fromTurns(model, turns, globalInstructions, providerParams);
        return callSpeechWithFallback(provider, model, fallbackModelId, request, turns,
                globalInstructions, providerParams);
    }

    /** 列出可用的語音模型。 */
    public List<com.noveltts.ai.dto.ModelInfo> listModels(GatewayProvider provider) {
        return openRouterClient.listModels(provider);
    }

    /** 測試 provider 連線。 */
    public String testConnection(GatewayProvider provider) {
        try {
            List<com.noveltts.ai.dto.ModelInfo> models = openRouterClient.listModels(provider);
            return "連線成功，可用模型 " + models.size() + " 個";
        } catch (RuntimeException e) {
            throw new IllegalStateException("連線測試失敗: " + e.getMessage(), e);
        }
    }

    /**
     * 分析呼叫：429/5xx 退避重試，仍失敗則改用備援模型。
     */
    private String callChatWithFallback(GatewayProvider provider, String model,
                                        String fallbackModelId, List<ChatMessage> messages,
                                        Double temperature, Integer maxTokens,
                                        Map<String, Object> extraParams) {
        return callChatWithFallback(provider, model, fallbackModelId, messages,
                temperature, maxTokens, extraParams, true);
    }

    /**
     * 分析呼叫：429/5xx 退避重試，仍失敗則改用備援模型。
     *
     * @param jsonResponse true 時要求 JSON 回應；生成小說時傳 false
     */
    private String callChatWithFallback(GatewayProvider provider, String model,
                                        String fallbackModelId, List<ChatMessage> messages,
                                        Double temperature, Integer maxTokens,
                                        Map<String, Object> extraParams,
                                        boolean jsonResponse) {
        AiCallException lastError = null;
        String effectiveModel = (model == null || model.isBlank())
                ? DEFAULT_TEXT_MODEL : model;

        for (int attempt = 0; attempt < MAX_CHAT_ATTEMPTS; attempt++) {
            try {
                ChatResult result = openRouterClient.chat(provider, effectiveModel, messages,
                        temperature, maxTokens, extraParams, jsonResponse);
                return result.content();
            } catch (AiCallException e) {
                lastError = e;
                if (!e.isRetryable()) {
                    break;
                }
                log.warn("分析呼叫失敗 (HTTP {})，第 {}/{} 次重試: {}",
                        e.getStatusCode(), attempt + 1, MAX_CHAT_ATTEMPTS, e.getMessage());
                if (attempt < MAX_CHAT_ATTEMPTS - 1) {
                    sleepBackoff(attempt);
                }
            }
        }

        if (fallbackModelId != null && !fallbackModelId.isBlank()) {
            log.warn("主要模型 {} 失敗，改用備援模型 {}", model, fallbackModelId);
            try {
                ChatResult result = openRouterClient.chat(provider, fallbackModelId, messages,
                        temperature, maxTokens, extraParams, jsonResponse);
                return result.content();
            } catch (AiCallException e) {
                log.error("備援模型 {} 同样失敗 (HTTP {}): {}",
                        fallbackModelId, e.getStatusCode(), e.getMessage());
                lastError = e;
            }
        }

        throw lastError != null ? lastError
                : new IllegalStateException("分析呼叫失敗且未取得具體錯誤");
    }

    /**
     * 語音合成呼叫：429/5xx 退避重試，仍失敗則改用備援模型。
     */
    private SpeechResult callSpeechWithFallback(GatewayProvider provider, String model,
                                                String fallbackModelId, TtsRequest request,
                                                List<TtsTurn> turns, String globalInstructions,
                                                Map<String, Object> providerParams) {
        AiCallException lastError = null;

        for (int attempt = 0; attempt < MAX_CHAT_ATTEMPTS; attempt++) {
            try {
                return openRouterClient.speech(provider, request);
            } catch (AiCallException e) {
                lastError = e;
                if (!e.isRetryable()) {
                    break;
                }
                log.warn("TTS 呼叫失敗 (HTTP {})，第 {}/{} 次重試: {}",
                        e.getStatusCode(), attempt + 1, MAX_CHAT_ATTEMPTS, e.getMessage());
                if (attempt < MAX_CHAT_ATTEMPTS - 1) {
                    sleepBackoff(attempt);
                }
            }
        }

        if (fallbackModelId != null && !fallbackModelId.isBlank()) {
            log.warn("主要模型 {} 失敗，改用備援模型 {}", model, fallbackModelId);
            TtsRequest fallbackRequest = TtsRequest.fromTurns(
                    fallbackModelId, turns, globalInstructions, providerParams);
            try {
                return openRouterClient.speech(provider, fallbackRequest);
            } catch (AiCallException e) {
                log.error("備援模型 {} 同样失敗 (HTTP {}): {}",
                        fallbackModelId, e.getStatusCode(), e.getMessage());
                lastError = e;
            }
        }

        throw lastError != null ? lastError
                : new IllegalStateException("TTS 呼叫失敗且未取得具體錯誤");
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_BASE_MS * (1L << attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重試等待被中斷", e);
        }
    }
}
