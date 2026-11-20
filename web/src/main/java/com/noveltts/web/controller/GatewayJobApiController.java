package com.noveltts.web.controller;

import com.noveltts.ai.GatewayJobStore;
import com.noveltts.service.GatewayJobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 閘道背景工作 API。
 *
 * <p>與其他閘道端點一樣是無狀態的：伺服器只把請求轉給 OpenRouter 再回傳，
 * 不保存使用者的小說或音檔。唯一例外是背景工作進行期間，
 * 伺服器會暫時持有 API Key 與已完成的章節結果 —— 這些只存在記憶體，
 * 工作結束或逾時（30 分鐘）後自動清除，不落盤、不進資料庫。
 *
 * <p>呼叫流程：
 *
 * <pre>
 * POST /api/gateway/jobs/analyze  → { jobId }          （發起，馬上回）
 * GET  /api/gateway/jobs/{jobId}  → { status, done, ... }（每 2 秒輪詢）
 * DELETE /api/gateway/jobs/{jobId}                     （取消並清除）
 * </pre>
 */
@RestController
@RequestMapping("/api/gateway/jobs")
public class GatewayJobApiController {

    private final GatewayJobService jobService;
    private final GatewayJobStore store;

    public GatewayJobApiController(GatewayJobService jobService, GatewayJobStore store) {
        this.jobService = jobService;
        this.store = store;
    }

    /** 開始背景分析。 */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> startAnalysis(
            @RequestHeader(value = "X-OpenRouter-Key", required = false) String apiKey,
            @RequestHeader(value = "X-OpenRouter-Base-Url", required = false) String baseUrl,
            @RequestBody AnalyzeJobRequest request) {
        GatewayJobStore.Job job = jobService.startAnalysis(
                apiKey,
                baseUrl != null ? baseUrl : request.baseUrl(),
                request.safeChapters(),
                request.model(),
                request.fallbackModelId(),
                request.temperature(),
                request.maxTokens());
        return ResponseEntity.accepted().body(Map.of(
                "jobId", job.id(),
                "status", job.status(),
                "total", job.total(),
                "message", job.message()));
    }

    /** 查詢工作進度與結果。 */
    @GetMapping("/{jobId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String jobId) {
        GatewayJobStore.Job job = store.get(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "job_not_found",
                    "detail", "找不到這個工作，可能已逾時或已被清除"));
        }
        return ResponseEntity.ok(job.snapshot());
    }

    /** 取消工作並清除結果。 */
    @DeleteMapping("/{jobId}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String jobId) {
        boolean found = jobService.delete(jobId);
        if (!found) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "job_not_found",
                    "detail", "找不到這個工作"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "message", "工作已取消"));
    }

    /**
     /**
      * 開始背景分析的請求。
      *
      * <p>章節由前端切好再送進來，而不是送整本小說讓伺服器切。
      * 理由：前端已經有一套切章邏輯（logic.js），而且續傳時
      * 「哪些章節需要重跑」是前端根據已存檔的結果算出來的 ——
      * 前端決定要分析哪些章節，伺服器就只分析那些。
      *
      * @param chapters 要分析的章節清單
      * @param model 分析模型
      * @param fallbackModelId 備援模型
      * @param temperature 溫度
      * @param maxTokens max tokens 上限
      * @param baseUrl 自訂 base url，可為 null
      */
     public record AnalyzeJobRequest(
             List<GatewayJobService.ChapterInput> chapters,
             String model,
             String fallbackModelId,
             Double temperature,
             Integer maxTokens,
             String baseUrl) {

         /** 章節清單，永遠不是 null。 */
         public List<GatewayJobService.ChapterInput> safeChapters() {
             return chapters == null ? List.of() : chapters;
         }
     }
}
