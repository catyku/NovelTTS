package com.noveltts.service;

import com.noveltts.domain.entity.AudioFile;
import com.noveltts.domain.entity.Novel;
import com.noveltts.domain.enums.AudioKind;
import com.noveltts.domain.repository.AudioFileRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 整本 ZIP 匯出。
 */
@Service
public class ExportService {

    private final AudioFileRepository audioFileRepository;
    private final StorageService storageService;

    public ExportService(AudioFileRepository audioFileRepository, StorageService storageService) {
        this.audioFileRepository = audioFileRepository;
        this.storageService = storageService;
    }

    /**
     * 將小說所有音檔打包成 ZIP，寫入目標路徑。
     */
    public Path exportNovel(Novel novel, Path target) {
        List<AudioFile> files = audioFileRepository.findByNovelIdOrderByIdAsc(novel.getId());
        storageService.ensureDir(target.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(target))) {
            for (AudioFile file : files) {
                Path source = Path.of(file.getFilePath());
                if (!Files.exists(source)) {
                    continue;
                }
                String entryName = entryName(file);
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(source, zos);
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("匯出 ZIP 失敗: " + e.getMessage(), e);
        }
        return target;
    }

    public void exportNovelToStream(Novel novel, OutputStream out) {
        List<AudioFile> files = audioFileRepository.findByNovelIdOrderByIdAsc(novel.getId());
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (AudioFile file : files) {
                Path source = Path.of(file.getFilePath());
                if (!Files.exists(source)) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(entryName(file)));
                Files.copy(source, zos);
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("匯出 ZIP 失敗: " + e.getMessage(), e);
        }
    }

    private String entryName(AudioFile file) {
        if (file.getKind() == AudioKind.SEGMENT) {
            return "segments/" + file.getFileName();
        }
        if (file.getKind() == AudioKind.CHAPTER) {
            return "chapters/" + file.getFileName();
        }
        return file.getFileName();
    }
}
