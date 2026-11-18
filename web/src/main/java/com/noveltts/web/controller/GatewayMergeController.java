package com.noveltts.web.controller;

import com.noveltts.tts.AudioTool;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 無狀態音檔合併與 ZIP 打包端點。
 *
 * <p>瀏覽器端不跑 ffmpeg —— 合併與打包都在 server 端完成，
 * 瀏覽器把音檔位元組送進來、拿回結果，server 不保留任何檔案。
 *
 * <p>合併與 ZIP 都需要 ffmpeg 與 zip 這類原生工具，搬到瀏覽器需要
 * ffmpeg.wasm（約 30MB 的 wasm），對這個用途來說不划算。
 *
 * <p>請求格式使用 multipart：每個 part 是一個音檔，檔名為它在 ZIP 中的路徑。
 */
@RestController
@RequestMapping("/api/gateway")
public class GatewayMergeController {

    private final AudioTool audioTool;

    public GatewayMergeController(AudioTool audioTool) {
        this.audioTool = audioTool;
    }

    /**
     * 合併多個 MP3。
     *
     * <p>multipart 的每個 part 是依序要合併的 MP3（以 part 順序為準）。
     *
     * @return 合併後的 MP3
     */
    @PostMapping(value = "/merge", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> merge(
            @org.springframework.web.bind.annotation.RequestParam("files") List<org.springframework.web.multipart.MultipartFile> files) {

        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("沒有可合併的音檔");
        }

        Path tmpDir = null;
        Path output = null;
        try {
            tmpDir = Files.createTempDirectory("noveltts-merge-");
            List<Path> inputs = new ArrayList<>();
            int i = 0;
            for (org.springframework.web.multipart.MultipartFile f : files) {
                if (f.isEmpty()) {
                    continue;
                }
                Path p = tmpDir.resolve(String.format("in_%04d.mp3", i++));
                try (var in = f.getInputStream()) {
                    Files.copy(in, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                inputs.add(p);
            }
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("沒有可合併的音檔");
            }

            output = tmpDir.resolve("out.mp3");
            audioTool.concatMp3(inputs, output);
            byte[] merged = Files.readAllBytes(output);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/mpeg"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"merged.mp3\"")
                    .body(merged);
        } catch (IOException e) {
            throw new IllegalStateException("合併音檔失敗: " + e.getMessage(), e);
        } finally {
            deleteDir(tmpDir);
        }
    }

    /**
     * 把多個音檔打包成 ZIP。
     *
     * <p>每個 part 的檔名即為 ZIP 中的條目路徑（例如 {@code chapters/ch001.mp3}）。
     *
     * @return ZIP 位元組
     */
    @PostMapping(value = "/zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> zip(
            @org.springframework.web.bind.annotation.RequestParam("files") List<org.springframework.web.multipart.MultipartFile> files,
            @org.springframework.web.bind.annotation.RequestParam(value = "name", required = false) String archiveName) {

        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("沒有可打包的檔案");
        }

        try (var out = new java.io.ByteArrayOutputStream()) {
            try (ZipOutputStream zos = new ZipOutputStream(out)) {
                for (org.springframework.web.multipart.MultipartFile f : files) {
                    if (f.isEmpty()) {
                        continue;
                    }
                    String entryName = f.getOriginalFilename();
                    if (entryName == null || entryName.isBlank()) {
                        entryName = "audio.mp3";
                    }
                    // 防路徑穿越：只取檔名部分
                    entryName = entryName.replace("\\", "/");
                    int slash = entryName.lastIndexOf('/');
                    if (slash >= 0) {
                        entryName = entryName.substring(slash + 1);
                    }
                    zos.putNextEntry(new ZipEntry(entryName));
                    try (var in = f.getInputStream()) {
                        in.transferTo(zos);
                    }
                    zos.closeEntry();
                }
            }
            byte[] zip = out.toByteArray();
            String name = (archiveName == null || archiveName.isBlank()) ? "novel" : archiveName;
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            com.noveltts.web.ContentDisposition.attachment(name + ".zip"))
                    .body(zip);
        } catch (IOException e) {
            throw new IllegalStateException("打包 ZIP 失敗: " + e.getMessage(), e);
        }
    }

    /** 遞迴刪除暫存目錄。 */
    private void deleteDir(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 暫存檔刪除失敗不影響主流程
                }
            });
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
