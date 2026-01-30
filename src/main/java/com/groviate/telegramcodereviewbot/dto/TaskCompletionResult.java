package com.groviate.telegramcodereviewbot.dto;

import com.groviate.telegramcodereviewbot.entity.Level;

/**
 * DTO результата выполнения задания.
 * Важно: не тащим сюда JPA-сущность User, чтобы сервис не “протекал” наружу сущностями.
 */
public record TaskCompletionResult(
        boolean success,
        String message,
        Level.Task task,
        boolean levelUnlocked,
        Integer newLevelNumber
) {

    public static TaskCompletionResult success(Level.Task task, boolean levelUnlocked, Integer newLevelNumber) {
        String text = String.format("""
                        ✅ Задание выполнено!
                        
                        🎯 %s
                        ⭐ +%d очков
                        
                        %s
                        """,
                task.name(),
                task.points(),
                levelUnlocked ? "🎉 Новый уровень разблокирован!" : ""
        );

        return new TaskCompletionResult(true, text, task, levelUnlocked, newLevelNumber);
    }

    public static TaskCompletionResult error(String errorMessage) {
        return new TaskCompletionResult(false, "❌ "
                + errorMessage, null, false, null);
    }
}