package com.noveltts.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg / ffprobe 封裝。
 */
@Component
public class AudioTool {

    private static final Logger log = LoggerFactory.getLogger(AudioTool.class);
    private static final long TIMEOUT_SECONDS = 300;

    /** PCM（s16le, 單聲道, 24kHz）轉 MP3。 */
    public void pcmToMp3(Path pcmFile, Path mp3File, int sampleRate) {
        List<String> cmd = List.of(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "s16le", "-ar", String.valueOf(sampleRate), "-ac", "1",
                "-i", pcmFile.toString(),
                "-c:a", "libmp3lame", "-b:a", "128k",
                mp3File.toString());
        run(cmd);
    }

    /** 用 concat demuxer 合併多個 mp3。 */
    public void concatMp3(List<Path> inputs, Path output) {
        if (inputs.isEmpty()) {
            throw new IllegalStateException("沒有可合併的音檔");
        }
        if (inputs.size() == 1) {
            try {
                Files.copy(inputs.get(0), output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                throw new IllegalStateException("複製音檔失敗: " + e.getMessage(), e);
            }
        }
        Path listFile = null;
        try {
            listFile = Files.createTempFile("concat-", ".txt");
            StringBuilder sb = new StringBuilder();
            for (Path p : inputs) {
                sb.append("file '").append(p.toAbsolutePath().toString().replace("'", "'\\''")).append("'\n");
            }
            Files.writeString(listFile, sb.toString(), StandardCharsets.UTF_8);

            List<String> cmd = List.of(
                    "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                    "-f", "concat", "-safe", "0", "-i", listFile.toString(),
                    "-c", "copy",
                    output.toString());
            run(cmd);
        } catch (IOException e) {
            throw new IllegalStateException("合併音檔失敗: " + e.getMessage(), e);
        } finally {
            if (listFile != null) {
                try {
                    Files.deleteIfExists(listFile);
                } catch (IOException ignored) {
                    // 忽略
                }
            }
        }
    }

    /** 量測音檔長度（毫秒）。 */
    public Integer durationMs(Path file) {
        List<String> cmd = List.of(
                "ffprobe", "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                file.toString());
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().reduce("", (a, b) -> a + b);
            }
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                return null;
            }
            double seconds = Double.parseDouble(output.trim());
            return (int) Math.round(seconds * 1000);
        } catch (Exception e) {
            log.warn("量測音檔長度失敗: {}", e.getMessage());
            return null;
        }
    }

    private void run(List<String> cmd) {
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("ffmpeg 執行逾時: " + String.join(" ", cmd));
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("ffmpeg 執行失敗 (exit " + process.exitValue() + "): "
                        + String.join("\n", lines));
            }
        } catch (IOException e) {
            throw new IllegalStateException("無法執行 ffmpeg: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ffmpeg 執行被中斷", e);
        }
    }
}
