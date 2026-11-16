package com.noveltts.service;

import com.noveltts.ai.AiCallException;
import com.noveltts.ai.OpenRouterClient;
import com.noveltts.ai.PromptBuilder;
import com.noveltts.ai.SegmentParser;
import com.noveltts.ai.dto.ChatMessage;
import com.noveltts.ai.dto.ChatResult;
import com.noveltts.ai.dto.SegmentAnalysis;
import com.noveltts.config.CryptoException;
import com.noveltts.config.NovelTtsProperties;
import com.noveltts.domain.entity.AiModelSetting;
import com.noveltts.domain.entity.AiProviderConfig;
import com.noveltts.domain.entity.Chapter;
import com.noveltts.domain.entity.DialogueSegment;
import com.noveltts.domain.entity.Novel;
import com.noveltts.domain.entity.NovelCharacter;
import com.noveltts.domain.enums.ChapterStatus;
import com.noveltts.domain.enums.ModelPurpose;
import com.noveltts.domain.enums.NovelStatus;
import com.noveltts.domain.enums.SegmentType;
import com.noveltts.domain.enums.TtsStatus;
import com.noveltts.domain.repository.ChapterRepository;
import com.noveltts.domain.repository.DialogueSegmentRepository;
import com.noveltts.domain.repository.NovelCharacterRepository;
import com.noveltts.domain.repository.NovelRepository;
import com.noveltts.domain.repository.TtsVoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 分析：章節 → 段落 + 角色。
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final OpenRouterClient openRouterClient;
    private final SegmentParser segmentParser;
    private final AiSettingsService aiSettingsService;
    private final UsageLogService usageLogService;
    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;
    private final DialogueSegmentRepository segmentRepository;
    private final NovelCharacterRepository characterRepository;
    private final TtsVoiceRepository voiceRepository;
    private final NovelTtsProperties properties;

    public AnalysisService(OpenRouterClient openRouterClient,
                            SegmentParser segmentParser,
                            AiSettingsService aiSettingsService,
                            UsageLogService usageLogService,
                            NovelRepository novelRepository,
                            ChapterRepository chapterRepository,
                            DialogueSegmentRepository segmentRepository,
                            NovelCharacterRepository characterRepository,
                            TtsVoiceRepository voiceRepository,
                            NovelTtsProperties properties) {
        this.openRouterClient = openRouterClient;
        this.segmentParser = segmentParser;
        this.aiSettingsService = aiSettingsService;
        this.usageLogService = usageLogService;
        this.novelRepository = novelRepository;
        this.chapterRepository = chapterRepository;
        this.segmentRepository = segmentRepository;
        this.characterRepository = characterRepository;
        this.voiceRepository = voiceRepository;
        this.properties = properties;
    }

    /**
     * 分析單一章節。
     *
     * @return 建立的段落數
     */
    @Transactional
    public int analyzeChapter(Chapter chapter, Long jobId) {
        AiProviderConfig provider = aiSettingsService.requireProvider();
        AiModelSetting model = aiSettingsService.requireModel(ModelPurpose.ANALYSIS);

        chapter.setStatus(ChapterStatus.ANALYZING);
        chapterRepository.save(chapter);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", PromptBuilder.SYSTEM_PROMPT));
        messages.add(new ChatMessage("user", PromptBuilder.userPrompt(chapter)));

        SegmentAnalysis analysis;
        try {
            analysis = callWithRetry(provider, model, messages, chapter);
        } catch (AiCallException e) {
            chapter.setStatus(ChapterStatus.FAILED);
            chapterRepository.save(chapter);
            throw e;
        }
        if (analysis == null) {
            chapter.setStatus(ChapterStatus.FAILED);
            chapterRepository.save(chapter);
            throw new IllegalStateException("章節 " + chapter.getId() + " 分析失敗：AI 回應格式不正確");
        }

        // 清掉舊段落
        segmentRepository.deleteByChapterId(chapter.getId());
        segmentRepository.flush();

        Novel novel = chapter.getNovel();
        int seq = 1;
        for (SegmentAnalysis.Segment s : analysis.segments()) {
            DialogueSegment segment = new DialogueSegment();
            segment.setChapter(chapter);
            segment.setSeq(seq);
            SegmentType type = "DIALOGUE".equalsIgnoreCase(s.type() == null ? "" : s.type())
                    ? SegmentType.DIALOGUE : SegmentType.NARRATION;
            segment.setType(type);
            segment.setText(s.text().strip());
            segment.setCharCount(s.text().length());
            segment.setEmotion(s.emotion());
            segment.setStyleHint(s.style_hint());
            segment.setInstructions(s.style_instructions());
            segment.setTtsStatus(TtsStatus.PENDING);

            if (type == SegmentType.DIALOGUE) {
                NovelCharacter character = findOrCreateCharacter(novel, s.character_name().strip());
                segment.setCharacter(character);
                segment.setSpeakerName(character.getName());
                character.setSegmentCount(character.getSegmentCount() + 1);
                characterRepository.save(character);
            } else {
                segment.setSpeakerName("旁白");
            }
            segmentRepository.save(segment);
            seq++;
        }

        chapter.setStatus(ChapterStatus.ANALYZED);
        chapterRepository.save(chapter);
        log.info("章節 {} 分析完成，共 {} 段", chapter.getId(), seq - 1);
        return seq - 1;
    }

    private SegmentAnalysis callWithRetry(AiProviderConfig provider, AiModelSetting model,
                                          List<ChatMessage> messages, Chapter chapter) {
        ChatResult result = null;
        Exception lastError = null;

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                result = openRouterClient.chat(provider, model.getModelId(), messages,
                        model.getTemperature() == null ? null : model.getTemperature().doubleValue(),
                        model.getMaxTokens(), model.getParams());
                if (result.usage() != null) {
                    usageLogService.logText(provider.getProvider(), model.getModelId(),
                            result.usage().promptTokens(), result.usage().completionTokens(),
                            result.usage().totalSpend(), null);
                }
                return segmentParser.parse(result.content());
            } catch (AiCallException e) {
                // 上游呼叫失敗：429/5xx 可重試，其他 4xx 直接失敗不重試
                lastError = e;
                log.warn("AI 呼叫嘗試 {} 失敗 (HTTP {}): {}", attempt + 1, e.getStatusCode(), e.getMessage());
                if (!e.isRetryable()) {
                    throw e;
                }
                if (attempt == 0) {
                    try {
                        Thread.sleep(1000L * (1L << attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            } catch (CryptoException e) {
                // 設定類問題（主金鑰錯誤），重試無意義，直接失敗
                log.error("AI 呼叫前置檢查失敗: {}", e.getMessage());
                throw e;
            } catch (IllegalStateException e) {
                // 回應格式問題：帶錯誤訊息重試一次
                lastError = e;
                log.warn("AI 回應解析嘗試 {} 失敗: {}", attempt + 1, e.getMessage());
                if (attempt == 0) {
                    List<ChatMessage> retry = new ArrayList<>();
                    retry.add(new ChatMessage("system", PromptBuilder.SYSTEM_PROMPT));
                    retry.add(new ChatMessage("user", PromptBuilder.retryPrompt(chapter, e.getMessage())));
                    messages = retry;
                }
            }
        }
        log.error("章節 {} 分析最終失敗", chapter.getId(), lastError);
        return null;
    }

    @Transactional
    public NovelCharacter findOrCreateCharacter(Novel novel, String name) {
        return characterRepository.findByNovelIdAndName(novel.getId(), name)
                .orElseGet(() -> {
                    NovelCharacter c = new NovelCharacter();
                    c.setNovel(novel);
                    c.setName(name);
                    c.setAliases(new String[0]);
                    c.setColorHex(pickColor(name));
                    return characterRepository.save(c);
                });
    }

    /** 依角色名穩定分配色碼，讓 UI 上同一角色永遠同色。 */
    private String pickColor(String name) {
        String[] palette = {"#4C6EF5", "#F76707", "#12B886", "#E64980", "#7950F2",
                            "#0C8599", "#F59F00", "#5C7CFA", "#D6336C", "#2F9E44"};
        int hash = Math.abs(name == null ? 0 : name.hashCode());
        return palette[hash % palette.length];
    }

    /** 全本分析入口（同步呼叫，由 job 服務在背景執行緒觸發）。 */
    @Transactional
    public int analyzeNovel(Long novelId, List<Long> chapterIds, ProgressListener listener) {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new IllegalStateException("找不到小說 " + novelId));
        novel.setStatus(NovelStatus.ANALYZING);
        novelRepository.save(novel);

        List<Chapter> chapters = chapterRepository.findByNovelIdOrderByChapterIndex(novelId);
        if (chapterIds != null && !chapterIds.isEmpty()) {
            chapters = chapters.stream().filter(c -> chapterIds.contains(c.getId())).toList();
        }
        int total = chapters.size();
        int done = 0;
        int failed = 0;
        for (Chapter chapter : chapters) {
            if (listener != null) {
                listener.onProgress(done, total, "正在分析第 " + chapter.getChapterIndex() + " 章");
            }
            try {
                analyzeChapter(chapter, null);
            } catch (Exception e) {
                failed++;
                log.error("章節 {} 分析失敗", chapter.getId(), e);
            }
            done++;
        }

        if (failed > 0) {
            novel.setStatus(NovelStatus.FAILED);
            novelRepository.save(novel);
            throw new IllegalStateException(
                    failed + " / " + total + " 章分析失敗，詳見各章狀態與伺服器記錄");
        }

        novel.setStatus(NovelStatus.READY);
        novelRepository.save(novel);
        if (listener != null) {
            listener.onProgress(total, total, "分析完成");
        }
        return total;
    }

    /** 進度回呼介面。 */
    public interface ProgressListener {
        void onProgress(int progress, int total, String message);
    }
}
