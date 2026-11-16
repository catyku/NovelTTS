package com.noveltts.service;

import com.noveltts.domain.entity.JobTask;
import com.noveltts.domain.enums.JobStatus;
import com.noveltts.domain.enums.JobType;
import com.noveltts.domain.repository.JobTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


/**
 * 非同步 job 管理：建立 job → 背景執行 → 更新進度。
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobTaskRepository jobRepository;

    public JobService(JobTaskRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    /**
     * 建立 job 記錄。
     * 用 REQUIRES_NEW 讓 job 記錄獨立提交：若呼叫端有外層 transaction，
     * job 在外層提交前是看不到的，@Async 執行緒會讀不到而靜默結束，
     * 導致 job 卡在 PENDING 不動。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JobTask create(JobType type, Long refId, int total) {
        JobTask job = new JobTask();
        job.setType(type);
        job.setRefId(refId);
        job.setStatus(JobStatus.PENDING);
        job.setProgress(0);
        job.setTotal(total);
        return jobRepository.save(job);
    }

    /** 泛用背景執行：body 執行過程中透過 progressConsumer 回報進度。 */
    @Async
    public void execute(Long jobId, JobBody body) {
        try {
            JobTask job = jobRepository.findById(jobId).orElse(null);
            if (job == null) {
                // 讀不到 job 代表它尚未被提交（外層 transaction 未結束）。
                // 這時不能靜默結束，否則 job 會永遠卡在 PENDING。
                log.error("Job {} 尚未可見，無法執行。請檢查 job 建立時的事務邊界。", jobId);
                return;
            }
            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(java.time.OffsetDateTime.now());
            jobRepository.save(job);

            body.run((progress, total, message) -> {
                JobTask j = jobRepository.findById(jobId).orElse(null);
                if (j != null) {
                    j.setProgress(progress);
                    j.setTotal(total);
                    j.setMessage(message);
                    jobRepository.save(j);
                }
            });

            JobTask done = jobRepository.findById(jobId).orElse(null);
            if (done != null) {
                done.setStatus(JobStatus.SUCCESS);
                done.setFinishedAt(java.time.OffsetDateTime.now());
                jobRepository.save(done);
            }
        } catch (Exception e) {
            log.error("Job {} 執行失敗", jobId, e);
            JobTask job = jobRepository.findById(jobId).orElse(null);
            if (job != null) {
                job.setStatus(JobStatus.FAILED);
                job.setErrorMessage(e.getMessage());
                job.setFinishedAt(java.time.OffsetDateTime.now());
                jobRepository.save(job);
            }
        }
    }

    /** job 主體。 */
    public interface JobBody {
        void run(ProgressConsumer consumer);
    }

    /** 進度回報。 */
    public interface ProgressConsumer {
        void accept(int progress, int total, String message);
    }
}
