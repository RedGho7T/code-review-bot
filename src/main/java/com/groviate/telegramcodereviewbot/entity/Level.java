package com.groviate.telegramcodereviewbot.entity;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

@Getter
@Slf4j
public enum Level {

    MINIBRO(1, "Микрочелик", "👶",
            Arrays.asList(
                    new Task("minibro_1",
                            "1️⃣ Первое задание",
                            25,
                            "Поднятие проекта"),
                    new Task("minibro_2",
                            "2️⃣ Второе задание",
                            25,
                            "Проведение Code review (15-20 раз)"),
                    new Task("minibro_3",
                            "3️⃣ Третье задание",
                            25,
                            "Написание Unit-test'ов и Integration-test'ов"),
                    new Task("minibro_4",
                            "4️⃣ Четвертое задание",
                            25,
                            "Java advance part1 и part2")
            ),
            "Для перехода на следующий уровень выполни все 4 задания!"
    ),

    BOSS(2, "Босс этого проекта", "👨\u200D💼",
            Arrays.asList(
                    new Task("boss_1", "1️⃣ Придумывется",
                            50,
                            "Ну вы так-то совесть имейте, я еще не сделал"),
                    new Task("boss_2", "2️⃣ Придумывется",
                            50,
                            "Ну вы так-то совесть имейте, я еще не сделал")
            ),
            "Познать дзен и преисполниться в своем познании"
    );

    private final int number;
    private final String name;
    private final String emoji;
    private final List<Task> tasks;
    private final String unlockCondition;

    Level(int number, String name, String emoji, List<Task> tasks, String unlockCondition) {
        this.number = number;
        this.name = name;
        this.emoji = emoji;
        this.tasks = tasks;
        this.unlockCondition = unlockCondition;
    }

    public static Level getByNumber(int number) {
        return Arrays.stream(values())
                .filter(level -> level.getNumber() == number)
                .findFirst()
                .orElse(MINIBRO);
    }

    public Task getTaskById(String taskId) {
        return tasks.stream()
                .filter(task -> task.id().equals(taskId))
                .findFirst()
                .orElse(null);
    }

    public String getTaskIdByButtonText(String buttonText) {
        if (buttonText == null || buttonText.isBlank()) {
            return null;
        }

        String cleanText = buttonText
                .replace("📝", "")
                .replace("✅", "")
                .trim();

        log.debug("Resolve taskId: level={}, buttonText='{}', cleanText='{}'",
                getName(), buttonText, cleanText);

        for (Task task : tasks) {
            if (cleanText.equals(task.name())) {
                return task.id();
            }
        }

        for (Task task : tasks) {
            if (cleanText.contains(task.name()) || task.name().contains(cleanText)) {
                return task.id();
            }
        }

        return null;
    }

    public record Task(String id, String name, int points, String shortDescription) {
    }
}