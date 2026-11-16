package com.noveltts.domain.repository;

import com.noveltts.domain.entity.NovelCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NovelCharacterRepository extends JpaRepository<NovelCharacter, Long> {

    List<NovelCharacter> findByNovelIdOrderByIdAsc(Long novelId);

    /**
     * 取某小說的角色，連同音色與小說一起載入。
     * fetch join 避免 lazy proxy 在 session 關閉後才被存取而拋
     * LazyInitializationException（頁面與 API 都會讀取 ttsVoice 的 voiceId）。
     */
    @Query("SELECT c FROM NovelCharacter c "
            + "LEFT JOIN FETCH c.ttsVoice "
            + "LEFT JOIN FETCH c.novel "
            + "WHERE c.novel.id = :novelId "
            + "ORDER BY c.id ASC")
    List<NovelCharacter> findByNovelIdWithDetails(@Param("novelId") Long novelId);

    Optional<NovelCharacter> findByNovelIdAndName(Long novelId, String name);
}
