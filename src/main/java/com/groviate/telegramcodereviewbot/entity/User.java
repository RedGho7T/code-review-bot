package com.groviate.telegramcodereviewbot.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users", schema = "public")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", unique = true, nullable = false)
    private Long chatId;

    @Column(name = "telegram_username")
    private String telegramUsername;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "current_level")
    @Builder.Default
    private Integer currentLevel = 1;

    @Column(name = "max_unlocked_level")
    @Builder.Default
    private Integer maxUnlockedLevel = 1;

    @Column(name = "total_points")
    @Builder.Default
    private Integer totalPoints = 0;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "last_activity_at")
    @Builder.Default
    private LocalDateTime lastActivityAt = LocalDateTime.now();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Set<CompletedTask> completedTasks = new HashSet<>();

    /**
     * Проверить, выполнена ли задача
     */
    @Transient
    public boolean hasCompletedTask(String taskId) {
        return completedTasks.stream()
                .anyMatch(task -> task.getTaskId().equals(taskId));
    }

    /**
     * Проверить, доступен ли уровень
     */
    @Transient
    public boolean isLevelUnlocked(int levelNumber) {
        return maxUnlockedLevel >= levelNumber;
    }

    /**
     * Отметить задачу как выполненную (сразу начисляем очки)
     */
    public void markTaskCompleted(String taskId, int points, String taskName) {
        // Проверяем, не выполнена ли уже эта задача
        if (hasCompletedTask(taskId)) {
            return;
        }

        CompletedTask completedTask = CompletedTask.builder()
                .user(this)
                .taskId(taskId)
                .taskName(taskName)
                .points(points)
                .completedAt(LocalDateTime.now())
                .build();

        completedTasks.add(completedTask);
        totalPoints += points;
        lastActivityAt = LocalDateTime.now();
    }

    /**
     * Проверить, можно ли разблокировать следующий уровень
     */
    @Transient
    public boolean canUnlockNextLevel() {
        Level currentLevelEnum = Level.getByNumber(currentLevel);
        if (currentLevelEnum == null) return false;

        return currentLevelEnum.getTasks().stream()
                .allMatch(task -> hasCompletedTask(task.getId()));
    }

    /**
     * Разблокировать следующий уровень
     */
    public void unlockNextLevel() {
        if (canUnlockNextLevel() && maxUnlockedLevel < Level.values().length) {
            maxUnlockedLevel++;
            currentLevel = maxUnlockedLevel;
        }
    }
}