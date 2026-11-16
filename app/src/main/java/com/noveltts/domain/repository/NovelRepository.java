package com.noveltts.domain.repository;

import com.noveltts.domain.entity.Novel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NovelRepository extends JpaRepository<Novel, Long> {

    List<Novel> findAllByOrderByIdDesc();

    List<Novel> findByTitleContainingIgnoreCase(String keyword);
}
