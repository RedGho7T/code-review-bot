package com.groviate.telegramcodereviewbot.repository;

import com.groviate.telegramcodereviewbot.entity.UserScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface UserScoreRepository extends JpaRepository<UserScore, Long> {


    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from UserScore us where us.user.id = :userId")
    void deleteByUserId(Long userId);

    @Query(value = """
        SELECT 
            u.id as userId,
            u.telegram_username as username,
            u.first_name as firstName,
            COALESCE(SUM(
                CASE 
                    WHEN us.created_at >= :cutoffTime THEN us.points * 1.0  -- Вес 1.0 (можно увеличить)
                    ELSE us.points  -- Стандартный вес для старых очков
                END
            ), 0) as weightedScore,
            MAX(us.created_at) as lastScoreTime
        FROM users u
        LEFT JOIN user_scores us ON u.id = us.user_id
        GROUP BY u.id, u.telegram_username, u.first_name
        ORDER BY weightedScore DESC, lastScoreTime DESC NULLS LAST
        LIMIT 5
        """, nativeQuery = true)
    List<Object[]> findTop5WeightedLeaderboardNative(@Param("cutoffTime") LocalDateTime cutoffTime);
}
