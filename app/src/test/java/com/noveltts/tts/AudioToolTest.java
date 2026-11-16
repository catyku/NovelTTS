package com.noveltts.tts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 音訊工具整合測試（需要 ffmpeg / ffprobe）。
 * 驗證 PCM -> MP3 轉檔、MP3 合併、時長量測這段 TTS 成功路徑的關鍵環節。
 */
class AudioToolTest {

    private final AudioTool audioTool = new AudioTool();
    private static final int SAMPLE_RATE = 24000;

    /**
     * 產生 1 秒的 440Hz 正弦波 PCM（s16le, mono）。
     */
    private byte[] sineWavePcm(int millis) throws IOException {
        int samples = SAMPLE_RATE * millis / 1000;
        byte[] out = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            double v = Math.sin(2 * Math.PI * 440.0 * i / SAMPLE_RATE) * 16000;
            short s = (short) v;
            out[i * 2] = (byte) (s & 0xFF);
            out[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }

    @Test
    void pcm轉mp3(@TempDir Path dir) throws IOException {
        Path pcm = dir.resolve("a.pcm");
        Path mp3 = dir.resolve("a.mp3");
        Files.write(pcm, sineWavePcm(1000));

        audioTool.pcmToMp3(pcm, mp3, SAMPLE_RATE);

        assertThat(mp3).exists();
        assertThat(Files.size(mp3)).isGreaterThan(0);
        // MP3 檔頭應為 ID3 或 MPEG frame sync
        byte[] head = Files.readAllBytes(mp3);
        assertThat(head.length).isGreaterThan(100);
    }

    @Test
    void 量測音檔長度(@TempDir Path dir) throws IOException {
        Path pcm = dir.resolve("b.pcm");
        Path mp3 = dir.resolve("b.mp3");
        Files.write(pcm, sineWavePcm(2000));
        audioTool.pcmToMp3(pcm, mp3, SAMPLE_RATE);

        Integer duration = audioTool.durationMs(mp3);

        assertThat(duration).isNotNull();
        assertThat(duration).isBetween(1500, 2600);
    }

    @Test
    void 合併多個mp3(@TempDir Path dir) throws IOException {
        Path m1 = dir.resolve("c1.mp3");
        Path m2 = dir.resolve("c2.mp3");
        Path pcm = dir.resolve("c.pcm");
        Files.write(pcm, sineWavePcm(1000));
        audioTool.pcmToMp3(pcm, m1, SAMPLE_RATE);
        audioTool.pcmToMp3(pcm, m2, SAMPLE_RATE);
        Path merged = dir.resolve("merged.mp3");

        audioTool.concatMp3(List.of(m1, m2), merged);

        assertThat(merged).exists();
        Integer duration = audioTool.durationMs(merged);
        assertThat(duration).isNotNull();
        assertThat(duration).isBetween(1500, 3000);
    }

    @Test
    void 合併單一檔案直接複製(@TempDir Path dir) throws IOException {
        Path m1 = dir.resolve("d1.mp3");
        Path pcm = dir.resolve("d.pcm");
        Files.write(pcm, sineWavePcm(500));
        audioTool.pcmToMp3(pcm, m1, SAMPLE_RATE);
        Path merged = dir.resolve("merged1.mp3");

        audioTool.concatMp3(List.of(m1), merged);

        assertThat(merged).exists();
        assertThat(Files.size(merged)).isEqualTo(Files.size(m1));
    }

    @Test
    void 合併空清單會拋出錯誤(@TempDir Path dir) {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> audioTool.concatMp3(List.of(), dir.resolve("x.mp3"))))
                .hasMessageContaining("沒有可合併");
    }
}
