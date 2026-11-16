package com.noveltts.domain.repository;

import com.noveltts.domain.entity.TtsVoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TtsVoiceRepository extends JpaRepository<TtsVoice, Long> {

    List<TtsVoice> findByEnabledTrueOrderBySortOrderAscDisplayNameAsc();

    List<TtsVoice> findByModel(String model);

    List<TtsVoice> findAllByOrderBySortOrderAscDisplayNameAsc();
}
