package com.noveltts.ai;

import com.noveltts.ai.dto.TtsRequest;
import com.noveltts.ai.dto.TtsTurn;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TTS 請求格式測試：驗證送給 OpenRouter /audio/speech 的 JSON 結構。
 * 依 spec/02 §2.4。
 */
class TtsRequestTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void 序列化為OpenRouter期望的格式() {
        TtsRequest request = new TtsRequest(
                "google/gemini-3.8-flash-tts",
                List.of(
                        new TtsTurn("你好", "Kore", "以沉穩的語氣朗讀"),
                        new TtsTurn("進來吧", "Charon", null)),
                null,
                "小說有聲書風格",
                "pcm",
                Map.of("zdr", true));

        String json = mapper.writeValueAsString(request);

        assertThat(json).contains("\"model\":\"google/gemini-3.8-flash-tts\"");
        assertThat(json).contains("\"response_format\":\"pcm\"");
        assertThat(json).contains("\"instructions\":\"小說有聲書風格\"");
        assertThat(json).contains("\"voice\":\"Kore\"");
        assertThat(json).contains("\"text\":\"你好\"");
        assertThat(json).contains("\"zdr\":true");
    }

    @Test
    void null的instructions不會送出欄位() {
        TtsRequest request = new TtsRequest(
                "m", List.of(new TtsTurn("測試", "Kore", null)),
                null, "全域", "pcm", null);

        String json = mapper.writeValueAsString(request);

        assertThat(json).doesNotContain("\"instructions\":null");
        assertThat(json).contains("\"text\":\"測試\"");
    }

    @Test
    void voice欄位為null時不送出根層欄位() {
        TtsRequest request = new TtsRequest(
                "m", List.of(new TtsTurn("測試", "Kore", null)),
                null, null, "pcm", null);

        String json = mapper.writeValueAsString(request);

        // 根層不應有 voice（turn 內層的 voice 仍需存在）
        assertThat(mapper.readTree(json).has("voice")).isFalse();
        // turn 內層的 voice 仍要送出
        assertThat(mapper.readTree(json).get("input").get(0).get("voice").asString())
                .isEqualTo("Kore");
    }

    /**
     * Gemini TTS 的 input 陣列必須含 2 種以上不同 voice，否則 provider 回 400。
     * 這是實測 OpenRouter google/gemini-3.8-flash-tts 得出的限制。
     */
    @Test
    void 多種voice時使用陣列形式的input() {
        TtsRequest request = TtsRequest.fromTurns("m",
                List.of(new TtsTurn("你好", "Kore", null),
                        new TtsTurn("進來吧", "Puck", null)),
                "全域", Map.of("zdr", true));

        var root = mapper.readTree(mapper.writeValueAsString(request));
        assertThat(root.get("input").isArray()).isTrue();
        assertThat(root.get("input").get(0).get("voice").asString()).isEqualTo("Kore");
        assertThat(root.get("input").get(1).get("voice").asString()).isEqualTo("Puck");
        // 根層 voice 不應存在
        assertThat(root.has("voice")).isFalse();
    }

    @Test
    void 只有一種voice時改用字串形式的input() {
        TtsRequest request = TtsRequest.fromTurns("m",
                List.of(new TtsTurn("只有旁白", "Kore", null)),
                "全域", null);

        var root = mapper.readTree(mapper.writeValueAsString(request));
        assertThat(root.get("input").isTextual()).isTrue();
        assertThat(root.get("input").asString()).isEqualTo("只有旁白");
        assertThat(root.get("voice").asString()).isEqualTo("Kore");
    }

    @Test
    void 全部同一voice的多個turn合併為字串() {
        TtsRequest request = TtsRequest.fromTurns("m",
                List.of(new TtsTurn("第一段", "Kore", null),
                        new TtsTurn("第二段", "Kore", null)),
                "全域", null);

        var root = mapper.readTree(mapper.writeValueAsString(request));
        assertThat(root.get("input").isTextual()).isTrue();
        assertThat(root.get("input").asString()).isEqualTo("第一段\n第二段");
    }

    @Test
    void 單一voice時把turn語氣併入根層() {
        TtsRequest request = TtsRequest.fromTurns("m",
                List.of(new TtsTurn("旁白", "Kore", "沉穩朗讀")),
                "全域", null);

        var root = mapper.readTree(mapper.writeValueAsString(request));
        assertThat(root.get("instructions").asString()).contains("全域").contains("沉穩朗讀");
    }

    @Test
    void 空turn清單會拋出例外() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> TtsRequest.fromTurns("m", List.of(), "全域", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
