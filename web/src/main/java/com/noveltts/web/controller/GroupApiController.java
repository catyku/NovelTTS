package com.noveltts.web.controller;

import com.noveltts.domain.entity.GroupCharacter;
import com.noveltts.domain.entity.NovelGroup;
import com.noveltts.domain.entity.TtsVoice;
import com.noveltts.domain.repository.GroupCharacterRepository;
import com.noveltts.domain.repository.NovelGroupRepository;
import com.noveltts.domain.repository.NovelRepository;
import com.noveltts.domain.repository.TtsVoiceRepository;
import com.noveltts.service.GroupService;
import com.noveltts.web.dto.ApiModels;
import com.noveltts.web.dto.Forms;
import com.noveltts.web.exception.NotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小說群組 API。
 *
 * 群組代表同一本連載小說 —— 分好幾十集慢慢出版。
 * 群組層級維護共用角色與旁白音色，各集分析時自動比對套用，
 * 讓同一角色與旁白在所有集之間音色一致。
 */
@RestController
@RequestMapping("/api/groups")
public class GroupApiController {

    private final NovelGroupRepository groupRepository;
    private final GroupCharacterRepository groupCharacterRepository;
    private final NovelRepository novelRepository;
    private final TtsVoiceRepository voiceRepository;
    private final GroupService groupService;

