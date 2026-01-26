package com.groviate.telegramcodereviewbot.repository;


import com.groviate.telegramcodereviewbot.entity.CompletedTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompletedTaskRepository extends JpaRepository<CompletedTask, Long> {
    List<CompletedTask> findByUserId(Long userId);

    @Query("SELECT ct FROM CompletedTask ct WHERE ct.user.chatId = :chatId AND ct.taskId = :taskId")
    Optional<CompletedTask> findByChatIdAndTaskId(@Param("chatId") Long chatId,
                                                  @Param("taskId") String taskId);

    @Query("SELECT COUNT(ct) FROM CompletedTask ct WHERE ct.user.chatId = :chatId")
    int countCompletedTasksByChatId(@Param("chatId") Long chatId);
}