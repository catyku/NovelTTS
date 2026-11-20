package com.noveltts.ai;

import com.noveltts.ai.dto.SegmentAnalysis;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentParserTest {

    private final SegmentParser parser = new SegmentParser();

    @Test
    void 解析純JSON() {
        String json = """
                {"segments":[
                  {"seq":1,"type":"NARRATION","speaker_name":"旁白","character_name":null,
                   "text":"夜色籠罩著小鎮","emotion":"平","style_hint":"低沉",
                   "style_instructions":"以沉穩低沉的語氣朗讀"},
                  {"seq":2,"type":"DIALOGUE","speaker_name":"小滿","character_name":"林小滿",
                   "text":"你到底要不要進來？","emotion":"驚","style_hint":"急促",
                   "style_instructions":"語速偏快"}
                ]}
                """;

        SegmentAnalysis result = parser.parse(json);

        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().get(0).type()).isEqualTo("NARRATION");
        assertThat(result.segments().get(1).character_name()).isEqualTo("林小滿");
        assertThat(result.segments().get(1).text()).isEqualTo("你到底要不要進來？");
    }

    @Test
    void JSON被截斷時救回已完成段落() {
        // 模擬模型輸出到一半被 max_tokens 截斷：陣列沒有收尾
        String truncated = "{\"segments\":["
                + "{\"seq\":1,\"type\":\"NARRATION\",\"text\":\"第一段。\"},"
                + "{\"seq\":2,\"type\":\"DIALOGUE\",\"character_name\":\"林晚\",\"text\":\"「你來了。」\"},"
                + "{\"seq\":3,\"type\":\"NARRATION\",\"text\":\"第三段還沒寫完";

        SegmentAnalysis result = parser.parse(truncated);

        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().get(0).text()).isEqualTo("第一段。");
        assertThat(result.segments().get(1).character_name()).isEqualTo("林晚");
    }

    @Test
    void 去除markdown圍欄後解析() {
        String withFence = "```json\n{\"segments\":[{\"seq\":1,\"type\":\"NARRATION\",\"text\":\"測試\"}]}\n```";

        SegmentAnalysis result = parser.parse(withFence);

        assertThat(result.segments()).hasSize(1);
        assertThat(result.segments().get(0).text()).isEqualTo("測試");
    }

    @Test
    void 從雜訊中擷取JSON() {
        String noisy = "好的，以下是分析結果：\n{\"segments\":[{\"seq\":1,\"type\":\"NARRATION\",\"text\":\"測試\"}]}\n希望有幫助。";

        SegmentAnalysis result = parser.parse(noisy);

        assertThat(result.segments()).hasSize(1);
    }

    @Test
    void 非法JSON拋出可讀錯誤() {
        assertThatThrownBy(() -> parser.parse("這不是 JSON"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void 空回應拋出錯誤() {
        assertThatThrownBy(() -> parser.parse(""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void segments為空拋出錯誤() {
        assertThatThrownBy(() -> parser.parse("{\"segments\":[]}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("segments");
    }

    @Test
    void DIALOGUE缺角色名拋出錯誤() {
        String json = "{\"segments\":[{\"seq\":1,\"type\":\"DIALOGUE\",\"text\":\"你好\"}]}";

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("character_name");
    }

    @Test
    void 非法type拋出錯誤() {
        String json = "{\"segments\":[{\"seq\":1,\"type\":\"WHISPER\",\"text\":\"你好\"}]}";

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("type");
    }
}
