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

/**
 * Сервис управления прогрессом пользователя: регистрация/обновление пользователя,
 * выполнение заданий, расчёт статистики и начисление очков.
 *
 * <p>Все операции, меняющие состояние пользователя, выполняются в транзакции.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserProgressService {

    private static final int ADMIN_BONUS_POINTS = 1000;

    private static final String USER_NOT_FOUND = "❌ Пользователь не найден";

    private static final String USER_STATS_TEMPLATE = """
            🏆 Твоя статистика:
            
            📊 Уровень: %d/%d
            🎯 Текущий: %s %s
            ✅ Заданий выполнено: %d/%d
            ⭐ Очки: %d
            🔓 Доступно уровней: %d
            
            💡 Следующий уровень: %s
            """;

    private final UserRepository userRepository;
    private final UserScoreRepository userScoreRepository;
    private final CompletedTaskRepository completedTaskRepository;

    /**
     * Возвращает пользователя по chatId или создаёт нового, если его ещё нет.
     * Обновляет username/firstName при изменении и фиксирует lastActivityAt.
     */
    @Transactional
    public User getOrCreateUser(Long chatId, String telegramUsername, String firstName) {
        log.info("getOrCreateUser called: chatId={}, username='{}', firstName='{}'",
                chatId, telegramUsername, firstName);

        // Если все null - создаем "анонимного" пользователя для группы
        if (telegramUsername == null && firstName == null) {
            log.info("Creating anonymous user for group chatId={}", chatId);
        }

        return userRepository.findByChatId(chatId)
                .map(existing -> {
                    boolean updated = false;

                    // Обновляем только если новые данные не null и не пустые
                    if (telegramUsername != null && !telegramUsername.trim().isEmpty()
                            && !telegramUsername.equals(existing.getTelegramUsername())) {
                        log.info("Updating username for chatId={}: {} -> {}",
                                chatId, existing.getTelegramUsername(), telegramUsername);
                        existing.setTelegramUsername(telegramUsername);
                        updated = true;
                    }

                    if (firstName != null && !firstName.trim().isEmpty()
                            && !firstName.equals(existing.getFirstName())) {
                        log.info("Updating firstName for chatId={}: {} -> {}",
                                chatId, existing.getFirstName(), firstName);
                        existing.setFirstName(firstName);
                        updated = true;
                    }

                    existing.setLastActivityAt(LocalDateTime.now());

                    if (updated) {
                        log.info("Saving updated user for chatId={}", chatId);
                        return userRepository.save(existing);
                    }

                    return existing;
                })
                .orElseGet(() -> {
                    log.info("Creating new user: chatId={}, username='{}', firstName='{}'",
                            chatId, telegramUsername, firstName);

                    User created = User.builder()
                            .chatId(chatId)
                            .telegramUsername(telegramUsername != null ? telegramUsername : "")
                            .firstName(firstName != null ? firstName : "")
                            .currentLevel(1)
                            .maxUnlockedLevel(1)
                            .totalPoints(0)
                            .createdAt(LocalDateTime.now())
                            .lastActivityAt(LocalDateTime.now())
                            .build();

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
        return completedTaskRepository.existsByChatIdAndTaskId(chatId, taskId);
    }

    /**
     * Полный сброс прогресса.
     * Ключевой момент:
     * 1) Чистим БД (scores/tasks)
     * 2) Чистим коллекции в сущности (иначе JPA может попытаться пересоздать удаленные записи)
     * 3) Сбрасываем поля прогресса
     * <p>
     * На данный момент (29.01) только сброс очков юзера, не более, так же команда убрана из /help
     * поскольку является админской и необходима только для более адекватного тестирования функций
     */
    @Transactional
    public void resetUser(Long chatId) {
        User user = getUserOrThrow(chatId);
        user.setLastActivityAt(LocalDateTime.now());

        UserScore score = new UserScore();
        score.setUser(user);
        score.setPoints(0);

        userScoreRepository.save(score);

        user.setTotalPoints(0);

        userRepository.save(user);
    }

    /**
     * Админское начисление очков (+1000).
     * Делаем через добавление в user.scores + сохранение user (cascade сохранит score).
     */
    @Transactional
    public void upScore(Long chatId) {
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
        userRepository.save(user);
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
        if (completedTaskRepository.existsByChatIdAndTaskId(chatId, taskId)) {
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
                .map(user -> buildUserStats(chatId, user))
                .orElse(USER_NOT_FOUND);
    }

    private String buildUserStats(Long chatId, User user) {
        Level currentLevel = Level.getByNumber(user.getCurrentLevel());

        long completedTasksInLevel = countCompletedTasksInLevel(chatId, currentLevel);

        String nextLevelHint = user.canUnlockNextLevel()
                ? "Доступен!"
                : currentLevel.getUnlockCondition();

        return USER_STATS_TEMPLATE.formatted(
                user.getCurrentLevel(),
                Level.values().length,
                currentLevel.getEmoji(),
                currentLevel.getName(),
                completedTasksInLevel,
                currentLevel.getTasks().size(),
                user.getTotalPoints(),
                user.getMaxUnlockedLevel(),
                nextLevelHint
        );
    }

    private long countCompletedTasksInLevel(Long chatId, Level level) {
        var taskIds = level.getTasks().stream()
                .map(Level.Task::id)
                .toList();

        return completedTaskRepository.countByChatIdAndTaskIdIn(chatId, taskIds);
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
