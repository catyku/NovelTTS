package com.noveltts.service;

import com.noveltts.ai.AiCallException;
import com.noveltts.ai.OpenRouterClient;
import com.noveltts.ai.dto.SpeechResult;
import com.noveltts.ai.dto.TtsRequest;
import com.noveltts.ai.dto.TtsTurn;
import com.noveltts.config.NovelTtsProperties;
import com.noveltts.domain.entity.AiModelSetting;
import com.noveltts.domain.entity.AiProviderConfig;
import com.noveltts.domain.entity.AudioFile;
import com.noveltts.domain.entity.Chapter;
import com.noveltts.domain.entity.DialogueSegment;
import com.noveltts.domain.entity.Novel;
import com.noveltts.domain.entity.NovelCharacter;
import com.noveltts.domain.enums.AudioKind;
import com.noveltts.domain.enums.AudioStatus;
import com.noveltts.domain.enums.ChapterStatus;
import com.noveltts.domain.enums.ModelPurpose;
import com.noveltts.domain.enums.NovelStatus;
import com.noveltts.domain.enums.TtsStatus;
import com.noveltts.domain.repository.AudioFileRepository;
import com.noveltts.domain.repository.ChapterRepository;
import com.noveltts.domain.repository.DialogueSegmentRepository;
import com.noveltts.domain.repository.NovelRepository;
import com.noveltts.tts.AudioTool;
import com.noveltts.tts.TtsGroup;
import com.noveltts.tts.TtsGroupBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * TTS 合成：段落 → OpenRouter audio/speech → PCM → MP3 → 章節/整本合併。
 */
