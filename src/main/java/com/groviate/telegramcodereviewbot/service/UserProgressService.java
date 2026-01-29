package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.dto.TaskCompletionResult;
import com.groviate.telegramcodereviewbot.entity.Level;
import com.groviate.telegramcodereviewbot.entity.User;
import com.groviate.telegramcodereviewbot.entity.UserScore;
import com.groviate.telegramcodereviewbot.repository.CompletedTaskRepository;
import com.groviate.telegramcodereviewbot.repository.UserRepository;
import com.groviate.telegramcodereviewbot.repository.UserScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserProgressService {

    private static final int ADMIN_BONUS_POINTS = 1000;

    private final UserRepository userRepository;
    private final UserScoreRepository userScoreRepository;
    private final CompletedTaskRepository completedTaskRepository;

    @Transactional
    public User getOrCreateUser(Long chatId, String username, String firstName) {
        return userRepository.findByChatId(chatId)
                .map(existing -> {
                    // Обновляем данные, если изменились (Telegram иногда меняет username)
                    if (username != null && !username.equals(existing.getTelegramUsername())) {
                        existing.setTelegramUsername(username);
                    }
                    if (firstName != null && !firstName.equals(existing.getFirstName())) {
                        existing.setFirstName(firstName);
                    }

                    existing.setLastActivityAt(LocalDateTime.now());
                    return userRepository.save(existing);
                })
                .orElseGet(() -> {
                    User created = User.builder()
                            .chatId(chatId)
                            .telegramUsername(username)
                            .firstName(firstName)
                            .currentLevel(1)
                            .maxUnlockedLevel(1)
                            .totalPoints(0)
                            .createdAt(LocalDateTime.now())
                            .lastActivityAt(LocalDateTime.now())
                            .build();

                    log.info("Создан новый пользователь: chatId={}, username={}, firstName={}", chatId, username, firstName);
                    return userRepository.save(created);
                });
    }

    /**
     * Проверить доступность уровня.
     * Оптимизация: используем запрос репозитория, не грузим сущность целиком.
     */
    @Transactional(readOnly = true)
    public boolean isLevelAccessible(Long chatId, int levelNumber) {
        return userRepository.isLevelAccessible(chatId, levelNumber);
    }

    /**
     * Проверить выполнение задания.
     * Оптимизация: проверяем напрямую через CompletedTaskRepository (не грузим User + EAGER коллекцию).
     */
    @Transactional(readOnly = true)
    public boolean isTaskCompleted(Long chatId, String taskId) {
        return completedTaskRepository.findByChatIdAndTaskId(chatId, taskId).isPresent();
    }

    /**
     * Полный сброс прогресса.
     * Ключевой момент:
     * 1) Чистим БД (scores/tasks)
     * 2) Чистим коллекции в сущности (иначе JPA может попытаться пересоздать удаленные записи)
     * 3) Сбрасываем поля прогресса
     */
    @Transactional
    public User resetUser(Long chatId) {
        User user = getUserOrThrow(chatId);

        // 1) удаляем историю и задачи в БД
        completedTaskRepository.deleteByUserId(user.getId());
        userScoreRepository.deleteByUserId(user.getId());

        // 2) чистим in-memory коллекции, чтобы не было “воскрешения” через cascade
        if (user.getCompletedTasks() != null) {
            user.getCompletedTasks().clear();
        }
        if (user.getScores() != null) {
            user.getScores().clear();
        }

        // 3) сброс прогресса
        user.setCurrentLevel(1);
        user.setMaxUnlockedLevel(1);
        user.setTotalPoints(0);
        user.setLastActivityAt(LocalDateTime.now());

        // (не обязательно) маркер “reset” в историю, 0 очков, на рейтинг не влияет
        user.getScores().add(UserScore.builder()
                .user(user)
                .points(0)
                .sourceType("reset")
                .sourceId("manual")
                .build());

        log.info("Progress reset: chatId={}, userId={}", chatId, user.getId());
        return userRepository.save(user);
    }

    /**
     * Админское начисление очков (+1000).
     * Делаем через добавление в user.scores + сохранение user (cascade сохранит score).
     */
    @Transactional
    public User upScore(Long chatId) {
        User user = getUserOrThrow(chatId);

        user.getScores().add(UserScore.builder()
                .user(user)
                .points(ADMIN_BONUS_POINTS)
                .sourceType("admin_bonus")
                .sourceId("upscore")
                .build());

        user.setTotalPoints(user.getTotalPoints() + ADMIN_BONUS_POINTS);
        user.setLastActivityAt(LocalDateTime.now());

        log.info("Admin bonus: chatId={}, userId={}, bonus={}", chatId, user.getId(), ADMIN_BONUS_POINTS);
        return userRepository.save(user);
    }

    /**
     * Выполнить задание.
     * Возвращаем DTO (вынесено отдельно), без возврата JPA User наружу.
     */
    @Transactional
    public TaskCompletionResult completeTask(Long chatId, String taskId) {
        User user = userRepository.findByChatId(chatId).orElse(null);
        if (user == null) {
            return TaskCompletionResult.error("Пользователь не найден");
        }

        Level currentLevel = Level.getByNumber(user.getCurrentLevel());
        if (currentLevel == null) {
            return TaskCompletionResult.error("Некорректный уровень пользователя");
        }

        Level.Task task = currentLevel.getTaskById(taskId);
        if (task == null) {
            return TaskCompletionResult.error("Задание не найдено на текущем уровне");
        }

        // Быстрая проверка через БД (не через EAGER коллекцию)
        if (isTaskCompleted(chatId, taskId)) {
            return TaskCompletionResult.error("Задание уже выполнено");
        }

        // 1) отмечаем задачу (внутри User создастся CompletedTask и UserScore, totalPoints увеличится)
        user.markTaskCompleted(taskId, task.points(), task.name());

        // 2) проверяем unlock уровня
        boolean levelUnlocked = false;
        Integer newLevelNumber = null;

        if (user.canUnlockNextLevel()) {
            user.unlockNextLevel();
            levelUnlocked = true;
            newLevelNumber = user.getCurrentLevel();
        }

        // 3) сохраняем один раз
        userRepository.save(user);

        return TaskCompletionResult.success(task, levelUnlocked, newLevelNumber);
    }

    /**
     * Статистика пользователя.
     * Sonar: заменили конкатенацию на text block.
     */
    @Transactional(readOnly = true)
    public String getUserStats(Long chatId) {
        return userRepository.findByChatId(chatId)
                .map(user -> {
                    Level currentLevel = Level.getByNumber(user.getCurrentLevel());
                    if (currentLevel == null) {
                        return "❌ Некорректный уровень пользователя";
                    }

                    long completedTasksInLevel = currentLevel.getTasks().stream()
                            .filter(task -> isTaskCompleted(chatId, task.id()))
                            .count();

                    return String.format("""
                            🏆 Твоя статистика:

                            📊 Уровень: %d/%d
                            🎯 Текущий: %s %s
                            ✅ Заданий выполнено: %d/%d
                            ⭐ Очки: %d
                            🔓 Доступно уровней: %d

                            💡 Следующий уровень: %s
                            """,
                            user.getCurrentLevel(),
                            Level.values().length,
                            currentLevel.getEmoji(),
                            currentLevel.getName(),
                            completedTasksInLevel,
                            currentLevel.getTasks().size(),
                            user.getTotalPoints(),
                            user.getMaxUnlockedLevel(),
                            user.canUnlockNextLevel() ? "Доступен!" : currentLevel.getUnlockCondition()
                    );
                })
                .orElse("❌ Пользователь не найден");
    }

    private User getUserOrThrow(Long chatId) {
        return userRepository.findByChatId(chatId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: chatId=" + chatId));
    }

    @Transactional(readOnly = true)
    public int getUserTotalPoints(Long chatId) {
        return userRepository.findByChatId(chatId).map(User::getTotalPoints).orElse(0);
    }
}
