package com.noveltts.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * AI 分析結果的最小可用結構（只取後端需要欄位，其餘忽略）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SegmentAnalysis(List<Segment> segments) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(
            Integer seq,
            String type,
            String speaker_name,
            String character_name,
            String text,
            String emotion,
            String style_hint,
            String style_instructions
    ) {
    }
}
