package com.noveltts.web.controller;

import com.noveltts.domain.entity.Chapter;
import com.noveltts.domain.entity.DialogueSegment;
import com.noveltts.domain.entity.Novel;
import com.noveltts.domain.repository.AudioFileRepository;
import com.noveltts.domain.repository.ChapterRepository;
import com.noveltts.domain.repository.DialogueSegmentRepository;
import com.noveltts.domain.repository.JobTaskRepository;
import com.noveltts.domain.repository.NovelCharacterRepository;
import com.noveltts.domain.repository.NovelRepository;
import com.noveltts.domain.repository.TtsVoiceRepository;
import com.noveltts.web.dto.ApiModels;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Controller
public class PageController {

    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;
    private final DialogueSegmentRepository segmentRepository;
    private final NovelCharacterRepository characterRepository;
    private final TtsVoiceRepository voiceRepository;
    private final AudioFileRepository audioFileRepository;
    private final JobTaskRepository jobRepository;

    public PageController(NovelRepository novelRepository,
                          ChapterRepository chapterRepository,
                          DialogueSegmentRepository segmentRepository,
                          NovelCharacterRepository characterRepository,
                          TtsVoiceRepository voiceRepository,
                          AudioFileRepository audioFileRepository,
                          JobTaskRepository jobRepository) {
        this.novelRepository = novelRepository;
        this.chapterRepository = chapterRepository;
        this.segmentRepository = segmentRepository;
        this.characterRepository = characterRepository;
        this.voiceRepository = voiceRepository;
        this.audioFileRepository = audioFileRepository;
        this.jobRepository = jobRepository;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("novels", novelRepository.findAllByOrderByIdDesc());
        model.addAttribute("jobs", jobRepository.findTop20ByOrderByIdDesc());
        model.addAttribute("voiceCount", voiceRepository.count());
        model.addAttribute("audioCount", audioFileRepository.count());
        return "dashboard";
    }

    @GetMapping("/novels")
    public String novels(Model model) {
        model.addAttribute("novels", novelRepository.findAllByOrderByIdDesc());
        return "novels";
    }

    @GetMapping("/novels/{id}")
    public String novelDetail(@PathVariable Long id, Model model) {
        Novel novel = requireNovel(id);
        List<Chapter> chapters = chapterRepository.findByNovelIdWithDetails(id);
        model.addAttribute("novel", novel);
        model.addAttribute("chapters", chapters);
        model.addAttribute("audios", audioFileRepository.findByNovelIdWithDetails(id));
        return "novel-detail";
    }

    @GetMapping("/novels/{id}/characters")
    public String characters(@PathVariable Long id, Model model) {
        Novel novel = requireNovel(id);
        model.addAttribute("novel", novel);
        model.addAttribute("characters", characterRepository.findByNovelIdWithDetails(id));
        model.addAttribute("voices", voiceRepository.findAllByOrderBySortOrderAscDisplayNameAsc());
        return "characters";
    }

    @GetMapping("/novels/{id}/segments")
    public String segments(@PathVariable Long id,
                           @RequestParam(required = false) Long chapterId,
                           Model model) {
        Novel novel = requireNovel(id);
        List<Chapter> chapters = chapterRepository.findByNovelIdWithDetails(id);
        Long target = chapterId;
        if (target == null && !chapters.isEmpty()) {
            target = chapters.get(0).getId();
        }
        model.addAttribute("novel", novel);
        model.addAttribute("chapters", chapters);
        model.addAttribute("selectedChapterId", target);
        model.addAttribute("segments", target == null
                ? List.of()
                : segmentRepository.findByChapterIdWithDetails(target).stream()
                        .map(this::toSegmentDto)
                        .toList());
        model.addAttribute("voices", voiceRepository.findAllByOrderBySortOrderAscDisplayNameAsc());
        return "segments";
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("voices", voiceRepository.findAllByOrderBySortOrderAscDisplayNameAsc());
        return "settings";
    }

    /**
     * 取得小說，不存在時回 404。
     * 原本用 orElseThrow() 會拋 NoSuchElementException 變成 500，
     * 對使用者而言「找不到」應是 404 而非伺服器錯誤。
     */
    private Novel requireNovel(Long id) {
        return novelRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "找不到小說 " + id));
    }

    /**
     * 段落轉為 DTO：模板需要 audioUrl 這類衍生欄位，entity 上沒有。
     */
    private ApiModels.SegmentDto toSegmentDto(DialogueSegment s) {
        return new ApiModels.SegmentDto(s.getId(), s.getChapter().getId(), s.getSeq(),
                s.getType().name(), s.getCharacter() == null ? null : s.getCharacter().getId(),
                s.getSpeakerName(), s.getText(), s.getEmotion(), s.getStyleHint(),
                s.getInstructions(), s.getVoiceId(), s.getTtsStatus().name(),
                s.getAudio() == null ? null : s.getAudio().getId(),
                s.getAudio() == null ? null : s.getAudio().getDurationMs(),
                s.getAudio() == null ? null : "/api/audio/" + s.getAudio().getId() + "/file");
    }
}
