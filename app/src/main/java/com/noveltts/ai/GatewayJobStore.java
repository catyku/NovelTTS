package com.noveltts.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 閘道背景工作的記憶體存放區。
 *
 * <p><b>為什麼放記憶體而不是資料庫</b>
 *
 * <p>這個專案的核心原則是「訪客資料不留在伺服器」——小說內容、音檔、
 * 分析結果全部存在使用者自己的瀏覽器裡，伺服器只是無狀態的 AI 閘道。
 *
 * <p>背景工作需要暫存「做到哪裡」與「已經算好的結果」，
 * 放資料庫等於把訪客的小說內容寫進伺服器磁碟，違反那條原則。
 * 放記憶體則是：
 *
 * <ul>
 *   <li>不落盤、不進資料庫、不備份</li>
 *   <li>容器重啟就消失（可接受：使用者重按一次即可）</li>
 *   <li>工作結束或逾時後整筆清除，伺服器手上不留任何使用者內容</li>
 * </ul>
 *
 * <p><b>生命週期</b>
 *
 * <p>工作有兩種結束方式：
 *
 * <ul>
 *   <li>正常結束（成功或失敗）→ 立刻清除 API Key，結果保留到 TTL 到期
 *       （前端可能還沒來取最後一批結果）</li>
 *   <li>逾時未完成 → 整筆連同 Key 一起清除</li>
 * </ul>
 *
 * <p>API Key 只在記憶體中存在，且只在工作進行期間。
 * 它不會寫入磁碟、不會出現在日誌（{@code toString} 已遮蔽）。
 */
@Component
public class GatewayJobStore {

    private static final Logger log = LoggerFactory.getLogger(GatewayJobStore.class);

    /** 工作完成後，結果保留多久（毫秒）。前端可能晚一點才來取結果。 */
    private static final long RESULT_TTL_MS = 30 * 60 * 1000L;

    /** 進行中的工作最長可以跑多久（毫秒）。超過就強制中止並清除。 */
    private static final long RUNNING_TIMEOUT_MS = 60 * 60 * 1000L;

    /** 每分鐘清理一次。 */
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger cleanupCounter = new AtomicInteger();

    /**
     * 一個背景工作的狀態。
     *
     * <p>欄位刻意保持最小：只放「做到哪」與「結果」，
     * 不放小說全文以外的任何東西。
     */
    public static final class Job {
        /** 唯一 id。 */
        private final String id;
        /** OpenRouter API Key —— 只在工作進行期間存在於記憶體。 */
        private volatile String apiKey;
        /** 自訂 base url（測試用），null 表示用預設。 */
        private final String baseUrl;
        /** 狀態：PENDING / RUNNING / SUCCESS / FAILED / CANCELLED。 */
        private volatile String status = "PENDING";
        /** 已完成的章節數。 */
        private volatile int done;
        /** 總章節數。 */
        private volatile int total;
        /** 目前正在處理的章節標題（給前端顯示）。 */
        private volatile String message = "";
        /** 已完成的章節結果。 */
        private final List<Object> results = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** 失敗的章節。 */
        private final List<Map<String, Object>> failures =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        /** 建立時間。 */
        private final long createdAt = System.currentTimeMillis();
        /** 最後更新時間。 */
        private volatile long updatedAt = System.currentTimeMillis();
        /** 是否要求中止。 */
        private volatile boolean cancelRequested;
        /** 工作類型：ANALYZE 或 SYNTHESIZE。 */
        private volatile String type = "ANALYZE";
        /**
         * 語音合成的結果（序號 → 音檔）。
         *
         * <p>音檔比分析結果大得多（500 段約 30MB），不能像 JSON 那樣
         * 整包塞進 snapshot。所以採「分段取回、取回即釋放」：
         * 客戶端每輪帶著已存檔的序號（ack）來取，伺服器把 ack 之前的
         * 音檔丟掉，避免記憶體無限累積。
         */
        private final Map<Integer, AudioEntry> audioResults =
                java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
        /** 客戶端已確認存檔的最大序號；小於等於此值的音檔會被釋放。 */
        private volatile int ackCursor = -1;
        /** 已取回（交給客戶端）的序號集合。 */
        private final java.util.Set<Integer> takenSeqs =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        /**
         * 一段語音合成的結果。
         *
         * @param seq 序號
         * @param segmentId 段落 id
         * @param chapterId 章節 id
         * @param voice 使用的音色
         * @param mp3 MP3 位元組
         */
        public record AudioEntry(int seq, String segmentId, String chapterId,
                                 String voice, byte[] mp3) {
        }

        Job(String id, String apiKey, String baseUrl, int total) {
            this.id = id;
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.total = total;
        }

        public String id() {
            return id;
        }

        public String apiKey() {
            return apiKey;
        }

        public String baseUrl() {
            return baseUrl;
        }

        public void clearApiKey() {
            this.apiKey = null;
        }

        public String status() {
            return status;
        }

        public void status(String s) {
            this.status = s;
            touch();
        }

        public int done() {
            return done;
        }

        public void setDone(int d) {
            this.done = d;
        }

        public int total() {
            return total;
        }

        public String message() {
            return message;
        }

        public void message(String m) {
            this.message = m == null ? "" : m;
            touch();
        }

        public List<Object> results() {
            return results;
        }

        public List<Map<String, Object>> failures() {
            return failures;
        }

        public void touch() {
            this.updatedAt = System.currentTimeMillis();
        }

        public long updatedAt() {
            return updatedAt;
        }

        public long createdAt() {
            return createdAt;
        }

        public boolean isCancelRequested() {
            return cancelRequested;
        }

        public void requestCancel() {
            this.cancelRequested = true;
        }

        public String type() {
            return type;
        }

