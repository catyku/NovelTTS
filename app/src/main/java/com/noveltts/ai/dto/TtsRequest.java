package com.noveltts.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TtsRequest(
        String model,
        Object input,
        String voice,
        String instructions,
        String response_format,
        Map<String, Object> provider
) {

    /**
     * 依 turn 的組成決定使用 multi-speaker 陣列或單一字串。
     *
     * Gemini TTS（google/gemini-3.8-flash-tts）對 /audio/speech 的 input 有一條硬限制，
     * 實測違反會得到 400：
     * 「input 為陣列時，turn 之間必須包含 2 種以上不同的 voice。」
     *
     * 實測結果（全部回 200 除非特別標示）：
     * - [Kore]（單一 turn）→ 400
     * - [Kore, Kore] → 400
     * - [Kore, Kore, Kore] → 400
     * - [Kore, Puck] → 200
     * - [Kore, Kore, Puck] → 200
     * - [Kore, Puck, Kore] → 200
     * - [Kore, Puck, Kore, Kore] → 200
     *
     * 也就是說唯一的失敗條件是「所有 turn 都用同一個 voice」。
     * 相鄰 turn 重複 voice 並非問題，只要整體有第二種 voice 即可。
     *
     * 因此陣列形式只在「有 2 種以上不同 voice」時使用；
     * 全部同一 voice 時退回「input 為字串 + 頂層 voice」的形式（單字串形式可正常運作）。
     */
    public static TtsRequest fromTurns(String model, List<TtsTurn> turns, String globalInstructions,
                                        Map<String, Object> provider) {
        if (turns == null || turns.isEmpty()) {
            throw new IllegalArgumentException("TTS 請求沒有任何 turn");
        }
        long distinctVoices = turns.stream()
                .map(TtsTurn::voice)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
        String joinedInstructions = mergeInstructions(globalInstructions, turns);

        if (distinctVoices >= 2) {
            return new TtsRequest(model, turns, null, joinedInstructions, "pcm", provider);
        }

        // 只有一種 voice：退回單字串 + 頂層 voice 的形式
        String voice = turns.get(0).voice();
        String text = turns.stream()
                .map(TtsTurn::text)
                .filter(java.util.Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        return new TtsRequest(model, text, voice, joinedInstructions, "pcm", provider);
    }

    /**
     * 合併全域語氣指令與各 turn 的語氣指令。
     * 多音色時各 turn 自身的語氣由 turn 內層攜帶，頂層只放全域指令；
     * 單音色時沒有 turn 層可依附，必須把各 turn 的語氣併入頂層。
     */
    private static String mergeInstructions(String globalInstructions, List<TtsTurn> turns) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (globalInstructions != null && !globalInstructions.isBlank()) {
            parts.add(globalInstructions.strip());
        }
        boolean singleVoice = turns.stream()
                .map(TtsTurn::voice)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count() < 2;
        if (singleVoice) {
            for (TtsTurn t : turns) {
                if (t.instructions() != null && !t.instructions().isBlank()
                        && !parts.contains(t.instructions().strip())) {
                    parts.add(t.instructions().strip());
                }
            }
        }
        return parts.isEmpty() ? null : String.join("，", parts);
    }
}
