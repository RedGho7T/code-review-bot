package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.entity.Level;
import com.groviate.telegramcodereviewbot.entity.User;
import com.groviate.telegramcodereviewbot.repository.UserRepository;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserProgressService {

    private final UserRepository userRepository;

    /**
     * Получить пользователя 
     */
    @Transactional
    public User getOrCreateUser(Long chatId, String username, String firstName, String lastName) {
        Optional<User> existingUser = userRepository.findByChatId(chatId);

        if (existingUser.isPresent()) {
            User user = existingUser.get();
            user.setLastActivityAt(LocalDateTime.now());
            return userRepository.save(user);
        }

        User newUser = User.builder()
                .chatId(chatId)
                .telegramUsername(username)
                .firstName(firstName)
                .lastName(lastName)
                .currentLevel(1)
                .maxUnlockedLevel(1)
                .totalPoints(0)
                .createdAt(LocalDateTime.now())
                .lastActivityAt(LocalDateTime.now())
                .build();

        log.info("Создан новый пользователь: {}", newUser);
        return userRepository.save(newUser);
    }

    /**
     * Проверить доступность уровня
     */
    public boolean isLevelAccessible(Long chatId, int levelNumber) {
        return userRepository.findByChatId(chatId)
                .map(user -> user.isLevelUnlocked(levelNumber))
                .orElse(false);
    }

    /**
     * Получить текущий уровень пользователя
     */
    public Level getCurrentLevel(Long chatId) {
        return userRepository.findByChatId(chatId)
                .map(user -> Level.getByNumber(user.getCurrentLevel()))
                .orElse(Level.MINIBRO);
    }

    /**
     * Проверить выполнение задания
     */
    public boolean isTaskCompleted(Long chatId, String taskId) {
        return userRepository.findByChatId(chatId)
                .map(user -> user.hasCompletedTask(taskId))
                .orElse(false);
    }

    /**
     * Выполнить задание
     */
    @Transactional
    public TaskCompletionResult completeTask(Long chatId, String taskId) {
        Optional<User> userOpt = userRepository.findByChatId(chatId);
        if (userOpt.isEmpty()) {
            return TaskCompletionResult.error("Пользователь не найден");
        }

        User user = userOpt.get();
        Level currentLevel = Level.getByNumber(user.getCurrentLevel());
        Level.Task task = currentLevel.getTaskById(taskId);

        if (task == null) {
            return TaskCompletionResult.error("Задание не найдено на текущем уровне");
        }

        if (user.hasCompletedTask(taskId)) {
            return TaskCompletionResult.error("Задание уже выполнено");
        }

        // Отмечаем задание как выполненное
        user.markTaskCompleted(taskId, task.getPoints(), task.getName());
        userRepository.save(user);

        // Проверяем, можно ли разблокировать следующий уровень
        boolean levelUnlocked = false;
        if (user.canUnlockNextLevel()) {
            user.unlockNextLevel();
            userRepository.save(user);
            levelUnlocked = true;
        }

        return TaskCompletionResult.success(task, levelUnlocked, user);
    }

    /**
     * Получить статистику пользователя
     */
    public String getUserStats(Long chatId) {
        return userRepository.findByChatId(chatId).map(user -> {
            Level currentLevel = Level.getByNumber(user.getCurrentLevel());
            long completedTasksInLevel = currentLevel.getTasks().stream()
                    .filter(task -> user.hasCompletedTask(task.getId()))
                    .count();

            return String.format(
                    "🏆 Твоя статистика:\n\n" +
                            "📊 Уровень: %d/%d\n" +
                            "🎯 Текущий: %s %s\n" +
                            "✅ Заданий выполнено: %d/%d\n" +
                            "⭐ Очки: %d\n" +
                            "🔓 Доступно уровней: %d\n\n" +
                            "💡 Следующий уровень: %s",
                    user.getCurrentLevel(), Level.values().length,
                    currentLevel.getEmoji(), currentLevel.getName(),
                    completedTasksInLevel, currentLevel.getTasks().size(),
                    user.getTotalPoints(),
                    user.getMaxUnlockedLevel(),
                    user.canUnlockNextLevel() ? "Доступен!" : currentLevel.getUnlockCondition()
            );
        }).orElse("❌ Пользователь не найден");
    }

    /**
     * Результат выполнения задания
     */
    @Getter
    @AllArgsConstructor
    public static class TaskCompletionResult {
        private final boolean success;
        private final String message;
        private final Level.Task task;
        private final boolean levelUnlocked;
        private final User user;

        public static TaskCompletionResult success(Level.Task task, boolean levelUnlocked, User user) {
            String message = String.format(
                    "✅ Задание выполнено!\n\n" +
                            "🎯 %s\n" +
                            "⭐ +%d очков\n\n" +
                            "%s",
                    task.getName(), task.getPoints(),
                    levelUnlocked ? "🎉 Новый уровень разблокирован!" : ""
            );
            return new TaskCompletionResult(true, message, task, levelUnlocked, user);
        }

        public static TaskCompletionResult error(String errorMessage) {
            return new TaskCompletionResult(false, "❌ " + errorMessage, null, false, null);
        }
    }

    /**
     * Получить количество очков пользователя
     */
    public int getTotalPoints(Long chatId) {
        return userRepository.findByChatId(chatId)
                .map(User::getTotalPoints)
                .orElse(0);
    }
}