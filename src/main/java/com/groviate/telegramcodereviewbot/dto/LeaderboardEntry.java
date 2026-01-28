package com.groviate.telegramcodereviewbot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaderboardEntry {
    private Long userId;
    private String username;
    private String firstName;
    private Integer totalScore;
    private LocalDateTime lastScoreTime;
    private Integer rank;

    @Override
    public String toString() {

        String displayName = firstName != null && !firstName.isEmpty()
                ? firstName
                : (username != null && !username.isEmpty() ? username : "Пользователь");

        return String.format("🏆 %d. %s | @%s - %d очков \n",
                rank, displayName, username , totalScore);
    }


    // Вспомогательные методы
    private String getDisplayName() {
        if (firstName != null && !firstName.isEmpty()) {
            return firstName;
        } else if (username != null && !username.isEmpty()) {
            return "@" + username;
        } else {
            return "Анонимный пользователь";
        }
    }

    private String getMedalEmoji() {
        switch (rank) {
            case 1: return "🥇";
            case 2: return "🥈";
            case 3: return "🥉";
            default: return "▫️";
        }
    }

    // Метод для Telegram-форматирования
    public String toTelegramString() {
        String medal = getMedalEmoji();
        String displayName = getDisplayName();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s *%d.* %s - *%d* очков",
                medal, rank, displayName, totalScore));

        // Добавляем username, если есть
        if (username != null && !username.isEmpty()) {
            sb.append(String.format("\n   👤 @%s", username));
        }

        return sb.toString();
    }
}


