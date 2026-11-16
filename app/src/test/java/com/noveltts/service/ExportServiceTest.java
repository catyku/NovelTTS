package com.noveltts.service;

import com.noveltts.domain.entity.AudioFile;
import com.noveltts.domain.entity.Novel;
import com.noveltts.domain.enums.AudioKind;
import com.noveltts.domain.repository.AudioFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ZIP 匯出測試。
 */
class ExportServiceTest {

    @Test
    void 匯出包含所有存在的音檔(@TempDir Path dir) throws Exception {
        AudioFileRepository repo = mock(AudioFileRepository.class);
        StorageService storage = new StorageService(new com.noveltts.config.NovelTtsProperties());
        ExportService service = new ExportService(repo, storage);

        Novel novel = new Novel();
        novel.setId(1L);
        novel.setTitle("測試小說");

        Path segFile = dir.resolve("seg1.mp3");
        Path chapFile = dir.resolve("ch1.mp3");
        Files.writeString(segFile, "segment-audio");
        Files.writeString(chapFile, "chapter-audio");

        AudioFile seg = new AudioFile();
        seg.setId(1L);
        seg.setNovel(novel);
        seg.setKind(AudioKind.SEGMENT);
        seg.setFileName("seg1.mp3");
        seg.setFilePath(segFile.toString());

        AudioFile chap = new AudioFile();
        chap.setId(2L);
        chap.setNovel(novel);
        chap.setKind(AudioKind.CHAPTER);
        chap.setFileName("ch1.mp3");
        chap.setFilePath(chapFile.toString());

        when(repo.findByNovelIdOrderByIdAsc(any())).thenReturn(List.of(seg, chap));

        Path zip = dir.resolve("out.zip");
        service.exportNovel(novel, zip);

        assertThat(zip).exists();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            assertThat(zf.stream().map(e -> e.getName()).toList())
                    .containsExactlyInAnyOrder("segments/seg1.mp3", "chapters/ch1.mp3");
        }
    }

    @Test
    void 跳過不存在的檔案(@TempDir Path dir) throws Exception {
        AudioFileRepository repo = mock(AudioFileRepository.class);
        StorageService storage = new StorageService(new com.noveltts.config.NovelTtsProperties());
        ExportService service = new ExportService(repo, storage);

        Novel novel = new Novel();
        novel.setId(1L);
        novel.setTitle("測試");

        AudioFile missing = new AudioFile();
        missing.setId(1L);
        missing.setNovel(novel);
        missing.setKind(AudioKind.SEGMENT);
        missing.setFileName("gone.mp3");
        missing.setFilePath(dir.resolve("gone.mp3").toString());

        when(repo.findByNovelIdOrderByIdAsc(any())).thenReturn(List.of(missing));

        Path zip = dir.resolve("out2.zip");
        service.exportNovel(novel, zip);

        assertThat(zip).exists();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            assertThat(zf.stream()).isEmpty();
        }
    }
}
