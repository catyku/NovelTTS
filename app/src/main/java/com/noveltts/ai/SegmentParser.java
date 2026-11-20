package com.noveltts.ai;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import com.noveltts.ai.dto.SegmentAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 回應解析與驗證。流程見 spec/02 §1.6
 */
@Component
public class SegmentParser {

    private static final Logger log = LoggerFactory.getLogger(SegmentParser.class);

    private final JsonMapper mapper;

    public SegmentParser() {
        // Jackson 3 的 ObjectMapper 為不可變，須用 builder 建構
        this.mapper = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
    }

    /**
     * 解析並驗證 AI 回應。失敗時拋出 IllegalStateException，訊息說明失敗原因，
     * 供呼叫端組成重試提示。
     */
    public SegmentAnalysis parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("AI 回應為空");
        }
        String json = stripCodeFence(content);
        SegmentAnalysis analysis;
        try {
            analysis = mapper.readValue(json, SegmentAnalysis.class);
        } catch (Exception e) {
            String extracted = extractJsonSlice(json);
            if (extracted == null) {
                throw new IllegalStateException("回應不是合法 JSON: " + truncate(content));
            }
            try {
                analysis = mapper.readValue(extracted, SegmentAnalysis.class);
            } catch (Exception e2) {
                // JSON 被 max_tokens 截斷是很常見的情況（章節長、段落多時）。
                // 與其整章失敗，不如把已經完整的那些段落救回來。
                SegmentAnalysis salvaged = salvageTruncatedJson(extracted);
                if (salvaged != null) {
                    log.warn("AI 回應的 JSON 被截斷，已救回 {} 段", salvaged.segments().size());
                    validate(salvaged);
                    return salvaged;
                }
                throw new IllegalStateException("回應不是合法 JSON: " + truncate(content));
            }
        }
        validate(analysis);
        return analysis;
    }

    /**
     * 從被截斷的 JSON 裡救回完整的段落。
     *
     * <p>模型輸出到一半被 max_tokens 截斷時，JSON 會缺結尾的
     * {@code ]}}。做法是找出每個完整段落的邊界，逐一取出再重組成合法 JSON。
     *
     * @return 救回的結果；沒有任何完整段落時回傳 null
     */
    private SegmentAnalysis salvageTruncatedJson(String json) {
        try {
            // 找出 "segments" 陣列的內容區間
            int key = json.indexOf("\"segments\"");
            if (key < 0) return null;
            int arrStart = json.indexOf('[', key);
            if (arrStart < 0) return null;

            // 逐一抓出完整的 {...} 物件
            java.util.List<String> objects = new java.util.ArrayList<>();
            int depth = 0;
            int objStart = -1;
            boolean inString = false;
            boolean escaped = false;
            for (int i = arrStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    if (depth == 0) objStart = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        objects.add(json.substring(objStart, i + 1));
                        objStart = -1;
                    }
                }
            }
            if (objects.isEmpty()) return null;

            StringBuilder sb = new StringBuilder("{\"segments\":[");
            for (int i = 0; i < objects.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(objects.get(i));
            }
            sb.append("]}");
            return mapper.readValue(sb.toString(), SegmentAnalysis.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void validate(SegmentAnalysis analysis) {
        List<SegmentAnalysis.Segment> segments = analysis.segments();
        if (segments == null || segments.isEmpty()) {
            throw new IllegalStateException("segments 為空");
        }
        int expected = 1;
        for (SegmentAnalysis.Segment s : segments) {
            if (s.text() == null || s.text().isBlank()) {
                throw new IllegalStateException("第 " + expected + " 段 text 為空");
            }
            String type = s.type() == null ? "NARRATION" : s.type().toUpperCase();
            if (!"NARRATION".equals(type) && !"DIALOGUE".equals(type)) {
                throw new IllegalStateException("第 " + expected + " 段 type 非法: " + s.type());
            }
            if ("DIALOGUE".equals(type)
                    && (s.character_name() == null || s.character_name().isBlank())) {
                throw new IllegalStateException("第 " + expected + " 段為 DIALOGUE 但缺 character_name");
            }
            if (s.seq() != null && s.seq() != expected) {
                log.debug("seq 不連續: 期望 {} 實際 {}", expected, s.seq());
            }
            expected++;
        }
    }

    private String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence > 0) {
                trimmed = trimmed.substring(0, lastFence);
            }
        }
        return trimmed.trim();
    }

    private String extractJsonSlice(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return null;
    }

    private String truncate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
