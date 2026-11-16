package com.noveltts.domain.repository;

import com.noveltts.domain.entity.DialogueSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DialogueSegmentRepository extends JpaRepository<DialogueSegment, Long> {

    List<DialogueSegment> findByChapterIdOrderBySeq(Long chapterId);

    /**
     * 取某章段落，連同音檔與角色一起載入。
     * 用 fetch join 避免 lazy proxy 在 session 關閉後才被存取而拋
     * LazyInitializationException（頁面渲染時讀取 audio 的 duration 等欄位）。
     */
    @Query("SELECT s FROM DialogueSegment s "
            + "LEFT JOIN FETCH s.audio "
            + "LEFT JOIN FETCH s.character "
            + "LEFT JOIN FETCH s.chapter "
            + "WHERE s.chapter.id = :chapterId "
            + "ORDER BY s.seq ASC")
    List<DialogueSegment> findByChapterIdWithDetails(@Param("chapterId") Long chapterId);

    /**
     * 取某小說所有章節的段落，依章節序與段落序排序。
     * 用 @Query 明確描述：衍生方法名 findByChapterNovelIdOrderByChapterChapterIndexSeq
     * 會被 Spring Data 解析成 "Chapter.ChapterIndexSeq" 而失敗。
     */
    @Query("SELECT s FROM DialogueSegment s "
            + "WHERE s.chapter.novel.id = :novelId "
            + "ORDER BY s.chapter.chapterIndex ASC, s.seq ASC")
    List<DialogueSegment> findByNovelIdOrdered(@Param("novelId") Long novelId);

    /**
     * 取某小說所有章節的段落，連同音檔、角色與章節一起載入。
     */
    @Query("SELECT s FROM DialogueSegment s "
            + "LEFT JOIN FETCH s.audio "
            + "LEFT JOIN FETCH s.character "
            + "LEFT JOIN FETCH s.chapter "
            + "WHERE s.chapter.novel.id = :novelId "
            + "ORDER BY s.chapter.chapterIndex ASC, s.seq ASC")
    List<DialogueSegment> findByNovelIdWithDetails(@Param("novelId") Long novelId);

    List<DialogueSegment> findByCharacterId(Long characterId);

    void deleteByChapterId(Long chapterId);
}
