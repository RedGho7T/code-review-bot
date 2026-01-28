package com.groviate.telegramcodereviewbot.repository;


import com.groviate.telegramcodereviewbot.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByChatId(Long chatId);
    boolean existsByChatId(Long chatId);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.chatId = :chatId AND u.maxUnlockedLevel >= :level")
    boolean isLevelAccessible(@Param("chatId") Long chatId, @Param("level") Integer level);
}