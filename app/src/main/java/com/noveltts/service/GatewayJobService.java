package com.noveltts.service;

import com.noveltts.ai.GatewayJobStore;
import com.noveltts.ai.GatewayProvider;
import com.noveltts.ai.dto.SegmentAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

/**
 * 閘道的背景分析工作。
 *
 * <p><b>為什麼要背景執行</b>
 *
 * <p>原本的流程是前端逐章呼叫 {@code /api/gateway/analyze}，
 * 有三個問題：
 *
 * <ol>
 *   <li><b>Cloudflare 100 秒牆</b>：單一章節分析若上游慢，整個請求會被
 *       砍成 524 HTML 錯誤頁。</li>
 *   <li><b>rate limit</b>：閘道限制每 IP 每分鐘 30 次呼叫，
 *       29 章的小說連續呼叫會直接觸發 429。</li>
 *   <li><b>總耗時</b>：29 章 × 約 15 秒 = 7 分鐘，使用者只能乾等。</li>
 * </ol>
 *
 * <p>改成背景工作後：
 *
 * <ul>
 *   <li>前端只發 1 個「開始分析」請求 + 每 2 秒 1 個「查進度」請求</li>
 *   <li>每次請求都只花幾毫秒，絕不會撞上 Cloudflare 的牆</li>
 *   <li>rate limit 不再是問題（29 章 = 30 次輪詢，一分鐘剛好）</li>
 *   <li>使用者關掉分頁再打開，結果還在（TTL 30 分鐘）</li>
 * </ul>
 */
@Service
public class GatewayJobService {

    private static final Logger log = LoggerFactory.getLogger(GatewayJobService.class);

    private final GatewayJobStore store;
    private final GatewayService gatewayService;
    private final ChapterSplitter chapterSplitter;
    private final com.noveltts.tts.AudioTool audioTool;
    private final com.noveltts.config.NovelTtsProperties properties;

    /**
     * 背景工作的執行緒池。
     *
     * <p>刻意只有 1 條執行緒：同一個 key 同時打太多上游請求會被
     * OpenRouter 限流，而且使用者一次通常只跑一個工作。
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "gateway-job");
        t.setDaemon(true);
        return t;
    });

    public GatewayJobService(GatewayJobStore store,
                             GatewayService gatewayService,
                             ChapterSplitter chapterSplitter,
                             com.noveltts.tts.AudioTool audioTool,
                             com.noveltts.config.NovelTtsProperties properties) {
        this.store = store;
        this.gatewayService = gatewayService;
        this.chapterSplitter = chapterSplitter;
        this.audioTool = audioTool;
        this.properties = properties;
    }

    /**
     * 開始背景分析。
     *
     * @param apiKey OpenRouter API Key（只在工作進行期間留在記憶體）
     * @param baseUrl 自訂 base url，可為 null
     * @param novelContent 小說全文
     * @param chapterMaxChars 每章字數上限
     * @param model 分析模型
     * @param fallbackModelId 備援模型
     * @param temperature 溫度
     * @param maxTokens max tokens 上限
     * @return 工作物件
     */
    public GatewayJobStore.Job startAnalysis(String apiKey, String baseUrl,
                                             List<ChapterInput> chapters,
                                             String model, String fallbackModelId,
                                             Double temperature, Integer maxTokens) {
        if (chapters == null || chapters.isEmpty()) {
            throw new IllegalArgumentException("沒有需要分析的章節");
        }

        GatewayJobStore.Job job = store.create(apiKey, baseUrl, chapters.size());
        job.status("PENDING");
        job.message("已排隊，共 " + chapters.size() + " 章");

        executor.submit(() -> runAnalysis(job, chapters, model, fallbackModelId,
                temperature, maxTokens));
        return job;
    }

    /**
     * 要分析的單一章節。
     *
     * @param index 章節編號
     * @param title 章節標題
     * @param content 章節內文
     */
    public record ChapterInput(Integer index, String title, String content) {
    }

