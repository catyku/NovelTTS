package com.noveltts.web.controller;

import com.noveltts.web.exception.NotFoundException;
import com.noveltts.domain.entity.AudioFile;
import com.noveltts.domain.entity.Chapter;
import com.noveltts.domain.entity.DialogueSegment;
import com.noveltts.domain.entity.Novel;
import com.noveltts.domain.enums.AudioKind;
import com.noveltts.domain.enums.JobType;
import com.noveltts.domain.repository.AudioFileRepository;
import com.noveltts.domain.repository.ChapterRepository;
import com.noveltts.domain.repository.DialogueSegmentRepository;
import com.noveltts.domain.repository.NovelRepository;
import com.noveltts.service.ExportService;
import com.noveltts.service.JobService;
import com.noveltts.service.StorageService;
import com.noveltts.service.TtsService;
import com.noveltts.web.dto.ApiModels;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api")
public class AudioApiController {

    private final AudioFileRepository audioFileRepository;
    private final ChapterRepository chapterRepository;
    private final DialogueSegmentRepository segmentRepository;
    private final NovelRepository novelRepository;
    private final TtsService ttsService;
    private final ExportService exportService;
    private final JobService jobService;
    private final StorageService storageService;

    public AudioApiController(AudioFileRepository audioFileRepository,
                              ChapterRepository chapterRepository,
                              DialogueSegmentRepository segmentRepository,
                              NovelRepository novelRepository,
                              TtsService ttsService,
                              ExportService exportService,
                              JobService jobService,
                              StorageService storageService) {
        this.audioFileRepository = audioFileRepository;
        this.chapterRepository = chapterRepository;
        this.segmentRepository = segmentRepository;
        this.novelRepository = novelRepository;
        this.ttsService = ttsService;
        this.exportService = exportService;
        this.jobService = jobService;
        this.storageService = storageService;
    }

    @GetMapping("/novels/{id}/audios")
    public List<ApiModels.AudioDto> listNovelAudios(@PathVariable Long id) {
        return audioFileRepository.findByNovelIdWithDetails(id).stream()
                .map(this::toDto)
                .toList();
    }

    @GetMapping("/chapters/{id}/audios")
    public List<ApiModels.AudioDto> listChapterAudios(@PathVariable Long id) {
        return audioFileRepository.findByChapterIdOrderByIdAsc(id).stream()
                .map(this::toDto)
                .toList();
    }

    @GetMapping("/audio/{id}/file")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        AudioFile audio = audioFileRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到音檔 " + id));
        Path path = Path.of(audio.getFilePath());
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("音檔檔案不存在: " + path);
        }
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/" + audio.getFormat()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        com.noveltts.web.ContentDisposition.inline(audio.getFileName()))
                .body(resource);
    }

    @DeleteMapping("/audio/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        AudioFile audio = audioFileRepository.findById(id).orElse(null);
        if (audio != null) {
            storageService.deleteFile(Path.of(audio.getFilePath()));
            audioFileRepository.delete(audio);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 單段重跑合成。
     * 以非同步 job 執行：TTS 呼叫可能需數十秒，同步會佔住請求執行緒並讓瀏覽器等待。
     */
    @PostMapping("/segments/{id}/synthesize")
    public ResponseEntity<ApiModels.JobDto> synthesizeSegment(@PathVariable Long id) {
        DialogueSegment segment = segmentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到段落 " + id));
        Long segmentId = segment.getId();
        var job = jobService.create(JobType.SYNTHESIZE, segmentId, 1);
        jobService.execute(job.getId(), consumer -> {
            DialogueSegment s = segmentRepository.findById(segmentId)
                    .orElseThrow(() -> new NotFoundException("找不到段落 " + segmentId));
            ttsService.synthesizeSingleSegment(s);
            consumer.accept(1, 1, "合成完成");
        });
        return ResponseEntity.ok(new ApiModels.JobDto(job.getId(), job.getType().name(),
                job.getRefId(), job.getStatus().name(), job.getProgress(), job.getTotal(),
                job.getMessage(), job.getErrorMessage()));
    }

    /**
     * 合成整章段落。以非同步 job 執行：TTS 呼叫可能需數十秒。
     */
    @PostMapping("/chapters/{id}/synthesize")
    public ResponseEntity<ApiModels.JobDto> synthesizeChapter(@PathVariable Long id,
                                                              @RequestParam(defaultValue = "true") boolean onlyPending) {
        Chapter chapter = chapterRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到章節 " + id));
        Long chapterId = chapter.getId();
        var job = jobService.create(JobType.SYNTHESIZE, chapterId, 1);
        jobService.execute(job.getId(), consumer -> {
            Chapter c = chapterRepository.findById(chapterId)
                    .orElseThrow(() -> new NotFoundException("找不到章節 " + chapterId));
            int n = ttsService.synthesizeChapter(c, null, onlyPending);
            consumer.accept(1, 1, "合成完成：" + n + " 組");
        });
        return ResponseEntity.ok(new ApiModels.JobDto(job.getId(), job.getType().name(),
                job.getRefId(), job.getStatus().name(), job.getProgress(), job.getTotal(),
                job.getMessage(), job.getErrorMessage()));
    }

    @PostMapping("/chapters/{id}/merge")
    @Transactional
    public ApiModels.AudioDto mergeChapter(@PathVariable Long id) {
        Chapter chapter = chapterRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到章節 " + id));
        return toDto(ttsService.mergeChapter(chapter));
    }

    @PostMapping("/novels/{id}/merge")
    @Transactional
    public ApiModels.AudioDto mergeNovel(@PathVariable Long id) {
        Novel novel = novelRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到小說 " + id));
        return toDto(ttsService.mergeNovel(novel));
    }

    @GetMapping("/novels/{id}/export")
    public ResponseEntity<Resource> export(@PathVariable Long id) {
        Novel novel = novelRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到小說 " + id));
        Path target = storageService.novelDir(id).resolve(
                (novel.getTitle() == null ? "novel" : novel.getTitle()
                        .replaceAll("[\\/:*?\"<>|\\s]+", "_")) + ".zip");
        exportService.exportNovel(novel, target);
        Resource resource = new FileSystemResource(target);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        com.noveltts.web.ContentDisposition.attachment(target.getFileName().toString()))
                .body(resource);
    }

    private ApiModels.AudioDto toDto(AudioFile a) {
        return new ApiModels.AudioDto(a.getId(), a.getKind().name(),
                a.getChapter() == null ? null : a.getChapter().getId(),
                a.getSegment() == null ? null : a.getSegment().getId(),
                a.getFileName(), a.getFormat(), a.getDurationMs(), a.getByteSize(),
                a.getProvider(), a.getModel(), a.getVoiceId(), a.getGenerationId(),
                "/api/audio/" + a.getId() + "/file");
    }

    private ApiModels.SegmentDto toSegment(DialogueSegment s) {
        return new ApiModels.SegmentDto(s.getId(), s.getChapter().getId(), s.getSeq(),
                s.getType().name(), s.getCharacter() == null ? null : s.getCharacter().getId(),
                s.getSpeakerName(), s.getText(), s.getEmotion(), s.getStyleHint(),
                s.getInstructions(), s.getVoiceId(), s.getTtsStatus().name(),
                s.getAudio() == null ? null : s.getAudio().getId(),
                s.getAudio() == null ? null : s.getAudio().getDurationMs(),
                s.getAudio() == null ? null : "/api/audio/" + s.getAudio().getId() + "/file");
    }
}
