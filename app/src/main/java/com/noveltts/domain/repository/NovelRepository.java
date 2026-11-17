package com.noveltts.domain.repository;

import com.noveltts.domain.entity.Novel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NovelRepository extends JpaRepository<Novel, Long> {

    /** 群組內的各集（依集數順序），連同群組一起載入。 */
    @Query("SELECT n FROM Novel n LEFT JOIN FETCH n.group WHERE n.group.id = :groupId "
            + "ORDER BY n.groupOrder ASC, n.id ASC")
    List<Novel> findByGroupIdWithDetails(@Param("groupId") Long groupId);

    /** 單筆小說，連同群組一起載入（避免 DTO 轉換時 lazy proxy 失效）。 */
    @Query("SELECT n FROM Novel n LEFT JOIN FETCH n.group WHERE n.id = :id")
    Optional<Novel> findByIdWithGroup(@Param("id") Long id);

    /**
     * 全部小說，連同群組一起載入。
     *
     * group 是 LAZY 關聯，DTO 轉換時若在 session 外讀取會拋
     * "Could not initialize proxy ... no session"，因此查詢時就 fetch join。
     */
    @Query("SELECT n FROM Novel n LEFT JOIN FETCH n.group ORDER BY n.id DESC")
    List<Novel> findAllWithGroupByOrderByIdDesc();

    /** 關鍵字搜尋，連同群組一起載入。 */
    @Query("SELECT n FROM Novel n LEFT JOIN FETCH n.group "
            + "WHERE LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY n.id DESC")
    List<Novel> findByTitleContainingIgnoreCaseWithGroup(@Param("keyword") String keyword);

    /** 群組的各集數量。 */
    @Query("SELECT COUNT(n) FROM Novel n WHERE n.group.id = :groupId")
    long countByGroupId(@Param("groupId") Long groupId);
}
