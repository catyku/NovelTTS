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
                             ChapterSplitter chapterSplitter) {
        this.store = store;
        this.gatewayService = gatewayService;
        this.chapterSplitter = chapterSplitter;
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
