package com.noveltts.domain.repository;

import com.noveltts.domain.entity.JobTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobTaskRepository extends JpaRepository<JobTask, Long> {

    List<JobTask> findTop20ByOrderByIdDesc();
}
