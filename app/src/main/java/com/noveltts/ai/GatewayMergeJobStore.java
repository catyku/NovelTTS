package com.noveltts.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 音檔合併工作的暫存區。
 *
 * <p><b>為什麼需要分批上傳</b>
 *
 * <p>「合併整本」要把整本書的所有音檔送給伺服器用 ffmpeg 合併。
 * 一本長篇小說有 500 段以上、總量 250MB 以上，單次 multipart 請求
 * 一定會被 Spring 的 {@code max-request-size} 擋掉（而且那個上限
 * 不論設多大都不對 —— 250MB 的書就會再撞一次）。
 *
 * <p>所以改成：前端分批上傳，伺服器把每一批落到暫存目錄，
 * 全部到齊後才在背景執行 ffmpeg 合併，最後讓前端取回結果。
 * 如此一來總量沒有上限。
 *
 * <p><b>暫存檔的隱私</b>
 *
 * <p>暫存檔是使用者音檔的副本，確實會短暫存在伺服器磁碟上。
 * 但：
 *
 * <ul>
 *   <li>只存在於工作期間，前端取回結果後立刻刪除</li>
 *   <li>逾時未完成也會自動清除</li>
 *   <li>放在系統暫存目錄，不進資料庫、不備份、不進版控</li>
 *   <li>不寫入 log</li>
 * </ul>
 *
 * <p>這與既有的合併端點行為一致 —— 那個端點也是把音檔寫到暫存目錄
 * 合併完就刪。差別只是這裡的生命週期較長（要等前端分批送完）。
 */
@Component
public class GatewayMergeJobStore {

    private static final Logger log = LoggerFactory.getLogger(GatewayMergeJobStore.class);

    /** 工作完成後，結果保留多久（毫秒）。 */
    private static final long RESULT_TTL_MS = 30 * 60 * 1000L;

    /** 進行中的工作最長可以跑多久（毫秒）。 */
    private static final long RUNNING_TIMEOUT_MS = 60 * 60 * 1000L;

    /** 結果取回後多久清除暫存檔（毫秒）。給前端一點重試的空間。 */
    private static final long DELIVERED_GRACE_MS = 30 * 1000L;

    /** 每分鐘清理一次。 */
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    private final Map<String, MergeJob> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger cleanupCounter = new AtomicInteger();

    /**
     * 一個合併工作。
     */
    public static final class MergeJob {
        /** 唯一 id。 */
        private final String id;
        /** 工作類型：MERGE（合併成一個音檔）或 ZIP（打包）。 */
        private final String type;
        /** 暫存目錄。 */
        private final Path dir;
        /** 已上傳的片段，依上傳順序。 */
        private final List<Part> parts = new ArrayList<>();
        /** 前端告知的總片段數（0 表示未知）。 */
        private final int expectedTotal;
        /** 狀態：PENDING / UPLOADING / MERGING / DONE / FAILED / CANCELLED。 */
        private volatile String status = "PENDING";
        /** 進度說明。 */
        private volatile String message = "";
        /** 合併結果（暫存檔）。 */
        private volatile Path result;
        /** ZIP 的檔名。 */
        private volatile String archiveName = "novel";
        /** 結果是否已被取回。取回後就該清掉暫存檔了。 */
        private volatile boolean delivered;
        /** 建立時間。 */
        private final long createdAt = System.currentTimeMillis();
        /** 最後更新時間。 */
        private volatile long updatedAt = System.currentTimeMillis();

        MergeJob(String id, String type, Path dir, int expectedTotal) {
            this.id = id;
            this.type = type;
            this.dir = dir;
            this.expectedTotal = expectedTotal;
        }

        public String id() {
            return id;
        }

        public String type() {
            return type;
        }

        public Path dir() {
            return dir;
        }

        public int expectedTotal() {
            return expectedTotal;
        }

        public int uploaded() {
            synchronized (parts) {
                return parts.size();
            }
        }

        public String status() {
            return status;
        }

        public void status(String s) {
            this.status = s;
            touch();
        }

        public String message() {
            return message;
        }

        public void message(String m) {
            this.message = m == null ? "" : m;
            touch();
        }

        public Path result() {
            return result;
        }

        public void result(Path p) {
            this.result = p;
            touch();
        }

        public boolean isDelivered() {
            return delivered;
        }

        /**
         * 標記結果已取回。
         *
         * <p>取回後暫存檔就沒有存在理由了 —— 伺服器不該留著
         * 使用者的音檔副本等 30 分鐘。
         */
        public void markDelivered() {
            this.delivered = true;
            touch();
        }

        public String archiveName() {
            return archiveName;
        }

        public void archiveName(String n) {
            if (n != null && !n.isBlank()) {
                this.archiveName = n;
            }
        }

        public void touch() {
            this.updatedAt = System.currentTimeMillis();
        }

        public long updatedAt() {
            return updatedAt;
        }

        /** 合併時要用的片段（依上傳順序）。 */
        public List<Path> partPaths() {
            synchronized (parts) {
                List<Path> out = new ArrayList<>();
                for (Part p : parts) {
                    out.add(p.path());
                }
                return out;
            }
        }

