package com.noveltts.domain.repository;

import com.noveltts.domain.entity.GroupCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GroupCharacterRepository extends JpaRepository<GroupCharacter, Long> {

    /** 群組的共用角色（連同音色與群組），避免 lazy proxy 過期。 */
    @Query("SELECT c FROM GroupCharacter c "
            + "LEFT JOIN FETCH c.ttsVoice "
            + "LEFT JOIN FETCH c.group "
            + "WHERE c.group.id = :groupId "
            + "ORDER BY c.id ASC")
    List<GroupCharacter> findByGroupIdWithDetails(@Param("groupId") Long groupId);

    Optional<GroupCharacter> findByGroupIdAndName(Long groupId, String name);
}
