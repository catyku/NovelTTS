package com.noveltts.service;

import com.noveltts.domain.entity.GroupCharacter;
import com.noveltts.domain.entity.Novel;
import com.noveltts.domain.entity.NovelCharacter;
import com.noveltts.domain.entity.NovelGroup;
import com.noveltts.domain.entity.TtsVoice;
import com.noveltts.domain.repository.GroupCharacterRepository;
import com.noveltts.domain.repository.NovelCharacterRepository;
import com.noveltts.domain.repository.NovelGroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 小說群組服務：管理跨集共用的角色設定。
 *
 * 使用情境是同一本小說分好幾十集慢慢出版 —— 群組維護共用角色與旁白音色，
 * 各集分析時自動比對並套用，確保跨集音色一致。
 *
 * 比對策略：以角色名為主鍵，另比對別名（aliases）。
 * 命中時把群組角色的音色與語氣覆寫到該集角色上，並保留該集自己的
 * 性別/年齡/描述等「本集特有」資訊。
 */
@Service
public class GroupService {

    private final NovelGroupRepository groupRepository;
    private final GroupCharacterRepository groupCharacterRepository;
    private final NovelCharacterRepository novelCharacterRepository;

    public GroupService(NovelGroupRepository groupRepository,
                        GroupCharacterRepository groupCharacterRepository,
                        NovelCharacterRepository novelCharacterRepository) {
        this.groupRepository = groupRepository;
        this.groupCharacterRepository = groupCharacterRepository;
        this.novelCharacterRepository = novelCharacterRepository;
    }

    /**
     * 找出此小說所屬群組中，與指定角色名對應的共用角色。
     *
     * 先比對名稱完全相同，再比對別名。回傳 null 代表沒有對應的共用角色，
     * 該集角色維持自己的設定。
     */
    @Transactional(readOnly = true)
    public Optional<GroupCharacter> findMatchingGroupCharacter(Novel novel, String characterName) {
        if (novel == null || novel.getGroup() == null || characterName == null) {
            return Optional.empty();
        }
        Long groupId = novel.getGroup().getId();
        if (groupId == null) {
            return Optional.empty();
        }

        // 1. 名稱完全相同
        Optional<GroupCharacter> byName = groupCharacterRepository.findByGroupIdAndName(groupId, characterName);
        if (byName.isPresent()) {
            return byName;
        }

        // 2. 別名比對。別名比名稱更容易不一致（例如多出空白或不同簡體字），
        //    因此用 trim 後的完全比對，不做模糊比對以免誤配。
        List<GroupCharacter> all = groupCharacterRepository.findByGroupIdWithDetails(groupId);
        String target = characterName.trim();
        for (GroupCharacter gc : all) {
            if (gc.getAliases() == null) {
                continue;
            }
            for (String alias : gc.getAliases()) {
                if (alias != null && alias.trim().equals(target)) {
                    return Optional.of(gc);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 把群組共用角色的音色與語氣套用到該集角色。
     *
     * 只覆寫「跨集應該一致」的欄位（音色、語氣、色碼），
     * 性別/年齡/個性/描述等該集特有的資訊保持不動。
     *
     * @return true 表示有套用群組設定
     */
    @Transactional
    public boolean applyGroupCharacter(Novel novel, NovelCharacter character, String characterName) {
        Optional<GroupCharacter> matched = findMatchingGroupCharacter(novel, characterName);
        if (matched.isEmpty()) {
            return false;
        }
        GroupCharacter gc = matched.get();

        // 群組角色必須有指定音色才覆寫 —— 否則保留該集原本的音色設定。
        if (gc.getTtsVoice() != null) {
            character.setTtsVoice(gc.getTtsVoice());
        }
        if (gc.getTtsInstructions() != null && !gc.getTtsInstructions().isBlank()) {
            character.setTtsInstructions(gc.getTtsInstructions());
        }
        if (gc.getColorHex() != null && !gc.getColorHex().isBlank()) {
            character.setColorHex(gc.getColorHex());
        }
        novelCharacterRepository.save(character);
        return true;
    }

    /**
     * 建立或取得群組共用角色。
     */
    @Transactional
    public GroupCharacter findOrCreateGroupCharacter(NovelGroup group, String name) {
        return groupCharacterRepository.findByGroupIdAndName(group.getId(), name)
                .orElseGet(() -> {
                    GroupCharacter c = new GroupCharacter();
                    c.setGroup(group);
                    c.setName(name);
                    c.setAliases(new String[0]);
                    c.setColorHex("#4C6EF5");
                    return groupCharacterRepository.save(c);
                });
    }

    /**
     * 群組共用角色的比對索引：名稱與別名都映射到同一個共用角色。
     *
     * 供批次套用時一次載入，避免每個角色都打一次資料庫。
     */
    @Transactional(readOnly = true)
    public Map<String, GroupCharacter> buildMatchIndex(Long groupId) {
        Map<String, GroupCharacter> index = new LinkedHashMap<>();
        if (groupId == null) {
            return index;
        }
        for (GroupCharacter gc : groupCharacterRepository.findByGroupIdWithDetails(groupId)) {
            index.put(gc.getName().trim(), gc);
            if (gc.getAliases() != null) {
                for (String alias : gc.getAliases()) {
                    if (alias != null && !alias.isBlank()) {
                        index.put(alias.trim(), gc);
                    }
                }
            }
        }
        return index;
    }

    /** 群組的共用旁白音色；群組未設定時回傳 null。 */
    @Transactional(readOnly = true)
    public TtsVoice findGroupNarratorVoice(Novel novel) {
        if (novel == null || novel.getGroup() == null) {
            return null;
        }
        NovelGroup group = novel.getGroup();
        // narratorVoice 是 LAZY，重新載入群組以取得。
        NovelGroup loaded = groupRepository.findByIdWithDetails(group.getId()).orElse(null);
        return loaded == null ? null : loaded.getNarratorVoice();
    }

    /** 群組的共用旁白語氣；未設定時回傳 null。 */
    @Transactional(readOnly = true)
    public String findGroupNarratorInstructions(Novel novel) {
        if (novel == null || novel.getGroup() == null) {
            return null;
        }
        NovelGroup loaded = groupRepository
                .findByIdWithDetails(novel.getGroup().getId()).orElse(null);
        return loaded == null ? null : loaded.getNarratorInstructions();
    }
}
