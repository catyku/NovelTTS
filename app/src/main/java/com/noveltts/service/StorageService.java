package com.noveltts.service;

import com.noveltts.config.NovelTtsProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 音檔儲存根目錄管理。
 */
@Service
public class StorageService {

    private final NovelTtsProperties properties;
    private Path root;

    public StorageService(NovelTtsProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        this.root = Paths.get(properties.getStorageRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("無法建立儲存根目錄 " + root + ": " + e.getMessage(), e);
        }
    }

    public Path root() {
        return root;
    }

    public Path novelDir(Long novelId) {
        return root.resolve("novel").resolve(String.valueOf(novelId));
    }

    public Path chapterDir(Long novelId, Long chapterId) {
        return novelDir(novelId).resolve("ch" + chapterId);
    }

    public void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("無法建立目錄 " + dir + ": " + e.getMessage(), e);
        }
    }

    /** 刪除整本小說的實體檔案（DB cascade 不會動檔案系統）。 */
    public void deleteNovelFiles(Long novelId) {
        deleteRecursively(novelDir(novelId));
    }

    public void deleteFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 忽略：刪除失敗不阻擋主流程
        }
    }

    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 忽略
                }
            });
        } catch (IOException e) {
            // 忽略
        }
    }
}
