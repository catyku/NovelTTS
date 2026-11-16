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
                throw new IllegalStateException("回應不是合法 JSON: " + truncate(content));
            }
        }
        validate(analysis);
        return analysis;
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