@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    private final OpenRouterClient openRouterClient;
    private final TtsGroupBuilder groupBuilder;
    private final AudioTool audioTool;
    private final StorageService storageService;
    private final AiSettingsService aiSettingsService;
    private final UsageLogService usageLogService;
    private final ModelPricingService modelPricingService;
    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;
    private final DialogueSegmentRepository segmentRepository;
    private final AudioFileRepository audioFileRepository;
    private final NovelTtsProperties properties;
    private final GroupService groupService;

    public TtsService(OpenRouterClient openRouterClient,
                      TtsGroupBuilder groupBuilder,
                      AudioTool audioTool,
                      StorageService storageService,
                      AiSettingsService aiSettingsService,
                      UsageLogService usageLogService,
                      ModelPricingService modelPricingService,
                      NovelRepository novelRepository,
                      ChapterRepository chapterRepository,
                      DialogueSegmentRepository segmentRepository,
                      AudioFileRepository audioFileRepository,
                      NovelTtsProperties properties,
                      GroupService groupService) {
        this.openRouterClient = openRouterClient;
        this.groupBuilder = groupBuilder;
        this.audioTool = audioTool;
        this.storageService = storageService;
        this.aiSettingsService = aiSettingsService;
        this.usageLogService = usageLogService;
        this.modelPricingService = modelPricingService;
        this.novelRepository = novelRepository;
        this.chapterRepository = chapterRepository;
        this.segmentRepository = segmentRepository;
        this.audioFileRepository = audioFileRepository;
        this.properties = properties;
        this.groupService = groupService;
    }

    /**
     * 合成單一章節的段落。
     */
    @Transactional
    public int synthesizeChapter(Chapter chapter, Long jobId, boolean onlyPending) {
        List<DialogueSegment> segments = segmentRepository.findByChapterIdOrderBySeq(chapter.getId());
        if (onlyPending) {
            segments = segments.stream()
                    .filter(s -> s.getTtsStatus() == TtsStatus.PENDING || s.getTtsStatus() == TtsStatus.FAILED)
                    .toList();
        }
        if (segments.isEmpty()) {
            return 0;
        }

        AiProviderConfig provider = aiSettingsService.requireProvider();
        AiModelSetting model = aiSettingsService.requireModel(ModelPurpose.TTS);

        chapter.setStatus(ChapterStatus.SYNTHESIZING);
        chapterRepository.save(chapter);

        List<TtsGroup> groups = groupBuilder.build(segments, resolveGroupContext(chapter));
        Semaphore semaphore = new Semaphore(Math.max(1, properties.getTtsConcurrency()));

        int success = 0;
        int failed = 0;
        String lastError = null;
        for (TtsGroup group : groups) {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                AudioFile audio = synthesizeGroup(provider, model, chapter, group, jobId);
                if (audio != null) {
                    success++;
                }
            } catch (Exception e) {
                log.error("章節 {} 的一組段落合成失敗", chapter.getId(), e);
                markGroupFailed(group, e.getMessage());
                failed++;
                lastError = e.getMessage();
            } finally {
                semaphore.release();
            }
        }

        // 全部失敗時拋出，讓 job 正確標記 FAILED。
        // 原本無論成功或失敗都回 success，導致 job 永遠回報 SUCCESS。
        if (failed > 0 && success == 0) {
            chapter.setStatus(ChapterStatus.FAILED);
            chapterRepository.save(chapter);
            throw new IllegalStateException(
                    "章節 " + chapter.getChapterIndex() + " 全部 " + failed + " 組段落合成失敗: "
                            + (lastError == null ? "未知錯誤" : lastError));
        }

        chapter.setStatus(ChapterStatus.SYNTHESIZED);
        chapterRepository.save(chapter);
        return success;
    }

    /** 語音合成失敗時的重試嘗試次數（含首次呼叫）。 */
    private static final int MAX_SPEECH_ATTEMPTS = 3;

    /** 重試退避基準（毫秒），第 n 次等待 base * 2^n。 */
    private static final long RETRY_BACKOFF_BASE_MS = 1000L;

    /**
     * 語音合成，失敗時重試並可改用備援模型。
     *
     * <p>流程：
     * <ol>
     *   <li>以主要模型呼叫，遇 429/5xx 指數退避重試（最多 {@value #MAX_SPEECH_ATTEMPTS} 次）。</li>
     *   <li>仍失敗且設定了備援模型時，改用備援模型再試一次。</li>
     *   <li>其他 4xx（參數錯誤、模型不存在等）不重試 —— 重試也不會成功。</li>
     * </ol>
     */
    private SpeechResult synthesizeWithFallback(AiProviderConfig provider,
                                                AiModelSetting model,
                                                TtsRequest request,
                                                List<TtsTurn> turns,
                                                String globalInstructions,
                                                Map<String, Object> providerParams) {
        AiCallException lastError = null;

        // 階段一：主要模型重試
        for (int attempt = 0; attempt < MAX_SPEECH_ATTEMPTS; attempt++) {
            try {
                return openRouterClient.speech(provider, request);
            } catch (AiCallException e) {
                lastError = e;
                if (!e.isRetryable()) {
                    break;
                }
                log.warn("TTS 呼叫失敗 (HTTP {})，第 {}/{} 次重試: {}",
                        e.getStatusCode(), attempt + 1, MAX_SPEECH_ATTEMPTS, e.getMessage());
                if (attempt < MAX_SPEECH_ATTEMPTS - 1) {
                    sleepBackoff(attempt);
                }
            }
        }

        // 階段二：改用備援模型
        String fallbackModelId = model.getFallbackModelId();
        if (fallbackModelId != null && !fallbackModelId.isBlank()) {
            log.warn("主要模型 {} 失敗，改用備援模型 {}", model.getModelId(), fallbackModelId);
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

    /** 指數退避等待。 */
    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_BASE_MS * (1L << attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TTS 重試等待被中斷", e);
        }
    }

    private AudioFile synthesizeGroup(AiProviderConfig provider, AiModelSetting model,
                                      Chapter chapter, TtsGroup group, Long jobId) {
        List<TtsTurn> turns = group.turns().stream()
                .map(t -> new TtsTurn(t.text(),
                        t.voice(),
                        t.instructions() == null || t.instructions().isBlank() ? null : t.instructions()))
                .toList();

        // 由 fromTurns 決定用 multi-speaker 陣列或單字串形式：
        // Gemini TTS 在所有 turn 同一個 voice 時會拒絕陣列形式（400）。
        TtsRequest request = TtsRequest.fromTurns(
                model.getModelId(),
                turns,
                properties.getGlobalInstructions(),
                Map.of("zdr", true));

        SpeechResult result = synthesizeWithFallback(provider, model, request,
                turns, properties.getGlobalInstructions(), Map.of("zdr", true));

        // 落地
        Path dir = storageService.chapterDir(chapter.getNovel().getId(), chapter.getId());
        storageService.ensureDir(dir);
        String baseName = String.format("ch%03d_%s", chapter.getChapterIndex(),
                group.segments().get(0).getSeq() == null ? 0 : group.segments().get(0).getSeq());
        Path pcmFile = dir.resolve(baseName + ".pcm");
        Path mp3File = dir.resolve(baseName + ".mp3");

        try {
            Files.write(pcmFile, result.audio());
        } catch (IOException e) {
            throw new IllegalStateException("寫入 PCM 失敗: " + e.getMessage(), e);
        }
        audioTool.pcmToMp3(pcmFile, mp3File, properties.getAudioSampleRate());
        storageService.deleteFile(pcmFile);

        Integer durationMs = audioTool.durationMs(mp3File);
        Long byteSize = sizeOf(mp3File);

        AudioFile audio = new AudioFile();
        audio.setNovel(chapter.getNovel());
        audio.setChapter(chapter);
        audio.setSegment(group.segments().get(0));
        audio.setKind(AudioKind.SEGMENT);
        audio.setFileName(mp3File.getFileName().toString());
        audio.setFilePath(mp3File.toString());
        audio.setFormat("mp3");
        audio.setDurationMs(durationMs);
        audio.setByteSize(byteSize);
        audio.setProvider(provider.getProvider());
        audio.setModel(model.getModelId());
        audio.setVoiceId(group.turns().isEmpty() ? null : group.turns().get(0).voice());
        audio.setGenerationId(result.generationId());
        audio.setCharCount(group.charCount());
        audio.setStatus(AudioStatus.READY);
        audioFileRepository.save(audio);

        // 段落標記完成
        for (DialogueSegment segment : group.segments()) {
            segment.setTtsStatus(TtsStatus.DONE);
            segment.setAudio(audio);
            segment.setErrorMessage(null);
            segmentRepository.save(segment);
        }

        // TTS 端點不回報費用，用模型定價 × 字元數估算
        BigDecimal estimatedCost = modelPricingService.estimateSpeechCost(
                model.getModelId(), group.charCount());

        usageLogService.logAudio(provider.getProvider(), model.getModelId(),
                group.charCount(), durationMs, estimatedCost, jobId);
        return audio;
    }

    private void markGroupFailed(TtsGroup group, String message) {
        for (DialogueSegment segment : group.segments()) {
            segment.setTtsStatus(TtsStatus.FAILED);
            segment.setErrorMessage(message);
            segmentRepository.save(segment);
        }
    }

    /** 全本合成。 */
    @Transactional
    public int synthesizeNovel(Long novelId, boolean onlyPending, ProgressListener listener) {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new IllegalStateException("找不到小說 " + novelId));
        novel.setStatus(NovelStatus.SYNTHESIZING);
        novelRepository.save(novel);

        List<Chapter> chapters = chapterRepository.findByNovelIdOrderByChapterIndex(novelId);
        int total = chapters.size();
        int done = 0;
        StringBuilder failedChapters = null;
        for (Chapter chapter : chapters) {
            if (listener != null) {
                listener.onProgress(done, total, "正在合成第 " + chapter.getChapterIndex() + " 章");
            }
            try {
                synthesizeChapter(chapter, null, onlyPending);
            } catch (Exception e) {
                log.error("章節 {} 合成例外", chapter.getId(), e);
                // 記錄後繼續其他章節，但最後要回報整體失敗
                if (failedChapters == null) {
                    failedChapters = new StringBuilder();
                }
                failedChapters.append("第 ").append(chapter.getChapterIndex())
                        .append(" 章: ").append(e.getMessage()).append("; ");
            }
            done++;
        }

        if (failedChapters != null) {
            novel.setStatus(NovelStatus.FAILED);
            novelRepository.save(novel);
            throw new IllegalStateException(failedChapters.toString());
        }

        novel.setStatus(NovelStatus.SYNTHESIZED);
        novelRepository.save(novel);
        if (listener != null) {
            listener.onProgress(total, total, "合成完成");
        }
        return total;
    }

    /** 合併單一章節的段落音檔。 */
    @Transactional
    public AudioFile mergeChapter(Chapter chapter) {
        List<AudioFile> segments = audioFileRepository
                .findByChapterIdAndKindOrderByIdAsc(chapter.getId(), AudioKind.SEGMENT);
        if (segments.isEmpty()) {
            throw new IllegalStateException("章節尚未有段落音檔");
        }
        Path dir = storageService.chapterDir(chapter.getNovel().getId(), chapter.getId());
        storageService.ensureDir(dir);
        Path out = dir.resolve(String.format("ch%03d_full.mp3", chapter.getChapterIndex()));

        List<Path> inputs = new ArrayList<>();
        for (AudioFile a : segments) {
            inputs.add(Path.of(a.getFilePath()));
        }
        audioTool.concatMp3(inputs, out);

        AudioFile existing = audioFileRepository
                .findByChapterIdAndKindOrderByIdAsc(chapter.getId(), AudioKind.CHAPTER).stream()
                .findFirst().orElse(null);
        if (existing != null) {
            storageService.deleteFile(Path.of(existing.getFilePath()));
            existing.setFilePath(out.toString());
            existing.setFileName(out.getFileName().toString());
            existing.setDurationMs(audioTool.durationMs(out));
            existing.setByteSize(sizeOf(out));
            return audioFileRepository.save(existing);
        }

        AudioFile merged = new AudioFile();
        merged.setNovel(chapter.getNovel());
        merged.setChapter(chapter);
        merged.setKind(AudioKind.CHAPTER);
        merged.setFileName(out.getFileName().toString());
        merged.setFilePath(out.toString());
        merged.setFormat("mp3");
        merged.setDurationMs(audioTool.durationMs(out));
        merged.setByteSize(sizeOf(out));
        merged.setStatus(AudioStatus.READY);
        return audioFileRepository.save(merged);
    }

    /** 合併整本。 */
    @Transactional
    public AudioFile mergeNovel(Novel novel) {
        List<Chapter> chapters = chapterRepository.findByNovelIdOrderByChapterIndex(novel.getId());
        List<Path> inputs = new ArrayList<>();
        for (Chapter chapter : chapters) {
            List<AudioFile> chapterFiles = audioFileRepository
                    .findByChapterIdAndKindOrderByIdAsc(chapter.getId(), AudioKind.CHAPTER);
            if (chapterFiles.isEmpty()) {
                chapterFiles = audioFileRepository
                        .findByChapterIdAndKindOrderByIdAsc(chapter.getId(), AudioKind.SEGMENT);
            }
            for (AudioFile a : chapterFiles) {
                inputs.add(Path.of(a.getFilePath()));
            }
        }
        if (inputs.isEmpty()) {
            throw new IllegalStateException("尚無可合併的音檔");
        }
        Path dir = storageService.novelDir(novel.getId());
        storageService.ensureDir(dir);
        Path out = dir.resolve(sanitize(novel.getTitle()) + "_full.mp3");
        audioTool.concatMp3(inputs, out);

        AudioFile existing = audioFileRepository
                .findByNovelIdAndKindOrderByIdAsc(novel.getId(), AudioKind.BOOK).stream()
                .findFirst().orElse(null);
        if (existing != null) {
            storageService.deleteFile(Path.of(existing.getFilePath()));
            existing.setFilePath(out.toString());
            existing.setFileName(out.getFileName().toString());
            existing.setDurationMs(audioTool.durationMs(out));
            existing.setByteSize(sizeOf(out));
            return audioFileRepository.save(existing);
        }

        AudioFile merged = new AudioFile();
        merged.setNovel(novel);
        merged.setKind(AudioKind.BOOK);
        merged.setFileName(out.getFileName().toString());
        merged.setFilePath(out.toString());
        merged.setFormat("mp3");
        merged.setDurationMs(audioTool.durationMs(out));
        merged.setByteSize(sizeOf(out));
        merged.setStatus(AudioStatus.READY);
        return audioFileRepository.save(merged);
    }

    /**
     * 解析章節所屬群組的旁白覆寫設定。
     *
     * 同一本連載小說的各集共用同一個旁白音色與語氣，確保跨集聽感一致。
     * 章節不屬於任何群組時回傳 none()，沿用全域設定。
     */
    private TtsGroupBuilder.GroupContext resolveGroupContext(Chapter chapter) {
        if (chapter == null || chapter.getNovel() == null) {
            return TtsGroupBuilder.GroupContext.none();
        }
        Novel novel = chapter.getNovel();
        if (novel.getGroup() == null) {
            return TtsGroupBuilder.GroupContext.none();
        }
        String voiceId = null;
        String instructions = null;
        try {
            var narratorVoice = groupService.findGroupNarratorVoice(novel);
            if (narratorVoice != null) {
                voiceId = narratorVoice.getVoiceId();
            }
            instructions = groupService.findGroupNarratorInstructions(novel);
        } catch (Exception e) {
            // 群組設定讀取失敗不應阻擋合成 —— 退回全域旁白設定即可。
            log.warn("讀取群組 {} 的旁白設定失敗，改用全域設定", novel.getGroup().getId(), e);
        }
        return new TtsGroupBuilder.GroupContext(voiceId, instructions);
    }

    /** 單段重跑。 */
    @Transactional
    public AudioFile synthesizeSingleSegment(DialogueSegment segment) {
        List<TtsGroup> groups = groupBuilder.build(List.of(segment),
                resolveGroupContext(segment.getChapter()));
        if (groups.isEmpty()) {
            throw new IllegalStateException("無法為段落建立合成群組");
        }
        AiProviderConfig provider = aiSettingsService.requireProvider();
        AiModelSetting model = aiSettingsService.requireModel(ModelPurpose.TTS);
        return synthesizeGroup(provider, model, segment.getChapter(), groups.get(0), null);
    }

    private Long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return null;
        }
    }

    private String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "novel";
        }
        return name.replaceAll("[\\/:*?\"<>|\\s]+", "_");
    }

    /** 進度回呼介面。 */
    public interface ProgressListener {
        void onProgress(int progress, int total, String message);
    }
}
