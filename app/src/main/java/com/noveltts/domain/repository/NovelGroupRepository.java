package com.noveltts.domain.repository;

import com.noveltts.domain.entity.NovelGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NovelGroupRepository extends JpaRepository<NovelGroup, Long> {

    /** 全部群組，連同旁白音色一起載入（避免 lazy proxy 過期）。 */
    @Query("SELECT g FROM NovelGroup g LEFT JOIN FETCH g.narratorVoice ORDER BY g.id DESC")
    List<NovelGroup> findAllWithDetailsByOrderByIdDesc();

    Optional<NovelGroup> findByTitle(String title);

    /** 群組及其共用角色（連同音色），避免 lazy proxy 過期。 */
    @Query("SELECT g FROM NovelGroup g "
            + "LEFT JOIN FETCH g.narratorVoice "
            + "WHERE g.id = :id")
    Optional<NovelGroup> findByIdWithDetails(@Param("id") Long id);
}