        public void type(String t) {
            this.type = t;
        }

        /** 放入一段合成好的音檔。 */
        public void putAudio(AudioEntry entry) {
            audioResults.put(entry.seq(), entry);
            touch();
        }

        /**
         * 取回尚未取走的音檔，並釋放 ack 之前的。
         *
         * <p><b>ack 必須是「連續已取回的最大前綴」，不能是已取回的最大值</b>。
         * 合成是併行的，回來的順序不保險 —— 例如先回傳 seq 2 再回傳 seq 0。
         * 若用最大值當 ack，seq 0 與 seq 1 會被誤判成「已取回」而遭釋放，
         * 這兩段音檔就永遠拿不到了。
         *
         * <p>因此伺服器自己追蹤已取回的序號集合，只釋放那些「序號小於 ack
         * 且確實已被取回」的項目。ack 只是客戶端提供的線索，伺服器會驗證。
         *
         * @param ack 客戶端已確認存檔的最大序號（線索）
         * @param limit 本次最多取回幾段（避免單次回應過大）
         * @return 待取回的音檔
         */
        public List<AudioEntry> takeAudioAfter(int ack, int limit) {
            List<AudioEntry> out = new ArrayList<>();
            synchronized (audioResults) {
                for (Map.Entry<Integer, AudioEntry> e : audioResults.entrySet()) {
                    // 只取「還沒被取走過」的項目
                    if (takenSeqs.contains(e.getKey())) continue;
                    out.add(e.getValue());
                    if (out.size() >= limit) break;
                }
                // 標記為已交給客戶端，但先不刪除 ——
                // 萬一網路失敗，前端可以再取一次（重試機制）
                for (AudioEntry e : out) {
                    takenSeqs.add(e.seq());
                }
            }
            // 釋放：序號小於 ack 且已交給客戶端 → 客戶端肯定已經存好了
            if (ack >= 0) {
                synchronized (audioResults) {
                    audioResults.keySet().removeIf(k -> k <= ack && takenSeqs.contains(k));
                }
            }
            return out;
        }

        /**
         * 讓已交給客戶端但尚未確認的音檔可以再取一次。
         *
         * <p>前端在網路失敗時呼叫，把「交出但沒存到」的序號退回來重送。
         *
         * @param seq 序號
         */
        public void requeue(long seq) {
            takenSeqs.remove((int) seq);
            touch();
        }

        /**
         * 退回一批序號（前端存檔失敗時重試用）。
         *
         * @param seqs 序號清單
         */
        public void requeueAll(java.util.Collection<Long> seqs) {
            if (seqs == null) return;
            for (Long s : seqs) {
                if (s != null) takenSeqs.remove(s.intValue());
            }
            touch();
        }

        /** 目前待取回的音檔數。 */
        public int pendingAudioCount() {
            return audioResults.size();
        }

        /** 尚未取走的音檔總位元組數（記憶體用量監控用）。 */
        public long pendingAudioBytes() {
            long total = 0;
            synchronized (audioResults) {
                for (AudioEntry e : audioResults.values()) {
                    total += e.mp3() == null ? 0 : e.mp3().length;
                }
            }
            return total;
        }

        /** 是否已過期。 */
        boolean isExpired() {
            long age = System.currentTimeMillis() - updatedAt;
            if ("PENDING".equals(status) || "RUNNING".equals(status)) {
                return age > RUNNING_TIMEOUT_MS;
            }
            return age > RESULT_TTL_MS;
        }

        /** 對外回應用的快照。 */
        public Map<String, Object> snapshot() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("jobId", id);
            m.put("status", status);
            m.put("done", done);
            m.put("total", total);
            m.put("message", message);
            m.put("results", results);
            m.put("failures", failures);
            m.put("createdAt", Instant.ofEpochMilli(createdAt).toString());
            m.put("updatedAt", Instant.ofEpochMilli(updatedAt).toString());
            return m;
        }

        /** 遮蔽 API Key，避免意外被記錄。 */
        @Override
        public String toString() {
            return "Job{id=" + id + ", status=" + status + ", done=" + done + "/" + total + "}";
        }
    }

    /**
     * 建立一個新工作。
     *
     * @param apiKey OpenRouter API Key
     * @param baseUrl 自訂 base url，可為 null
     * @param total 總章節數
     * @return 工作物件
     */
    public Job create(String apiKey, String baseUrl, int total) {
        String id = "job_" + Long.toHexString(System.nanoTime()) + "_"
                + Integer.toHexString((int) (Math.random() * 0xFFFFFF));
        Job job = new Job(id, apiKey, baseUrl, total);
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
    public Job get(String id) {
        Job job = jobs.get(id);
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
     * 移除工作（取消或完成後清理）。
     *
     * @param id 工作 id
     */
    public void remove(String id) {
        Job job = jobs.remove(id);
        if (job != null) {
            job.clearApiKey();
        }
    }

    /** 目前持有的工作數（測試與監控用）。 */
    public int size() {
        return jobs.size();
    }

    /**
     * 定期清除過期工作。
     *
     * <p>每 60 次呼叫才真正清理一次，避免每次都掃整個 map。
     */
    private void cleanupIfDue() {
        if (cleanupCounter.incrementAndGet() % 60 != 0) {
            return;
        }
        cleanupExpired();
    }

    /**
     * 清除所有過期的工作。
     *
     * <p>過期的工作會連同 API Key 一起被丟掉 —— 這是「不保留使用者資料」
     * 最關鍵的一道保險。
     */
    public void cleanupExpired() {
        jobs.entrySet().removeIf(e -> {
            if (e.getValue().isExpired()) {
                e.getValue().clearApiKey();
                return true;
            }
            return false;
        });
    }
}
