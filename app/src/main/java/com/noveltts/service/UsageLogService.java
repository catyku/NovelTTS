package com.noveltts.service;

import com.noveltts.domain.entity.UsageLog;
import com.noveltts.domain.enums.UsageKind;
import com.noveltts.domain.repository.UsageLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class UsageLogService {

    private final UsageLogRepository repository;

    public UsageLogService(UsageLogRepository repository) {
        this.repository = repository;
    }

    /**
     * 記錄文字模型的用量。
     *
     * @param cost OpenRouter 回報的實際費用（USD）。無回報時傳 null。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logText(String provider, String model, Integer promptTokens, Integer completionTokens,
                        BigDecimal cost, Long jobId) {
        UsageLog log = new UsageLog();
        log.setProvider(provider);
        log.setModel(model);
        log.setKind(UsageKind.TEXT);
        log.setPromptTokens(promptTokens);
        log.setCompletionTokens(completionTokens);
        log.setEstimatedCost(cost);
        log.setJobId(jobId);
        repository.save(log);
    }

    /**
     * 記錄語音合成的用量。
     *
     * @param cost 依模型定價與字元數估算的費用（USD）。TTS 端點不回報費用，
     *             此為估算值而非實際扣款。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAudio(String provider, String model, Integer charCount, Integer durationMs,
                         BigDecimal cost, Long jobId) {
        UsageLog log = new UsageLog();
        log.setProvider(provider);
        log.setModel(model);
        log.setKind(UsageKind.AUDIO);
        log.setCharCount(charCount);
        log.setDurationMs(durationMs);
        log.setEstimatedCost(cost);
        log.setJobId(jobId);
        repository.save(log);
    }
}
