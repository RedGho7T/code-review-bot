package com.groviate.telegramcodereviewbot.repository;

import com.groviate.telegramcodereviewbot.entity.CompletedTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CompletedTaskRepository extends JpaRepository<CompletedTask, Long> {

    @Query("SELECT CASE WHEN COUNT(ct) > 0 THEN true ELSE false END " +
            "FROM CompletedTask ct WHERE ct.user.chatId = :chatId AND ct.taskId = :taskId")
    boolean existsByChatIdAndTaskId(@Param("chatId") Long chatId,
                                    @Param("taskId") String taskId);

    @Query("SELECT COUNT(ct) FROM CompletedTask ct " +
            "WHERE ct.user.chatId = :chatId AND ct.taskId IN :taskIds")
    long countByChatIdAndTaskIdIn(@Param("chatId") Long chatId,
                                  @Param("taskIds") java.util.Collection<String> taskIds);
}