    public GroupApiController(NovelGroupRepository groupRepository,
                              GroupCharacterRepository groupCharacterRepository,
                              NovelRepository novelRepository,
                              TtsVoiceRepository voiceRepository,
                              GroupService groupService) {
        this.groupRepository = groupRepository;
        this.groupCharacterRepository = groupCharacterRepository;
        this.novelRepository = novelRepository;
        this.voiceRepository = voiceRepository;
        this.groupService = groupService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<ApiModels.GroupDto> list() {
        return groupRepository.findAllWithDetailsByOrderByIdDesc().stream().map(this::toDto).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ApiModels.GroupDto get(@PathVariable Long id) {
        return toDto(requireGroup(id));
    }

    @PostMapping
    @Transactional
    public ApiModels.GroupDto create(@RequestBody Forms.GroupForm form) {
        NovelGroup group = new NovelGroup();
        applyForm(group, form);
        groupRepository.save(group);
        return toDto(group);
    }

    @PutMapping("/{id}")
    @Transactional
    public ApiModels.GroupDto update(@PathVariable Long id, @RequestBody Forms.GroupForm form) {
        NovelGroup group = requireGroup(id);
        applyForm(group, form);
        groupRepository.save(group);
        return toDto(group);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        NovelGroup group = requireGroup(id);
        groupRepository.delete(group);
        return ResponseEntity.ok().build();
    }

    // ── 群組共用角色 ────────────────────────────────────────────────

    @GetMapping("/{id}/characters")
    @Transactional(readOnly = true)
    public List<ApiModels.GroupCharacterDto> listCharacters(@PathVariable Long id) {
        requireGroup(id);
        return groupCharacterRepository.findByGroupIdWithDetails(id).stream()
                .map(this::toDto).toList();
    }

    @PostMapping("/{id}/characters")
    @Transactional
    public ApiModels.GroupCharacterDto createCharacter(@PathVariable Long id,
                                                       @RequestBody Forms.GroupCharacterForm form) {
        NovelGroup group = requireGroup(id);
        GroupCharacter character = groupService.findOrCreateGroupCharacter(group, form.name());
        applyCharacterForm(character, form);
        groupCharacterRepository.save(character);
        return toDto(character);
    }

    @PutMapping("/{id}/characters/{characterId}")
    @Transactional
    public ApiModels.GroupCharacterDto updateCharacter(@PathVariable Long id,
                                                       @PathVariable Long characterId,
                                                       @RequestBody Forms.GroupCharacterForm form) {
        requireGroup(id);
        GroupCharacter character = groupCharacterRepository.findById(characterId)
                .orElseThrow(() -> new NotFoundException("找不到群組角色 " + characterId));
        applyCharacterForm(character, form);
        groupCharacterRepository.save(character);
        return toDto(character);
    }

    @DeleteMapping("/{id}/characters/{characterId}")
    public ResponseEntity<Void> deleteCharacter(@PathVariable Long id,
                                                 @PathVariable Long characterId) {
        requireGroup(id);
        groupCharacterRepository.findById(characterId)
                .orElseThrow(() -> new NotFoundException("找不到群組角色 " + characterId));
        groupCharacterRepository.deleteById(characterId);
        return ResponseEntity.ok().build();
    }

    // ── 內部 ───────────────────────────────────────────────────────

    private void applyForm(NovelGroup group, Forms.GroupForm form) {
        if (form.title() != null && !form.title().isBlank()) {
            group.setTitle(form.title().strip());
        }
        if (form.author() != null) {
            group.setAuthor(form.author());
        }
        if (form.description() != null) {
            group.setDescription(form.description());
        }
        if (form.narratorInstructions() != null) {
            group.setNarratorInstructions(form.narratorInstructions());
        }
        if (form.ttsGroupMaxChars() != null) {
            group.setTtsGroupMaxChars(form.ttsGroupMaxChars());
        }
        if (form.status() != null && !form.status().isBlank()) {
            group.setStatus(form.status());
        }
        // 旁白音色：null 表示清除設定
        if (form.narratorVoiceId() != null) {
            TtsVoice voice = voiceRepository.findById(form.narratorVoiceId())
                    .orElseThrow(() -> new NotFoundException("找不到音色 " + form.narratorVoiceId()));
            group.setNarratorVoice(voice);
        }
    }

    private void applyCharacterForm(GroupCharacter character, Forms.GroupCharacterForm form) {
        if (form.name() != null && !form.name().isBlank()) {
            character.setName(form.name().strip());
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
        if (form.ttsInstructions() != null) {
            character.setTtsInstructions(form.ttsInstructions());
        }
        if (form.colorHex() != null && !form.colorHex().isBlank()) {
            character.setColorHex(form.colorHex());
        }
        if (form.ttsVoiceId() != null) {
            TtsVoice voice = voiceRepository.findById(form.ttsVoiceId())
                    .orElseThrow(() -> new NotFoundException("找不到音色 " + form.ttsVoiceId()));
            character.setTtsVoice(voice);
        }
    }

    private NovelGroup requireGroup(Long id) {
        // fetch join narratorVoice：DTO 轉換會讀取音色，lazy proxy 在 session
        // 關閉後會拋 "Could not initialize proxy ... no session"。
        return groupRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new NotFoundException("找不到群組 " + id));
    }

    /**
     * 群組 DTO。
     *
     * narratorVoice 是 LAZY 關聯，呼叫端必須在 transaction 內使用，
     * 否則 session 已關閉會拋 "Could not initialize proxy ... no session"。
     */
    private ApiModels.GroupDto toDto(NovelGroup g) {
        List<ApiModels.GroupCharacterDto> chars = groupCharacterRepository
                .findByGroupIdWithDetails(g.getId()).stream().map(this::toDto).toList();
        int novelCount = (int) novelRepository.countByGroupId(g.getId());
        return new ApiModels.GroupDto(
                g.getId(), g.getTitle(), g.getAuthor(), g.getDescription(),
                g.getNarratorVoice() == null ? null : g.getNarratorVoice().getId(),
                g.getNarratorVoice() == null ? null : g.getNarratorVoice().getVoiceId(),
                g.getNarratorInstructions(), g.getTtsGroupMaxChars(), g.getStatus(),
                novelCount, chars.size(), g.getCreatedAt());
    }

    private ApiModels.GroupCharacterDto toDto(GroupCharacter c) {
        return new ApiModels.GroupCharacterDto(
                c.getId(), c.getGroup().getId(), c.getName(), c.getAliases(),
                c.getGender(), c.getAgeGroup(), c.getPersonality(), c.getDescription(),
                c.getTtsVoice() == null ? null : c.getTtsVoice().getId(),
                c.getTtsVoice() == null ? null : c.getTtsVoice().getVoiceId(),
                c.getTtsInstructions(), c.getColorHex());
    }
}