    /** 逐章分析，回報進度。 */
    private void runAnalysis(GatewayJobStore.Job job, List<ChapterInput> chapters,
                             String model, String fallbackModelId,
                             Double temperature, Integer maxTokens) {
        job.status("RUNNING");
        try {
            for (ChapterInput chapter : chapters) {
                if (job.isCancelRequested()) {
                    job.status("CANCELLED");
                    job.message("已取消");
                    break;
                }
                job.message("分析第 " + chapter.index() + " / " + chapters.size() + " 章："
                        + (chapter.title() == null ? "" : chapter.title()));

                try {
                    SegmentAnalysis analysis = gatewayService.analyze(
                            provider(job), model, fallbackModelId,
                            chapter.title(), chapter.content(),
                            temperature, maxTokens, Map.of());

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("index", chapter.index());
                    result.put("title", chapter.title());
                    result.put("content", chapter.content());
                    result.put("charCount", chapter.content() == null ? 0 : chapter.content().length());
                    result.put("segments", analysis.segments());
                    job.results().add(result);
                } catch (Exception e) {
                    // 單章失敗不中斷整本：記下來繼續下一章
                    Map<String, Object> failure = new LinkedHashMap<>();
                    failure.put("index", chapter.index());
                    failure.put("title", chapter.title());
                    failure.put("message", String.valueOf(e.getMessage()));
                    job.failures().add(failure);
                    log.warn("工作 {} 第 {} 章分析失敗：{}", job.id(), chapter.index(), e.getMessage());
                }

                job.results().size();
                // done 用 results + failures 計算，避免多執行緒下的計數競爭
                jobDone(job);
                job.touch();
            }

            if (!"CANCELLED".equals(job.status())) {
                job.status(job.failures().isEmpty() ? "SUCCESS" : "PARTIAL");
                job.message(job.failures().isEmpty()
                        ? "分析完成：" + job.results().size() + " 章"
                        : "完成 " + job.results().size() + " 章，"
                                + job.failures().size() + " 章失敗");
            }
        } catch (Exception e) {
            log.error("工作 {} 執行失敗", job.id(), e);
            job.status("FAILED");
            job.message(String.valueOf(e.getMessage()));
        } finally {
            // 工作結束後立刻丟掉 API Key —— 伺服器手上不再有使用者的憑證
            job.clearApiKey();
        }
    }

    /** 更新已完成章節數。 */
    private void jobDone(GatewayJobStore.Job job) {
        // Job.done 是 volatile int，這裡用目前已完成 + 失敗的總數。
        // 兩者都由同一個執行緒更新，不會有競爭。
        job.setDone(job.results().size() + job.failures().size());
    }

    /**
     * 開始背景語音合成。
     *
     * <p><b>為什麼要背景執行</b>
     *
     * <p>一本長篇小說有 500 段以上。前端逐段同步呼叫會遇到三個問題：
     *
     * <ol>
     *   <li>閘道每 IP 每分鐘 30 次的 rate limit —— 500 段要跑 17 分鐘才跑得完</li>
     *   <li>Cloudflare 的 100 秒代理逾時 —— 慢的段落會變成 524</li>
     *   <li>串行呼叫 —— 每段 5～20 秒，總計要一兩個小時</li>
     * </ol>
     *
     * <p>改成背景工作後可以<b>併行</b>合成（預設 3 路），
     * 總時間直接縮短三倍，而且使用者關掉分頁再打開進度還在。
     *
     * <p><b>音檔怎麼回傳</b>
     *
     * <p>音檔比分析結果大 500 倍（500 段約 30MB），不能整包塞在狀態回應裡。
     * 採「分段取回、取回即釋放」：客戶端帶著已存檔的序號來取，
     * 伺服器把舊的音檔丟掉，記憶體用量因此維持在可控範圍。
     *
     * @param apiKey OpenRouter API Key
     * @param baseUrl 自訂 base url，可為 null
     * @param groups 要合成的 turn 群組（每組一次 multi-speaker 請求）
     * @param model 語音模型
     * @param fallbackModelId 備援模型
     * @param globalInstructions 全域語氣指令
     * @return 工作物件
     */
    public GatewayJobStore.Job startSynthesis(String apiKey, String baseUrl,
                                               List<TtsGroupInput> groups,
                                               String model, String fallbackModelId,
                                               String globalInstructions) {
        if (groups == null || groups.isEmpty()) {
            throw new IllegalArgumentException("沒有需要合成的段落");
        }
        int total = groups.stream().mapToInt(g -> g.turns() == null ? 0 : g.turns().size()).sum();

        GatewayJobStore.Job job = store.create(apiKey, baseUrl, total);
        job.type("SYNTHESIZE");
        job.status("PENDING");
        job.message("已排隊，共 " + groups.size() + " 組 / " + total + " 段");

        executor.submit(() -> runSynthesis(job, groups, model, fallbackModelId, globalInstructions));
        return job;
    }

