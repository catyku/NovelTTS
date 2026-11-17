package com.noveltts.tts;

import com.noveltts.config.NovelTtsProperties;
import com.noveltts.domain.entity.DialogueSegment;
import com.noveltts.domain.entity.NovelCharacter;
import com.noveltts.domain.enums.SegmentType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 依 spec/02 §2.3 把段落分組成 multi-speaker TTS 請求。
 *
 * 分組規則：
 * 1. 依「說話者」切 turn：同一說話者連續多段合併為一個 turn。
 *    判斷說話者的依據是「角色身分」，不能只比音色與語氣——
 *    兩個不同角色若恰好音色與語氣相同（常見於尚未指派音色的角色），
 *    若只看 voice/instructions 就會被合併，造成同一音色念出兩個人的對話。
 * 2. 再依 group 字數上限切分 group；單一 turn 超過上限時獨自成組，不與其他 turn 合併。
 */
@Component
public class TtsGroupBuilder {

    /**
     * 群組層級的旁白覆寫。
     *
     * 同一本連載小說的各集必須用同一個旁白音色，否則聽眾會以為換了本書。
     */
    public record GroupContext(String narratorVoiceId, String narratorInstructions) {
        public static GroupContext none() {
            return new GroupContext(null, null);
        }
    }

    private final NovelTtsProperties properties;

    public TtsGroupBuilder(NovelTtsProperties properties) {
        this.properties = properties;
    }

    public List<TtsGroup> build(List<DialogueSegment> segments) {
        return build(segments, GroupContext.none());
    }

    /**
     * 建立 TTS 分組。
     *
     * @param groupContext 群組層級的旁白覆寫；null 或空白欄位表示沿用全域設定
     */
    public List<TtsGroup> build(List<DialogueSegment> segments, GroupContext groupContext) {
        List<TtsGroup> groups = new ArrayList<>();
        if (segments == null || segments.isEmpty()) {
            return groups;
        }

        if (groupContext == null) {
            groupContext = GroupContext.none();
        }

        // 1. 切 turn
        List<TurnDraft> drafts = new ArrayList<>();
        for (DialogueSegment s : segments) {
            String voice = resolveVoice(s, groupContext);
            String instructions = resolveInstructions(s, groupContext);
            String speaker = resolveSpeaker(s);
            if (!drafts.isEmpty()) {
                TurnDraft last = drafts.get(drafts.size() - 1);
                if (last.speaker.equals(speaker)
                        && last.voice.equals(voice)
                        && last.instructions.equals(instructions)) {
                    last.text.append('\n').append(s.getText());
                    last.segments.add(s);
                    continue;
                }
            }
            TurnDraft draft = new TurnDraft(speaker, voice, instructions, new StringBuilder(s.getText()));
            draft.segments.add(s);
            drafts.add(draft);
        }

        // 2. 切 group
        List<TtsGroup.Turn> currentTurns = new ArrayList<>();
        List<DialogueSegment> currentSegments = new ArrayList<>();
        int currentChars = 0;

        int max = Math.max(1, properties.getTtsGroupMaxChars());
        for (TurnDraft draft : drafts) {
            int draftChars = draft.text.length();
            boolean overflow = draftChars > max;

            if (!currentTurns.isEmpty() && (currentChars + draftChars > max || overflow)) {
                groups.add(buildGroup(currentSegments, currentTurns, currentChars));
                currentTurns = new ArrayList<>();
                currentSegments = new ArrayList<>();
                currentChars = 0;
            }
            currentTurns.add(new TtsGroup.Turn(draft.text.toString(), draft.voice, draft.instructions));
            currentSegments.addAll(draft.segments);
            currentChars += draftChars;
        }
        if (!currentTurns.isEmpty()) {
            groups.add(buildGroup(currentSegments, currentTurns, currentChars));
        }
        return groups;
    }

    private TtsGroup buildGroup(List<DialogueSegment> segments, List<TtsGroup.Turn> turns, int chars) {
        return new TtsGroup(List.copyOf(segments), List.copyOf(turns), chars);
    }

    /**
     * 說話者識別碼：角色用角色名（同名即同一人），旁白固定為旁白。
     * 段落若自行指定 voiceId，仍視為同一角色（覆寫音色不改變說話者）。
     */
    private String resolveSpeaker(DialogueSegment segment) {
        if (segment.getType() == SegmentType.NARRATION) {
            return "__NARRATION__";
        }
        NovelCharacter character = segment.getCharacter();
        if (character != null && character.getName() != null) {
            return "C:" + character.getName();
        }
        return "S:" + Objects.toString(segment.getSpeakerName(), "");
    }

    private String resolveVoice(DialogueSegment segment, GroupContext groupContext) {
        if (segment.getVoiceId() != null && !segment.getVoiceId().isBlank()) {
            return segment.getVoiceId();
        }
        if (segment.getType() == SegmentType.NARRATION) {
            // 群組的旁白音色優先於全域設定（跨集一致）。
            if (groupContext.narratorVoiceId() != null
                    && !groupContext.narratorVoiceId().isBlank()) {
                return groupContext.narratorVoiceId();
            }
            return properties.getNarratorVoiceId();
        }
        NovelCharacter character = segment.getCharacter();
        if (character != null && character.getTtsVoice() != null) {
            return character.getTtsVoice().getVoiceId();
        }
        return properties.getDefaultVoiceId();
    }

    private String resolveInstructions(DialogueSegment segment, GroupContext groupContext) {
        List<String> parts = new ArrayList<>();
        NovelCharacter character = segment.getCharacter();
        if (character != null && character.getTtsInstructions() != null
                && !character.getTtsInstructions().isBlank()) {
            parts.add(character.getTtsInstructions().strip());
        }
        if (segment.getInstructions() != null && !segment.getInstructions().isBlank()) {
            parts.add(segment.getInstructions().strip());
        }
        // 旁白的群組語氣：讓跨集旁白不僅音色相同，語氣也一致。
        if (segment.getType() == SegmentType.NARRATION
                && groupContext.narratorInstructions() != null
                && !groupContext.narratorInstructions().isBlank()) {
            parts.add(groupContext.narratorInstructions().strip());
        }
        return String.join("，", parts);
    }

    private static final class TurnDraft {
        private final String speaker;
        private final String voice;
        private final String instructions;
        private final StringBuilder text;
        private final List<DialogueSegment> segments = new ArrayList<>();

        TurnDraft(String speaker, String voice, String instructions, StringBuilder text) {
            this.speaker = speaker;
            this.voice = voice;
            this.instructions = instructions;
            this.text = text;
        }
    }
}
