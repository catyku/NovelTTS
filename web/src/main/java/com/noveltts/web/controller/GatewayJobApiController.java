package com.noveltts.web.controller;

import com.noveltts.ai.GatewayJobStore;
import com.noveltts.service.GatewayJobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
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

    /** 開始背景語音合成。 */
    @PostMapping("/synthesize")
    public ResponseEntity<Map<String, Object>> startSynthesis(
            @RequestHeader(value = "X-OpenRouter-Key", required = false) String apiKey,
            @RequestHeader(value = "X-OpenRouter-Base-Url", required = false) String baseUrl,
            @RequestBody SynthesizeJobRequest request) {
        GatewayJobStore.Job job = jobService.startSynthesis(
                apiKey,
                baseUrl != null ? baseUrl : request.baseUrl(),
                request.safeGroups(),
                request.model(),
                request.fallbackModelId(),
                request.globalInstructions());
        return ResponseEntity.accepted().body(Map.of(
                "jobId", job.id(),
                "status", job.status(),
                "total", job.total(),
                "message", job.message()));
    }

    /**
     * 取回語音合成的結果。
     *
     * <p>音檔比分析結果大得多（500 段約 30MB），不能整包塞在狀態回應裡。
     * 採「分段取回、取回即釋放」：客戶端帶著已存檔的序號（ack）來取，
     * 伺服器把 ack 之前的音檔丟掉，記憶體用量因此維持在可控範圍。
     *
     * @param jobId 工作 id
     * @param ack 客戶端已確認存檔的最大序號
     * @param limit 本次最多取回幾段
     * @return 進度 + 待取回的音檔（base64）
     */
    @GetMapping("/{jobId}/audio")
    public ResponseEntity<Map<String, Object>> audio(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "-1") int ack,
            @RequestParam(defaultValue = "10") int limit) {
        GatewayJobStore.Job job = store.get(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "job_not_found",
                    "detail", "找不到這個工作，可能已逾時或已被清除"));
        }
        int capped = Math.min(Math.max(limit, 1), 50);
        List<GatewayJobStore.Job.AudioEntry> entries = job.takeAudioAfter(ack, capped);

        List<Map<String, Object>> items = new ArrayList<>();
        for (GatewayJobStore.Job.AudioEntry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", e.seq());
            m.put("segmentId", e.segmentId());
            m.put("chapterId", e.chapterId());
            m.put("voice", e.voice());
            m.put("audioBase64", e.mp3() == null ? "" : Base64.getEncoder().encodeToString(e.mp3()));
            items.add(m);
        }

        Map<String, Object> resp = new LinkedHashMap<>(job.snapshot());
        // 狀態快照裡的 results 是分析用的 JSON；語音合成用不到
        resp.remove("results");
        resp.put("items", items);
        resp.put("pendingAudio", job.pendingAudioCount());
        return ResponseEntity.ok(resp);
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

    /**
     * 退回音檔，讓前端可以重新取回。
     *
     * <p>音檔交給前端後不會立刻從伺服器刪除 —— 萬一網路失敗或
     * 存檔失敗，前端可以呼叫這個端點把序號退回來重送。
     *
     * @param jobId 工作 id
     * @param seqs 要退回的序號
     */
    @PostMapping("/{jobId}/audio/requeue")
    public ResponseEntity<Map<String, Object>> requeue(
            @PathVariable String jobId,
            @RequestBody RequeueRequest request) {
        GatewayJobStore.Job job = store.get(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "job_not_found",
                    "detail", "找不到這個工作"));
        }
        job.requeueAll(request.seqs());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * 退回音檔的請求。
     *
     * @param seqs 要退回的序號
     */
    public record RequeueRequest(List<Long> seqs) {
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
      * 開始背景語音合成的請求。
      *
      * @param groups 要合成的 turn 群組（分組由前端決定）
      * @param model 語音模型
      * @param fallbackModelId 備援模型
      * @param globalInstructions 全域語氣指令
      * @param baseUrl 自訂 base url，可為 null
      */
     public record SynthesizeJobRequest(
             List<GatewayJobService.TtsGroupInput> groups,
             String model,
             String fallbackModelId,
             String globalInstructions,
             String baseUrl) {

         /** 群組清單，永遠不是 null。 */
         public List<GatewayJobService.TtsGroupInput> safeGroups() {
             return groups == null ? List.of() : groups;
         }
     }

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
