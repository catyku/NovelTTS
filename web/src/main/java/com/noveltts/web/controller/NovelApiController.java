package com.noveltts.web.controller;

import com.noveltts.web.exception.NotFoundException;
import com.noveltts.domain.entity.Chapter;
import com.noveltts.domain.entity.DialogueSegment;
import com.noveltts.domain.entity.Novel;
import com.noveltts.domain.entity.NovelCharacter;
import com.noveltts.domain.enums.JobType;
import com.noveltts.domain.repository.ChapterRepository;
import com.noveltts.domain.repository.DialogueSegmentRepository;
import com.noveltts.domain.repository.NovelCharacterRepository;
import com.noveltts.domain.repository.NovelRepository;
import com.noveltts.service.AnalysisService;
import com.noveltts.service.JobService;
import com.noveltts.service.NovelService;
import com.noveltts.service.VoiceSuggestionService;
import com.noveltts.web.dto.ApiModels;
import com.noveltts.web.dto.Forms;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class NovelApiController {

    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;
    private final DialogueSegmentRepository segmentRepository;
    private final NovelCharacterRepository characterRepository;
    private final com.noveltts.domain.repository.TtsVoiceRepository ttsVoiceRepository;
    private final NovelService novelService;
    private final AnalysisService analysisService;
    private final JobService jobService;
    private final VoiceSuggestionService voiceSuggestionService;

    public NovelApiController(NovelRepository novelRepository,
                              ChapterRepository chapterRepository,
                              DialogueSegmentRepository segmentRepository,
                              NovelCharacterRepository characterRepository,
                              com.noveltts.domain.repository.TtsVoiceRepository ttsVoiceRepository,
                              NovelService novelService,
                              AnalysisService analysisService,
                              JobService jobService,
                              VoiceSuggestionService voiceSuggestionService) {
        this.novelRepository = novelRepository;
        this.chapterRepository = chapterRepository;
        this.segmentRepository = segmentRepository;
        this.characterRepository = characterRepository;
        this.ttsVoiceRepository = ttsVoiceRepository;
        this.novelService = novelService;
        this.analysisService = analysisService;
        this.jobService = jobService;
        this.voiceSuggestionService = voiceSuggestionService;
    }

    // ---------- novel ----------

    @GetMapping("/novels")
    public List<ApiModels.NovelDto> listNovels(@RequestParam(required = false) String keyword) {
        List<Novel> novels = (keyword == null || keyword.isBlank())
                ? novelRepository.findAllWithGroupByOrderByIdDesc()
                : novelRepository.findByTitleContainingIgnoreCaseWithGroup(keyword);
        return novels.stream().map(this::toDto).toList();
    }

    @GetMapping("/novels/{id}")
    public ApiModels.NovelDto getNovel(@PathVariable Long id) {
        return toDto(requireNovel(id));
    }

    @PostMapping("/novels")
    public ResponseEntity<ApiModels.NovelDto> createNovel(@RequestBody Forms.NovelForm form) {
        Novel novel = novelService.create(form.title(), form.author(), form.content(), null,
                form.groupId(), form.groupOrder());
        return ResponseEntity.ok(toDto(novel));
    }

    @PutMapping("/novels/{id}")
    @Transactional
    public ApiModels.NovelDto updateNovel(@PathVariable Long id, @RequestBody Forms.NovelForm form) {
        Novel novel = requireNovel(id);
        if (form.title() != null && !form.title().isBlank()) {
            novel.setTitle(form.title());
        }
        if (form.author() != null) {
            novel.setAuthor(form.author());
        }
        if (form.content() != null) {
            novel.setSourceText(form.content());
            novel.setTotalChars(form.content().length());
            novelService.splitChapters(novel);
        }
        return toDto(novelRepository.save(novel));
    }

    @DeleteMapping("/novels/{id}")
    public ResponseEntity<Void> deleteNovel(@PathVariable Long id) {
        novelService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---------- chapter ----------

    @GetMapping("/novels/{id}/chapters")
    public List<ApiModels.ChapterDto> listChapters(@PathVariable Long id) {
        return chapterRepository.findByNovelIdWithDetails(id).stream()
                .map(this::toDto)
                .toList();
    }

    @PostMapping("/novels/{id}/chapters/split")
    public List<ApiModels.ChapterDto> splitChapters(@PathVariable Long id,
                                                    @RequestBody(required = false) Map<String, Integer> body) {
        Novel novel = requireNovel(id);
        List<Chapter> chapters = novelService.splitChapters(novel);
        return chapters.stream().map(this::toDto).toList();
    }

    @PutMapping("/chapters/{id}")
    @Transactional
    public ApiModels.ChapterDto updateChapter(@PathVariable Long id, @RequestBody Forms.ChapterForm form) {
        Chapter chapter = requireChapter(id);
        if (form.title() != null) {
            chapter.setTitle(form.title());
        }
        if (form.content() != null) {
            chapter.setContent(form.content());
            chapter.setCharCount(form.content().length());
        }
        return toDto(chapterRepository.save(chapter));
    }

    @DeleteMapping("/chapters/{id}")
    public ResponseEntity<Void> deleteChapter(@PathVariable Long id) {
        chapterRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ---------- segment ----------

    @GetMapping("/chapters/{id}/segments")
    public List<ApiModels.SegmentDto> listSegments(@PathVariable Long id) {
        return segmentRepository.findByChapterIdOrderBySeq(id).stream()
                .map(this::toDto)
                .toList();
    }

    @GetMapping("/novels/{id}/segments")
    public List<ApiModels.SegmentDto> listNovelSegments(@PathVariable Long id) {
        return segmentRepository.findByNovelIdWithDetails(id).stream()
                .map(this::toDto)
                .toList();
    }

    @PutMapping("/segments/{id}")
    @Transactional
    public ApiModels.SegmentDto updateSegment(@PathVariable Long id, @RequestBody Forms.SegmentForm form) {
        DialogueSegment segment = segmentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到段落 " + id));
        if (form.text() != null && !form.text().isBlank()) {
            segment.setText(form.text());
            segment.setCharCount(form.text().length());
        }
        if (form.characterId() != null) {
            NovelCharacter character = characterRepository.findById(form.characterId()).orElse(null);
            segment.setCharacter(character);
            if (character != null) {
                segment.setSpeakerName(character.getName());
            }
        }
        if (form.voiceId() != null) {
            segment.setVoiceId(form.voiceId().isBlank() ? null : form.voiceId());
        }
        if (form.instructions() != null) {
            segment.setInstructions(form.instructions());
        }
        if (form.emotion() != null) {
            segment.setEmotion(form.emotion());
        }
        if (form.styleHint() != null) {
            segment.setStyleHint(form.styleHint());
        }
        return toDto(segmentRepository.save(segment));
    }

    @DeleteMapping("/segments/{id}")
    public ResponseEntity<Void> deleteSegment(@PathVariable Long id) {
        segmentRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ---------- character ----------

    @GetMapping("/novels/{id}/characters")
    public List<ApiModels.CharacterDto> listCharacters(@PathVariable Long id) {
        return characterRepository.findByNovelIdWithDetails(id).stream()
                .map(this::toDto)
                .toList();
    }

    @PutMapping("/characters/{id}")
    @Transactional
    public ApiModels.CharacterDto updateCharacter(@PathVariable Long id,
                                                  @RequestBody Forms.CharacterForm form) {
        NovelCharacter character = characterRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到角色 " + id));
        if (form.name() != null && !form.name().isBlank()) {
            character.setName(form.name());
        }
        if (form.aliases() != null) {
            character.setAliases(form.aliases());
        }
        if (form.gender() != null) {
            character.setGender(form.gender());
        }
        if (form.ageGroup() != null) {
            character.setAgeGroup(form.ageGroup());
        }
        if (form.personality() != null) {
            character.setPersonality(form.personality());
        }
        if (form.description() != null) {
            character.setDescription(form.description());
        }
        if (form.ttsVoiceId() != null) {
            character.setTtsVoice(form.ttsVoiceId() == 0
                    ? null
                    : ttsVoiceRepository.findById(form.ttsVoiceId()).orElse(null));
        }
        if (form.ttsInstructions() != null) {
            character.setTtsInstructions(form.ttsInstructions());
        }
        if (form.colorHex() != null) {
            character.setColorHex(form.colorHex());
        }
        return toDto(characterRepository.save(character));
    }

    /** 自動指派音色。 */
    @PostMapping("/novels/{id}/characters/auto-voice")
    @Transactional
    public ResponseEntity<ApiModels.CountResult> autoVoice(@PathVariable Long id,
                                                           @RequestParam(defaultValue = "false") boolean overwrite) {
        int assigned = 0;
        for (NovelCharacter character : characterRepository.findByNovelIdOrderByIdAsc(id)) {
            if (!overwrite && character.getTtsVoice() != null) {
                continue;
            }
            var voice = voiceSuggestionService.suggest(character);
            if (voice != null) {
                character.setTtsVoice(voice);
                characterRepository.save(character);
                assigned++;
            }
        }
        return ResponseEntity.ok(new ApiModels.CountResult(assigned));
    }

    // ---------- job ----------

    @PostMapping("/novels/{id}/analyze")
    public ResponseEntity<ApiModels.JobDto> analyze(@PathVariable Long id,
                                                    @RequestParam(required = false) List<Long> chapterIds) {
        Novel novel = requireNovel(id);
        int total = chapterIds == null || chapterIds.isEmpty()
                ? chapterRepository.findByNovelIdOrderByChapterIndex(id).size()
                : chapterIds.size();
        var job = jobService.create(JobType.ANALYZE, id, total);
        jobService.execute(job.getId(), consumer ->
                analysisService.analyzeNovel(id, chapterIds,
                        (p, t, m) -> consumer.accept(p, t, m)));
        return ResponseEntity.ok(toDto(job));
    }

    // ---------- helpers ----------

    private Novel requireNovel(Long id) {
        // fetch join 群組：DTO 轉換會讀取 group.title，lazy proxy 在 session
        // 關閉後會拋 "Could not initialize proxy ... no session"。
        return novelRepository.findByIdWithGroup(id)
                .orElseThrow(() -> new NotFoundException("找不到小說 " + id));
    }

    private Chapter requireChapter(Long id) {
        return chapterRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到章節 " + id));
    }

    private ApiModels.JobDto toDto(com.noveltts.domain.entity.JobTask j) {
        return new ApiModels.JobDto(j.getId(), j.getType().name(), j.getRefId(),
                j.getStatus().name(), j.getProgress(), j.getTotal(), j.getMessage(),
                j.getErrorMessage());
    }

    private ApiModels.NovelDto toDto(Novel n) {
        // group 是 LAZY，在 session 開啟時取；離開 session 後不可再讀取。
        Long groupId = n.getGroup() == null ? null : n.getGroup().getId();
        String groupTitle = n.getGroup() == null ? null : n.getGroup().getTitle();
        return new ApiModels.NovelDto(n.getId(), n.getTitle(), n.getAuthor(), n.getStatus().name(),
                n.getTotalChapters(), n.getTotalChars(), n.getCreatedAt(),
                groupId, groupTitle, n.getGroupOrder());
    }

    private ApiModels.ChapterDto toDto(Chapter c) {
        int segmentCount = segmentRepository.findByChapterIdOrderBySeq(c.getId()).size();
        return new ApiModels.ChapterDto(c.getId(), c.getNovel().getId(), c.getChapterIndex(),
                c.getTitle(), c.getCharCount(), c.getStatus().name(), segmentCount);
    }

    private ApiModels.CharacterDto toDto(NovelCharacter c) {
        return new ApiModels.CharacterDto(c.getId(), c.getNovel().getId(), c.getName(), c.getAliases(),
                c.getGender(), c.getAgeGroup(), c.getPersonality(), c.getDescription(),
                c.getTtsVoice() == null ? null : c.getTtsVoice().getId(),
                c.getTtsVoice() == null ? null : c.getTtsVoice().getVoiceId(),
                c.getTtsInstructions(), c.getColorHex(), c.getSegmentCount());
    }

    private ApiModels.SegmentDto toDto(DialogueSegment s) {
        return new ApiModels.SegmentDto(s.getId(), s.getChapter().getId(), s.getSeq(),
                s.getType().name(), s.getCharacter() == null ? null : s.getCharacter().getId(),
                s.getSpeakerName(), s.getText(), s.getEmotion(), s.getStyleHint(),
                s.getInstructions(), s.getVoiceId(), s.getTtsStatus().name(),
                s.getAudio() == null ? null : s.getAudio().getId(),
                s.getAudio() == null ? null : s.getAudio().getDurationMs(),
                s.getAudio() == null ? null : "/api/audio/" + s.getAudio().getId() + "/file");
    }
}
