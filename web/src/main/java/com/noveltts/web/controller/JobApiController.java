package com.noveltts.web.controller;

import com.noveltts.web.exception.NotFoundException;
import com.noveltts.domain.entity.JobTask;
import com.noveltts.domain.enums.JobType;
import com.noveltts.domain.repository.JobTaskRepository;
import com.noveltts.service.AnalysisService;
import com.noveltts.service.JobService;
import com.noveltts.service.TtsService;
import com.noveltts.web.dto.ApiModels;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class JobApiController {

    private final JobTaskRepository jobRepository;
    private final JobService jobService;
    private final AnalysisService analysisService;
    private final TtsService ttsService;

    public JobApiController(JobTaskRepository jobRepository,
                            JobService jobService,
                            AnalysisService analysisService,
                            TtsService ttsService) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.analysisService = analysisService;
        this.ttsService = ttsService;
    }

    @GetMapping("/jobs/{id}")
    public ApiModels.JobDto getJob(@PathVariable Long id) {
        JobTask job = jobRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到 job " + id));
        return toDto(job);
    }

    @GetMapping("/jobs")
    public List<ApiModels.JobDto> listJobs() {
        return jobRepository.findTop20ByOrderByIdDesc().stream().map(this::toDto).toList();
    }

    /** 觸發全本合成。 */
    @PostMapping("/novels/{id}/synthesize")
    public ResponseEntity<ApiModels.JobDto> synthesize(@PathVariable Long id,
                                                      @RequestParam(defaultValue = "true") boolean onlyPending) {
        var job = jobService.create(JobType.SYNTHESIZE, id, 0);
        jobService.execute(job.getId(), consumer ->
                ttsService.synthesizeNovel(id, onlyPending,
                        (p, t, m) -> consumer.accept(p, t, m)));
        return ResponseEntity.ok(toDto(job));
    }

    private ApiModels.JobDto toDto(JobTask j) {
        return new ApiModels.JobDto(j.getId(), j.getType().name(), j.getRefId(),
                j.getStatus().name(), j.getProgress(), j.getTotal(), j.getMessage(),
                j.getErrorMessage());
    }
}
