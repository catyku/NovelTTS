package com.noveltts.domain.repository;

import com.noveltts.domain.entity.UsageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface UsageLogRepository extends JpaRepository<UsageLog, Long> {

    List<UsageLog> findByJobId(Long jobId);

    List<UsageLog> findByCreatedAtAfter(OffsetDateTime since);

    /** 總費用（含 null 值時視為 0）。 */
    @Query("SELECT COALESCE(SUM(u.estimatedCost), 0) FROM UsageLog u")
    BigDecimal sumEstimatedCost();

    /** 文字模型的總費用。 */
    @Query("SELECT COALESCE(SUM(u.estimatedCost), 0) FROM UsageLog u WHERE u.kind = com.noveltts.domain.enums.UsageKind.TEXT")
    BigDecimal sumTextCost();

    /** 語音合成的總費用。 */
    @Query("SELECT COALESCE(SUM(u.estimatedCost), 0) FROM UsageLog u WHERE u.kind = com.noveltts.domain.enums.UsageKind.AUDIO")
    BigDecimal sumAudioCost();

    /** 有記錄費用的筆數（用於估算覆蓋率）。 */
    @Query("SELECT COUNT(u) FROM UsageLog u WHERE u.estimatedCost IS NOT NULL")
    long countWithCost();

    /** 總筆數。 */
    @Query("SELECT COUNT(u) FROM UsageLog u")
    long countAll();
}
