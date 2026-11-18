package com.noveltts.web.controller;

import com.noveltts.domain.entity.Chapter;
import com.noveltts.domain.entity.DialogueSegment;
import com.noveltts.domain.entity.Novel;
import com.noveltts.domain.repository.AudioFileRepository;
import com.noveltts.domain.repository.ChapterRepository;
import com.noveltts.domain.repository.DialogueSegmentRepository;
import com.noveltts.domain.repository.JobTaskRepository;
import com.noveltts.domain.repository.NovelCharacterRepository;
import com.noveltts.domain.repository.NovelGroupRepository;
import com.noveltts.domain.repository.NovelRepository;
import com.noveltts.domain.repository.TtsVoiceRepository;
import com.noveltts.web.dto.ApiModels;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 頁面路由。
 *
 * <p>訪客模式（未登入）下，頁面只是空殼：資料由前端的 IndexedDB 與閘道 API 提供。
 * 這裡不查詢資料庫，避免把帳號模式下的資料渲染給訪客看。
 *
 * <p>登入後（帳號模式）則沿用原本的 server 端渲染，兩種模式並存。
 */
@Controller
public class PageController {

    private final NovelRepository novelRepository;
    private final NovelGroupRepository groupRepository;
    private final ChapterRepository chapterRepository;
    private final DialogueSegmentRepository segmentRepository;
    private final NovelCharacterRepository characterRepository;
    private final TtsVoiceRepository voiceRepository;
    private final AudioFileRepository audioFileRepository;
    private final JobTaskRepository jobRepository;

    public PageController(NovelRepository novelRepository,
                          NovelGroupRepository groupRepository,
                          ChapterRepository chapterRepository,
                          DialogueSegmentRepository segmentRepository,
                          NovelCharacterRepository characterRepository,
                          TtsVoiceRepository voiceRepository,
                          AudioFileRepository audioFileRepository,
                          JobTaskRepository jobRepository) {
        this.novelRepository = novelRepository;
        this.groupRepository = groupRepository;
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
        if (isGuest()) {
            // 訪客模式：統計數字由前端從 IndexedDB 計算，server 不提供。
            model.addAttribute("guestMode", true);
            model.addAttribute("novels", List.of());
            model.addAttribute("jobs", List.of());
            model.addAttribute("voiceCount", 0);
            model.addAttribute("audioCount", 0);
            return "dashboard";
        }
        model.addAttribute("guestMode", false);
        model.addAttribute("novels", novelRepository.findAllWithGroupByOrderByIdDesc());
        model.addAttribute("jobs", jobRepository.findTop20ByOrderByIdDesc());
        model.addAttribute("voiceCount", voiceRepository.count());
        model.addAttribute("audioCount", audioFileRepository.count());
        return "dashboard";
    }

    @GetMapping("/novels")
    public String novels(Model model) {
        if (isGuest()) {
            model.addAttribute("guestMode", true);
            model.addAttribute("novels", List.of());
            model.addAttribute("groups", List.of());
            return "novels";
        }
        model.addAttribute("guestMode", false);
        model.addAttribute("novels", novelRepository.findAllWithGroupByOrderByIdDesc());
        model.addAttribute("groups", groupRepository.findAllWithDetailsByOrderByIdDesc());
        return "novels";
    }

    /** 小說群組：同一本連載小說的共用角色與旁白音色設定。 */
    @GetMapping("/groups")
    public String groups(Model model) {
        if (isGuest()) {
            model.addAttribute("guestMode", true);
            model.addAttribute("groups", List.of());
            return "groups";
        }
        model.addAttribute("guestMode", false);
        model.addAttribute("groups", groupRepository.findAllWithDetailsByOrderByIdDesc());
        return "groups";
    }

    @GetMapping("/novels/{id}")
    public String novelDetail(@PathVariable String id, Model model) {
        if (isGuest()) {
            // 訪客的小說 id 是瀏覽器生成的字串，不是資料庫主鍵。
            model.addAttribute("guestMode", true);
            model.addAttribute("novel", null);
            model.addAttribute("chapters", List.of());
            model.addAttribute("audios", List.of());
            return "novel-detail";
        }
        Long novelId = parseId(id);
        Novel novel = requireNovel(novelId);
        List<Chapter> chapters = chapterRepository.findByNovelIdWithDetails(novelId);
        model.addAttribute("guestMode", false);
        model.addAttribute("novel", novel);
        model.addAttribute("chapters", chapters);
        model.addAttribute("audios", audioFileRepository.findByNovelIdWithDetails(novelId));
        return "novel-detail";
    }

    @GetMapping("/novels/{id}/characters")
    public String characters(@PathVariable String id, Model model) {
        model.addAttribute("voices", voiceRepository.findAllByOrderBySortOrderAscDisplayNameAsc());
        if (isGuest()) {
            model.addAttribute("guestMode", true);
            model.addAttribute("novel", null);
            model.addAttribute("characters", List.of());
            return "characters";
        }
        Long novelId = parseId(id);
        Novel novel = requireNovel(novelId);
        model.addAttribute("guestMode", false);
        model.addAttribute("novel", novel);
        model.addAttribute("characters", characterRepository.findByNovelIdWithDetails(novelId));
        return "characters";
    }

    @GetMapping("/novels/{id}/segments")
    public String segments(@PathVariable String id,
                           @RequestParam(required = false) String chapterId,
                           Model model) {
        model.addAttribute("voices", voiceRepository.findAllByOrderBySortOrderAscDisplayNameAsc());
        if (isGuest()) {
            model.addAttribute("guestMode", true);
            model.addAttribute("novel", null);
            model.addAttribute("chapters", List.of());
            model.addAttribute("selectedChapterId", null);
            model.addAttribute("segments", List.of());
            return "segments";
        }
        Long novelId = parseId(id);
        Novel novel = requireNovel(novelId);
        List<Chapter> chapters = chapterRepository.findByNovelIdWithDetails(novelId);
        Long target = chapterId == null ? null : parseId(chapterId);
        if (target == null && !chapters.isEmpty()) {
            target = chapters.get(0).getId();
        }
        model.addAttribute("guestMode", false);
        model.addAttribute("novel", novel);
        model.addAttribute("chapters", chapters);
        model.addAttribute("selectedChapterId", target);
        model.addAttribute("segments", target == null
                ? List.of()
                : segmentRepository.findByChapterIdWithDetails(target).stream()
                        .map(this::toSegmentDto)
                        .toList());
        return "segments";
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("voices", voiceRepository.findAllByOrderBySortOrderAscDisplayNameAsc());
        model.addAttribute("guestMode", isGuest());
        return "settings";
    }

    /**
     * 是否為訪客（未登入）。
     *
     * <p>訪客模式的資料在瀏覽器，server 端不該查詢資料庫 —— 那會把
     * 帳號模式下的資料渲染出來。
     */
    private boolean isGuest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal());
    }

    /** 解析數字 id；訪客模式用的字串 id 在此不會走到。 */
    private Long parseId(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到資源 " + raw);
        }
    }

    /**
     * 取得小說，不存在時回 404。
     * 原本用 orElseThrow() 會拋 NoSuchElementException 變成 500，
     * 對使用者而言「找不到」應是 404 而非伺服器錯誤。
     */
    private Novel requireNovel(Long id) {
        // fetch join 群組：頁面可能顯示所屬群組，lazy proxy 在 session 關閉後失效。
        return novelRepository.findByIdWithGroup(id)
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
