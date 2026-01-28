package com.groviate.telegramcodereviewbot.service;


import com.groviate.telegramcodereviewbot.dto.LeaderboardEntry;
import com.groviate.telegramcodereviewbot.repository.UserScoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private final UserScoreRepository userScoreRepository;

    /**
     * Лидерборд с учетом свежих очков (за последние 24 часа)
     */
    @Transactional(readOnly = true)
    public List<LeaderboardEntry> getTop5WeightedLeaderboard() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusHours(24);
        List<Object[]> results = userScoreRepository.findTop5WeightedLeaderboardNative(cutoffTime);
        return mapToLeaderboardEntries(results);
    }

    /**
     * Получить форматированный лидерборд как строку (для Telegram)
     */
    @Transactional(readOnly = true)
    public String getFormattedLeaderboard() {
        List<LeaderboardEntry> entries = getTop5WeightedLeaderboard();
        return formatLeaderboardAsString(entries);
    }

    // Вспомогательные методы
    private List<LeaderboardEntry> mapToLeaderboardEntries(List<Object[]> results) {
        List<LeaderboardEntry> entries = new ArrayList<>();

        for (int i = 0; i < results.size(); i++) {
            Object[] row = results.get(i);
            LeaderboardEntry entry = new LeaderboardEntry(
                    ((Number) row[0]).longValue(),
                    (String) row[1],
                    (String) row[2],
                    ((Number) row[3]).intValue(),
                    row[4] != null ? ((java.sql.Timestamp) row[4]).toLocalDateTime() : null,
                    i + 1
            );
            entries.add(entry);
        }

        return entries;
    }

    private String formatLeaderboardAsString(List<LeaderboardEntry> entries) {
        if (entries.isEmpty()) {
            return "🏆 Пока никто не получил очки! Будьте первым! 🚀";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🏆 *ТОП ИГРОКОВ* 🏆\n\n");

        for (LeaderboardEntry entry : entries) {
            sb.append(entry.toTelegramString()).append("\n\n");
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return "LeaderboardService{" +
                "userScoreRepository=" + userScoreRepository +
                '}';
    }
}