        /** ZIP 用的片段名稱（依上傳順序）。 */
        public List<String> partNames() {
            synchronized (parts) {
                List<String> out = new ArrayList<>();
                for (Part p : parts) {
                    out.add(p.name());
                }
                return out;
            }
        }

        /**
         * 是否已過期。
         *
         * <p><b>順序很重要</b>：「結果已取回」的判斷必須放在最前面。
         * 若先判斷狀態，PENDING / UPLOADING / MERGING 會直接 return，
         * 底下「已取回」的分支永遠走不到 —— 暫存檔就留住了。
         */
        boolean isExpired() {
            long age = System.currentTimeMillis() - updatedAt;
            // 結果已取回 → 快速清除（給前端一點重試時間）
            if (delivered) {
                return age > DELIVERED_GRACE_MS;
            }
            if ("PENDING".equals(status) || "UPLOADING".equals(status) || "MERGING".equals(status)) {
                return age > RUNNING_TIMEOUT_MS;
            }
            return age > RESULT_TTL_MS;
        }

        /** 對外回應用的快照。 */
        public Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", id);
            m.put("type", type);
            m.put("status", status);
            m.put("uploaded", uploaded());
            m.put("total", expectedTotal);
            m.put("message", message);
            m.put("ready", result != null);
            m.put("createdAt", Instant.ofEpochMilli(createdAt).toString());
            m.put("updatedAt", Instant.ofEpochMilli(updatedAt).toString());
            return m;
        }
    }

    /**
     * 一個上傳片段。
     *
     * @param name 原始檔名（ZIP 用）
     * @param path 暫存路徑
     */
    public record Part(String name, Path path) {
    }

    /**
     * 建立一個合併工作。
     *
     * @param type MERGE 或 ZIP
     * @param expectedTotal 預期的片段數（0 表示未知）
     * @return 工作物件
     */
    public MergeJob create(String type, int expectedTotal) {
        String id = "merge_" + Long.toHexString(System.nanoTime()) + "_"
                + Integer.toHexString((int) (Math.random() * 0xFFFFFF));
        Path dir;
        try {
            dir = Files.createTempDirectory("noveltts-merge-job-");
        } catch (IOException e) {
            throw new IllegalStateException("無法建立暫存目錄: " + e.getMessage(), e);
        }
        MergeJob job = new MergeJob(id, type, dir, expectedTotal);
        jobs.put(id, job);
        cleanupIfDue();
        return job;
    }

    /**
     * 取得工作。
     *
     * @param id 工作 id
     * @return 工作；不存在或已過期回傳 null
     */
    public MergeJob get(String id) {
        MergeJob job = jobs.get(id);
        if (job == null) {
            return null;
        }
        if (job.isExpired()) {
            remove(id);
            return null;
        }
        return job;
    }

    /**
     * 追加一批片段。
     *
     * <p>用串流寫檔，不把整批讀進記憶體。
     *
     * @param job 工作
     * @param name 原始檔名
     * @param in 內容
     * @return 片段的暫存路徑
     */
    public Path appendPart(MergeJob job, String name, InputStream in) {
        int index;
        synchronized (job.parts) {
            index = job.parts.size();
        }
        Path target = job.dir().resolve(String.format("part_%05d.bin", index));
        try {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("寫入暫存檔失敗: " + e.getMessage(), e);
        }
        synchronized (job.parts) {
            job.parts.add(new Part(name == null ? ("part" + index) : name, target));
        }
        job.touch();
        return target;
    }

    /**
     * 移除工作並刪除暫存檔。
     *
     * @param id 工作 id
     */
    public void remove(String id) {
        MergeJob job = jobs.remove(id);
        if (job != null) {
            deleteDir(job.dir());
        }
    }

    /** 目前持有的工作數（測試與監控用）。 */
    public int size() {
        return jobs.size();
    }

    /** 每 60 次呼叫才真正清理一次，避免每次都掃整個 map。 */
    private void cleanupIfDue() {
        if (cleanupCounter.incrementAndGet() % 60 != 0) {
            return;
        }
        cleanupExpired();
    }

    /**
     * 定期清除過期的工作與暫存檔。
     *
     * <p>沒有這個排程的話，過期的工作只會在「有人建立新工作」時才被順帶清掉。
     * 沒有新工作時，暫存檔會一直留在磁碟上 —— 那就變成伺服器留存使用者音檔了。
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 60_000L)
    public void scheduledCleanup() {
        cleanupExpired();
    }

    /**
     * 清除所有過期的工作與暫存檔。
     *
     * <p>這是「伺服器不留存使用者音檔」最關鍵的一道保險 ——
     * 前端忘了取結果、或者中途放棄，暫存檔都會自動消失。
     */
    public void cleanupExpired() {
        jobs.entrySet().removeIf(e -> {
            if (e.getValue().isExpired()) {
                deleteDir(e.getValue().dir());
                return true;
            }
            return false;
        });
    }

    /** 遞迴刪除暫存目錄。 */
    private void deleteDir(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 暫存檔刪除失敗不影響主流程
                }
            });
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
