package com.noveltts.web.controller;

import com.noveltts.ai.GatewayMergeJobStore;
import com.noveltts.service.GatewayMergeJobService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 音檔合併的背景工作 API。
 *
 * <p><b>為什麼要分批上傳</b>
 *
 * <p>「合併整本」要把整本書的音檔送給伺服器用 ffmpeg 合併。
 * 長篇小說的總量動輒 250MB，單次 multipart 請求一定會撞上
 * Spring 的 {@code max-request-size} —— 而把上限調大只是把問題往後推。
 *
 * <p>所以改成：前端分批上傳 → 伺服器暫存 → 背景合併 → 前端取回。
 * 如此一來總量沒有上限，也不會有長時間的單一請求卡住。
 *
 * <p><b>與既有合併端點的關係</b>
 *
 * <p>{@code /api/gateway/merge} 仍然保留，適合小批量（幾個章節）。
 * 長篇小說用本控制器的工作流程。
 */
@RestController
@RequestMapping("/api/gateway/merge-jobs")
public class GatewayMergeJobApiController {

    private final GatewayMergeJobStore store;
    private final GatewayMergeJobService service;

    public GatewayMergeJobApiController(GatewayMergeJobStore store, GatewayMergeJobService service) {
        this.store = store;
        this.service = service;
    }

    /**
     * 建立合併工作。
     *
     * @param request 請求
     * @return 工作 id
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request) {
        String type = request.type() == null ? "MERGE" : request.type().toUpperCase();
        if (!"MERGE".equals(type) && !"ZIP".equals(type)) {
            type = "MERGE";
        }
        GatewayMergeJobStore.MergeJob job = store.create(type, request.effectiveTotal());
        job.archiveName(request.name());
        job.status("PENDING");
        job.message("已建立，等待上傳音檔");
        return ResponseEntity.accepted().body(job.snapshot());
    }

    /**
     * 上傳一批音檔。
     *
     * <p>每次請求的總量控制在 20MB 以內（前端負責分批）。
     *
     * @param jobId 工作 id
     * @param files 音檔
     * @param name ZIP 的檔名（僅 ZIP 需要）
     * @return 目前已上傳的片段數
     */
    @PostMapping(value = "/{jobId}/parts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadParts(
            @PathVariable String jobId,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "name", required = false) String name) {
        GatewayMergeJobStore.MergeJob job = store.get(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "job_not_found",
                    "detail", "找不到這個合併工作，可能已逾時"));
        }
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("沒有可上傳的音檔");
        }
        if ("PENDING".equals(job.status())) {
            job.status("UPLOADING");
        }
        for (MultipartFile f : files) {
            if (f.isEmpty()) {
                continue;
            }
            try (InputStream in = f.getInputStream()) {
                store.appendPart(job, f.getOriginalFilename(), in);
            } catch (IOException e) {
                throw new IllegalStateException("上傳失敗: " + e.getMessage(), e);
            }
        }
        if (name != null && !name.isBlank()) {
            job.archiveName(name);
        }
        job.message("已上傳 " + job.uploaded() + " 個音檔");
        return ResponseEntity.ok(job.snapshot());
    }

    /**
     * 開始合併。
     *
     * @param jobId 工作 id
     * @return 工作狀態
     */
    @PostMapping("/{jobId}/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable String jobId) {
        GatewayMergeJobStore.MergeJob job = store.get(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "job_not_found",
                    "detail", "找不到這個合併工作"));
        }
        if (job.uploaded() == 0) {
            throw new IllegalArgumentException("尚未上傳任何音檔");
        }
        service.start(job);
        return ResponseEntity.accepted().body(job.snapshot());
    }

    /**
     * 查詢合併進度。
     *
     * @param jobId 工作 id
     * @return 工作狀態
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String jobId) {
        GatewayMergeJobStore.MergeJob job = store.get(jobId);
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "job_not_found",
                    "detail", "找不到這個合併工作，可能已逾時"));
        }
        return ResponseEntity.ok(job.snapshot());
    }

    /**
     * 取回合併結果。
     *
     * @param jobId 工作 id
     * @return 合併後的音檔或 ZIP
     */
    @GetMapping("/{jobId}/result")
    public ResponseEntity<Resource> result(@PathVariable String jobId) {
        GatewayMergeJobStore.MergeJob job = store.get(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"DONE".equals(job.status()) || job.result() == null) {
            return ResponseEntity.status(409).body(null);
        }
        Path output = job.result();
        if (!Files.exists(output)) {
            return ResponseEntity.status(410).build();
        }
        MediaType type = "ZIP".equals(job.type())
                ? MediaType.parseMediaType("application/zip")
                : MediaType.parseMediaType("audio/mpeg");
        String fileName = "ZIP".equals(job.type())
                ? job.archiveName() + ".zip"
                : "merged.mp3";
        long length;
        try {
            length = Files.size(output);
        } catch (IOException e) {
            return ResponseEntity.status(410).build();
        }
        // 標記已取回 —— 暫存檔接下來就會被排程清除，
        // 伺服器不該留著使用者的音檔副本。
        job.markDelivered();
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        com.noveltts.web.ContentDisposition.attachment(fileName))
                .body(new FileSystemResource(output));
    }

    /**
     * 取消工作並清除暫存檔。
     *
     * @param jobId 工作 id
     * @return 是否找到該工作
     */
    @DeleteMapping("/{jobId}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String jobId) {
        boolean found = store.get(jobId) != null;
        store.remove(jobId);
        if (!found) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "job_not_found",
                    "detail", "找不到這個合併工作"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "message", "已取消並清除暫存檔"));
    }

    /**
     * 建立合併工作的請求。
     *
     * @param type MERGE 或 ZIP
     * @param total 預期的音檔數
     * @param name ZIP 檔名
     */
    public record CreateRequest(String type, Integer total, String name) {

        /** 預期數量，null 視為 0（未知）。 */
        public int effectiveTotal() {
            return total == null ? 0 : Math.max(0, total);
        }
    }
}
