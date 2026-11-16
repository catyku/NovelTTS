package com.noveltts.domain.repository;

import com.noveltts.domain.entity.AudioFile;
import com.noveltts.domain.enums.AudioKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AudioFileRepository extends JpaRepository<AudioFile, Long> {

    List<AudioFile> findByNovelIdOrderByIdAsc(Long novelId);

    /**
     * 取某小說的音檔，連同小說與章節一起載入（fetch join 避免 lazy proxy 延遲載入）。
     */
    @Query("SELECT a FROM AudioFile a "
            + "LEFT JOIN FETCH a.novel "
            + "LEFT JOIN FETCH a.chapter "
            + "WHERE a.novel.id = :novelId "
            + "ORDER BY a.id ASC")
    List<AudioFile> findByNovelIdWithDetails(@Param("novelId") Long novelId);

    List<AudioFile> findByNovelIdAndKindOrderByIdAsc(Long novelId, AudioKind kind);

    List<AudioFile> findByChapterIdOrderByIdAsc(Long chapterId);

    List<AudioFile> findByChapterIdAndKindOrderByIdAsc(Long chapterId, AudioKind kind);

    void deleteByNovelId(Long novelId);
}