    /**
     * 要合成的一組 turns。
     *
     * <p>一組會送出一次 multi-speaker 請求。分組由前端決定
     * （前端已有 {@code logic.js} 的 buildTtsGroups），伺服器照做即可，
     * 避免兩邊的分組規則不一致。
     *
     * @param seq 起始序號
     * @param segmentIds 這組涵蓋的段落 id
     * @param chapterId 章節 id
     * @param voice 這組使用的音色（給前端存檔用）
     * @param turns 要合成的 turns
     * @param model 這組使用的模型（音色必須搭配所屬模型）
     */
    public record TtsGroupInput(Integer seq, List<String> segmentIds, String chapterId,
                                 String voice, List<TtsTurnInput> turns, String model) {
    }

    /**
     * 一個 turn。
     *
     * @param text 文字
     * @param voice 音色
     * @param instructions 語氣指令
     */
    public record TtsTurnInput(String text, String voice, String instructions) {
    }

    /** 併行合成各組，回報進度。 */
    private void runSynthesis(GatewayJobStore.Job job, List<TtsGroupInput> groups,
                              String model, String fallbackModelId, String globalInstructions) {
        job.status("RUNNING");
        int concurrency = Math.max(1, properties.getTtsConcurrency());
        java.util.concurrent.atomic.AtomicInteger cursor =
                new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger completed =
                new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.ExecutorService pool =
                Executors.newFixedThreadPool(concurrency, r -> {
                    Thread t = new Thread(r, "gateway-tts");
                    t.setDaemon(true);
                    return t;
                });
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (TtsGroupInput group : groups) {
                futures.add(pool.submit(() -> {
                    if (job.isCancelRequested()) {
                        return;
                    }
                    int seq = group.seq() == null ? cursor.getAndIncrement()
                            : group.seq();
                    try {
                        byte[] mp3 = synthesizeGroup(job, group, model, fallbackModelId,
                                globalInstructions);
                        List<String> ids = group.segmentIds() == null ? List.of() : group.segmentIds();
                        job.putAudio(new GatewayJobStore.Job.AudioEntry(
                                seq,
                                ids.isEmpty() ? "" : ids.get(0),
                                group.chapterId() == null ? "" : group.chapterId(),
                                group.voice() == null ? "" : group.voice(),
                                mp3));
                        completed.incrementAndGet();
                        job.setDone(completed.get());
                        job.message("已合成 " + completed.get() + " / " + job.total() + " 段");
                    } catch (Exception e) {
                        Map<String, Object> failure = new LinkedHashMap<>();
                        failure.put("index", seq);
                        failure.put("title", group.chapterId());
                        failure.put("message", String.valueOf(e.getMessage()));
                        job.failures().add(failure);
                        log.warn("工作 {} 第 {} 組合成失敗：{}", job.id(), seq, e.getMessage());
                        completed.incrementAndGet();
                        job.setDone(completed.get());
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                f.get();
            }
            job.status(job.failures().isEmpty() ? "SUCCESS" : "PARTIAL");
            job.message(job.failures().isEmpty()
                    ? "合成完成：" + completed.get() + " 段"
                    : "完成 " + completed.get() + " 段，" + job.failures().size() + " 段失敗");
        } catch (Exception e) {
            log.error("工作 {} 合成失敗", job.id(), e);
            job.status("FAILED");
            job.message(String.valueOf(e.getMessage()));
        } finally {
            pool.shutdownNow();
            // 合成結束後立刻丟掉 API Key
            job.clearApiKey();
        }
    }

    /**
     * 合成一組 turns 並轉成 MP3。
     *
     * @return MP3 位元組
     */
    private byte[] synthesizeGroup(GatewayJobStore.Job job, TtsGroupInput group,
                                   String model, String fallbackModelId, String globalInstructions) {
        List<com.noveltts.ai.dto.TtsTurn> turns = new ArrayList<>();
        for (TtsTurnInput t : group.turns()) {
            turns.add(new com.noveltts.ai.dto.TtsTurn(t.text(), t.voice(),
                    t.instructions() == null ? "" : t.instructions()));
        }
        // 群組自帶模型時優先用它 —— 音色必須搭配所屬模型
        String effectiveModel = (group.model() == null || group.model().isBlank())
                ? model : group.model();

        com.noveltts.ai.dto.SpeechResult result = gatewayService.synthesize(
                provider(job), effectiveModel, fallbackModelId, turns, globalInstructions);
        return pcmToMp3(result.audio());
    }

    /**
     * PCM → MP3。轉檔用暫存檔，轉完即刪。
     *
     * <p>音檔只在伺服器的暫存磁碟上停留幾秒，不會留在磁碟上。
     */
    private byte[] pcmToMp3(byte[] pcm) {
        java.nio.file.Path pcmFile = null;
        java.nio.file.Path mp3File = null;
        try {
            java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("noveltts-job-");
            pcmFile = tmpDir.resolve("in.pcm");
            mp3File = tmpDir.resolve("out.mp3");
            java.nio.file.Files.write(pcmFile, pcm);
            audioTool.pcmToMp3(pcmFile, mp3File, properties.getAudioSampleRate());
            return java.nio.file.Files.readAllBytes(mp3File);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("PCM 轉 MP3 失敗: " + e.getMessage(), e);
        } finally {
            deleteQuietly(pcmFile);
            deleteQuietly(mp3File);
        }
    }

    /** 刪除暫存檔，失敗不影響主流程。 */
    private void deleteQuietly(java.nio.file.Path p) {
        if (p == null) {
            return;
        }
        try {
            java.nio.file.Files.deleteIfExists(p);
            java.nio.file.Files.deleteIfExists(p.getParent());
        } catch (java.io.IOException ignored) {
            // 暫存檔刪除失敗不影響主流程
        }
    }

    /**
     * 依工作目前持有的憑證建立 provider。
     *
     * <p>逾時設 180 秒：背景工作不在 Cloudflare 的 100 秒代理逾時之下
     * （那個限制只作用於「瀏覽器 → 伺服器」的請求），所以可以安心等更久。
     */
    private GatewayProvider provider(GatewayJobStore.Job job) {
        return new GatewayProvider("openrouter", job.baseUrl(), job.apiKey(), Map.of(), 180);
    }

    /**
     * 取消工作。
     *
     * @param jobId 工作 id
     * @return 是否找到該工作
     */
    public boolean cancel(String jobId) {
        GatewayJobStore.Job job = store.get(jobId);
        if (job == null) {
            return false;
        }
        job.requestCancel();
        job.message("正在取消…");
        return true;
    }

    /**
     * 刪除工作（連同結果與 Key）。
     *
     * @param jobId 工作 id
     * @return 是否找到該工作
     */
    public boolean delete(String jobId) {
        GatewayJobStore.Job job = store.get(jobId);
        if (job == null) {
            return false;
        }
        job.requestCancel();
        store.remove(jobId);
        return true;
    }

    /** 應用程式關閉時停止執行緒池。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
