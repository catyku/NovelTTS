package com.noveltts.service;

import com.noveltts.domain.entity.NovelCharacter;
import com.noveltts.domain.entity.TtsVoice;
import com.noveltts.domain.repository.TtsVoiceRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 依角色特徵推測音色（自動指派）。
 */
@Service
public class VoiceSuggestionService {

    private final TtsVoiceRepository voiceRepository;

    public VoiceSuggestionService(TtsVoiceRepository voiceRepository) {
        this.voiceRepository = voiceRepository;
    }

    public TtsVoice suggest(NovelCharacter character) {
        List<TtsVoice> voices = voiceRepository.findByEnabledTrueOrderBySortOrderAscDisplayNameAsc();
        if (voices.isEmpty()) {
            return null;
        }
        String gender = character.getGender() == null ? "" : character.getGender().toLowerCase(Locale.ROOT);
        String desc = join(character.getDescription(), character.getPersonality(), character.getName());

        boolean female = gender.contains("女") || gender.contains("female")
                || containsAny(desc, "女", "少女", "姑娘", "母", "姊", "姐", "夫人", "小姐", "她");
        boolean male = gender.contains("男") || gender.contains("male")
                || containsAny(desc, "男", "少年", "先生", "叔", "伯", "爺", "他");

        List<TtsVoice> candidates = voices.stream()
                .filter(v -> female && isFemale(v))
                .toList();
        if (candidates.isEmpty()) {
            candidates = voices.stream().filter(v -> male && isMale(v)).toList();
        }
        if (candidates.isEmpty()) {
            candidates = voices;
        }
        return candidates.get(Math.abs((character.getName() == null ? 0 : character.getName().hashCode()))
                % candidates.size());
    }

    private boolean isFemale(TtsVoice v) {
        return containsAny(text(v), "女", "female", "kore", "aoede", "leda");
    }

    private boolean isMale(TtsVoice v) {
        return containsAny(text(v), "男", "male", "puck", "charon", "fenrir");
    }

    private String text(TtsVoice v) {
        return join(v.getDisplayName(), v.getStyleDesc(), v.getVoiceId()).toLowerCase(Locale.ROOT);
    }

    private String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null) {
                sb.append(p).append(' ');
            }
        }
        return sb.toString();
    }

    private boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text != null && text.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
