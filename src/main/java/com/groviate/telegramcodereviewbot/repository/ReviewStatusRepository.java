package com.groviate.telegramcodereviewbot.repository;

import com.groviate.telegramcodereviewbot.entity.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReviewStatusRepository extends JpaRepository<ReviewStatus, Long> {

    Optional<ReviewStatus> findByProjectIdAndMrIid(Integer projectId, Integer mrIid);
}