package com.noveltts.tts;

import com.noveltts.config.NovelTtsProperties;
import com.noveltts.domain.entity.DialogueSegment;
import com.noveltts.domain.entity.NovelCharacter;
import com.noveltts.domain.enums.SegmentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TtsGroupBuilderTest {

    private NovelTtsProperties properties;
    private TtsGroupBuilder builder;

    @BeforeEach
    void setUp() {
        properties = new NovelTtsProperties();
        properties.setTtsGroupMaxChars(100);
        properties.setNarratorVoiceId("Kore");
        properties.setDefaultVoiceId("Kore");
        builder = new TtsGroupBuilder(properties);
    }

    private DialogueSegment segment(int seq, SegmentType type, String characterName,
                                    String text, String instructions) {
        DialogueSegment s = new DialogueSegment();
        s.setSeq(seq);
        s.setType(type);
        s.setText(text);
        s.setInstructions(instructions);
        if (characterName != null) {
            NovelCharacter c = new NovelCharacter();
            c.setName(characterName);
            c.setTtsInstructions("角色語氣");
            s.setCharacter(c);
        }
        return s;
    }

    @Test
    void 同角色連續段落合併為同一turn() {
        List<DialogueSegment> segs = List.of(
                segment(1, SegmentType.DIALOGUE, "小滿", "第一句", null),
                segment(2, SegmentType.DIALOGUE, "小滿", "第二句", null));

        List<TtsGroup> groups = builder.build(segs);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).turns()).hasSize(1);
        assertThat(groups.get(0).turns().get(0).text()).isEqualTo("第一句\n第二句");
        assertThat(groups.get(0).segments()).hasSize(2);
    }

    @Test
    void 角色切換產生不同turn() {
        List<DialogueSegment> segs = List.of(
                segment(1, SegmentType.DIALOGUE, "小滿", "你好", null),
                segment(2, SegmentType.DIALOGUE, "老人", "進來吧", null));

        List<TtsGroup> groups = builder.build(segs);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).turns()).hasSize(2);
    }

    @Test
    void 旁白使用旁白音色() {
        List<DialogueSegment> segs = List.of(
                segment(1, SegmentType.NARRATION, null, "夜色籠罩著小鎮", "以沉穩的語氣朗讀"));

        List<TtsGroup> groups = builder.build(segs);

        assertThat(groups.get(0).turns().get(0).voice()).isEqualTo("Kore");
        assertThat(groups.get(0).turns().get(0).instructions()).isEqualTo("以沉穩的語氣朗讀");
    }

    @Test
    void 角色語氣與段落語氣合併() {
        List<DialogueSegment> segs = List.of(
                segment(1, SegmentType.DIALOGUE, "小滿", "你好", "語速偏快"));

        List<TtsGroup> groups = builder.build(segs);

        assertThat(groups.get(0).turns().get(0).instructions())
                .isEqualTo("角色語氣，語速偏快");
    }

    @Test
    void 超過字數上限切成多組() {
        String longText = "長".repeat(60);
        List<DialogueSegment> segs = List.of(
                segment(1, SegmentType.DIALOGUE, "小滿", longText, null),
                segment(2, SegmentType.DIALOGUE, "老人", longText, null),
                segment(3, SegmentType.DIALOGUE, "小滿", longText, null));

        List<TtsGroup> groups = builder.build(segs);

        assertThat(groups).hasSizeGreaterThan(1);
        assertThat(groups).allSatisfy(g -> assertThat(g.charCount()).isLessThanOrEqualTo(100));
    }

    @Test
    void 空輸入回傳空清單() {
        assertThat(builder.build(List.of())).isEmpty();
        assertThat(builder.build(null)).isEmpty();
    }
}
