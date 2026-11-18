package com.noveltts.web.controller;

import com.noveltts.ai.AiCallException;
import com.noveltts.ai.GatewayProvider;
import com.noveltts.ai.dto.SegmentAnalysis;
import com.noveltts.ai.dto.SpeechResult;
import com.noveltts.ai.dto.TtsTurn;
import com.noveltts.service.GatewayService;
import com.noveltts.tts.AudioTool;
import com.noveltts.config.NovelTtsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 無狀態 AI 閘道 API。
 *
 * <p>使用者的 OpenRouter API Key 由瀏覽器放在 {@code X-OpenRouter-Key} 標頭中傳來。
 * 伺服器只在該次請求中使用它，不寫入資料庫、不落盤、不記錄到 log。
 *
 * <p>本 controller 不觸碰任何使用者資料（小說、角色、音檔都在瀏覽器端），
 * 也不寫入 job_task 等資料表。
 */
@RestController
@RequestMapping("/api/gateway")
public class GatewayApiController {

    private static final Logger log = LoggerFactory.getLogger(GatewayApiController.class);

    private final GatewayService gatewayService;
    private final AudioTool audioTool;
    private final NovelTtsProperties properties;

    public GatewayApiController(GatewayService gatewayService,
                                AudioTool audioTool,
                                NovelTtsProperties properties) {
        this.gatewayService = gatewayService;
        this.audioTool = audioTool;
        this.properties = properties;
    }

    /** 分析章節內容。 */
    @PostMapping("/analyze")
    public SegmentAnalysis analyze(
            @RequestHeader(value = "X-OpenRouter-Key", required = false) String apiKey,
            @RequestHeader(value = "X-OpenRouter-Base-Url", required = false) String baseUrl,
            @RequestBody AnalyzeRequest request) {
        GatewayProvider provider = provider(apiKey, baseUrl, request.baseUrl());
        return gatewayService.analyze(provider,
                request.model(), request.fallbackModelId(),
                request.chapterTitle(), request.chapterContent(),
                request.temperature(), request.maxTokens(), request.extraParams());
    }

    /**
     * 語音合成，回傳 MP3。
     *
     * <p>server 端轉檔（PCM → MP3）後再回傳位元組，瀏覽器直接存成 Blob，
     * 不需要 ffmpeg.wasm。轉檔用暫存檔，轉完即刪。
     */
    @PostMapping("/synthesize")
    public ResponseEntity<byte[]> synthesize(
            @RequestHeader(value = "X-OpenRouter-Key", required = false) String apiKey,
            @RequestHeader(value = "X-OpenRouter-Base-Url", required = false) String baseUrl,
            @RequestBody SynthesizeRequest request) {
        GatewayProvider provider = provider(apiKey, baseUrl, request.baseUrl());
        List<TtsTurn> turns = request.turns() == null ? List.of() : request.turns();
        SpeechResult result = gatewayService.synthesize(provider,
                request.model(), request.fallbackModelId(),
                turns, request.globalInstructions());

        byte[] mp3 = pcmToMp3(result.audio());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"segment.mp3\"")
                .body(mp3);
    }

    /** 列出可用的語音模型。 */
    @GetMapping("/models")
    public List<?> models(
            @RequestHeader(value = "X-OpenRouter-Key", required = false) String apiKey,
            @RequestHeader(value = "X-OpenRouter-Base-Url", required = false) String baseUrl,
            @RequestParam(required = false) String baseUrlParam) {
        return gatewayService.listModels(provider(apiKey, baseUrl, baseUrlParam));
    }

    /** 測試 provider 連線。 */
    @PostMapping("/test")
    public Map<String, Object> test(
            @RequestHeader(value = "X-OpenRouter-Key", required = false) String apiKey,
            @RequestHeader(value = "X-OpenRouter-Base-Url", required = false) String baseUrl,
            @RequestBody(required = false) Map<String, Object> body) {
        String bodyBaseUrl = body == null ? null : (String) body.get("baseUrl");
        GatewayProvider provider = provider(apiKey, baseUrl, bodyBaseUrl);
        return Map.of("ok", true, "message", gatewayService.testConnection(provider));
    }

    /**
     * 建立 provider。
     *
     * <p>優先序：請求標頭 → 請求 body 的 baseUrl → 預設 OpenRouter。
     * API Key 只從標頭取得（body 也能帶，但標頭優先），避免 key 出現在 URL 或 log。
     */
    private GatewayProvider provider(String headerKey, String headerBaseUrl, String bodyBaseUrl) {
        String key = headerKey;
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("缺少 X-OpenRouter-Key");
        }
        String base = headerBaseUrl != null && !headerBaseUrl.isBlank()
                ? headerBaseUrl
                : (bodyBaseUrl != null && !bodyBaseUrl.isBlank() ? bodyBaseUrl : null);
        return new GatewayProvider("openrouter", base, key, Map.of(), null);
    }

    /** PCM → MP3。轉檔用暫存檔，完成後刪除。 */
    private byte[] pcmToMp3(byte[] pcm) {
        Path pcmFile = null;
        Path mp3File = null;
        try {
            Path tmpDir = Files.createTempDirectory("noveltts-gw-");
            pcmFile = tmpDir.resolve("in.pcm");
            mp3File = tmpDir.resolve("out.mp3");
            Files.write(pcmFile, pcm);
            audioTool.pcmToMp3(pcmFile, mp3File, properties.getAudioSampleRate());
            return Files.readAllBytes(mp3File);
        } catch (IOException e) {
            throw new IllegalStateException("PCM 轉 MP3 失敗: " + e.getMessage(), e);
        } finally {
            deleteQuietly(pcmFile);
            deleteQuietly(mp3File);
        }
    }

    private void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
            Files.deleteIfExists(p.getParent());
        } catch (IOException ignored) {
            // 暫存檔刪除失敗不影響主流程
        }
    }

    /** 分析請求。 */
    public record AnalyzeRequest(
            String model,
            String fallbackModelId,
            String chapterTitle,
            String chapterContent,
            Double temperature,
            Integer maxTokens,
            Map<String, Object> extraParams,
            String baseUrl
    ) {
    }

    /** 合成請求。 */
    public record SynthesizeRequest(
            String model,
            String fallbackModelId,
            List<TtsTurn> turns,
            String globalInstructions,
            String baseUrl
    ) {
    }
}
