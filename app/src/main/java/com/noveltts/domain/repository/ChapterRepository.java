package com.noveltts.domain.repository;

import com.noveltts.domain.entity.Chapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChapterRepository extends JpaRepository<Chapter, Long> {

    List<Chapter> findByNovelIdOrderByChapterIndex(Long novelId);

    /**
     * 取某小說的章節，連同小說一起載入（fetch join 避免 lazy proxy 延遲載入）。
     */
    @Query("SELECT c FROM Chapter c "
            + "LEFT JOIN FETCH c.novel "
            + "WHERE c.novel.id = :novelId "
            + "ORDER BY c.chapterIndex ASC")
    List<Chapter> findByNovelIdWithDetails(@Param("novelId") Long novelId);

    void deleteByNovelId(Long novelId);
}
