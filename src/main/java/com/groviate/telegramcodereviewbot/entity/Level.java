package com.groviate.telegramcodereviewbot.entity;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Getter
public enum Level {

    MINIBRO(1, "Микрочелик", "👶",
            Arrays.asList(
                    new Task("minibro_1", "1️⃣ Первое задание", 25,
                            "Поднятие проекта"),
                    new Task("minibro_2", "2️⃣ Второе задание", 25,
                            "Проведение Code review (15-20 раз)"),
                    new Task("minibro_3", "3️⃣ Третье задание", 25,
                            "Написание Unit-test'ов и Integration-test'ов"),
                    new Task("minibro_4", "4️⃣ Четвертое задание", 25,
                            "Java advance part1 и part2")
            ),
            "Для перехода на следующий уровень выполни все 4 задания!",
            Map.of(
                    "minibro_1", "Поднятие проекта.   \n" +
                            "Эпик: https://jora.kata.academy/browse/HAB-1490 \n\n" +
                            "Для решения этой задачи, маякни что бы добавил тебя в группу, у тебя появится доступ к соответствующим репам микросервисов\n" +
                            "\n" +
                            "нужно проверить что сервис работает локально. Описать доработки и правки, если необходимы\n" +
                            "\n" +
                            "доступ в группу и репы проверь по этой ссылке https://gitlab.groviate.com/habit-tracker\n" +
                            "если видишь проект- значит доступ есть, ищи свой микросервис и приступай к задаче. Удачи! Все получится!\n" +
                            "\n" +
                            "Нужно идти по гайду для запуска проекта в полирепах:\n" +
                            "https://wika.kata.academy/pages/viewpage.action?pageId=47317804&moved=true\n" +
                            "\n" +
                            "Дедлайн: 8 часов",
                    "minibro_2", "Проведение Code review (15-20 раз)\n" +
                            "Эпик: https://jora.kata.academy/browse/HAB-550 \n\n" +
                            "В этой таске ты освоишь САМЫЙ ВАЖНЫЙ НАВЫК на реальной РАБОТЕ!\n" +
                            "\n" +
                            "как проводить код ревью\n" +
                            "\n" +
                            "https://wika.kata.academy/pages/viewpage.action?pageId=40829516\n" +
                            "\n" +
                            "Добавь себя в эпик- задачу. Назначь на себя задачу. В комментария к задаче прикрепляй ссылки на МР где провел ревью.\n" +
                            "Cделал- значит изучил и запустил код, оставил комментарии и замечания по доработкам, а не просто прожал \"approve\"\n" +
                            "\n" +
                            "Нужно провести 15-20 код ревью МР. Так вы получите максимальное количество практики.\n" +
                            "Значит в сабтаске - вашей задаче, в комментарии будет около 20 ссылок на МР.\n" +
                            "\n" +
                            "Дедлайн: время вашего обучения на проекте",
                    "minibro_3", "Написание Unit-test'ов и Integration-test'ов\n" +
                            "Эпик: https://jora.kata.academy/browse/HAB-499\n\n" +
                            "Для тестирования применяем AssertJ, Testcontainers.\n" +
                            "JUnit - стараемся не применять.\n" +
                            "\n" +
                            "Тут можете ознакомиться кто делает смежную с вами задачу.\n" +
                            "\n" +
                            "Ваша совместная работа- тренировка командного взаимодействия и часть реального опыта разработки.\n" +
                            "\n" +
                            "Написать юнит тесты для eureka-server, habit-service, payment-service.\n" +
                            "Написать интеграционные тесты eureka-server, notifcation-service, habit-server, payment-service",
                    "minibro_4", "Зайди на этот Эпик: https://jora.kata.academy/browse/HAB-570\n " +
                            "Заведи на себя 2 задачи: java advance part1 и part2\n\n" +
                            "Эти таски НЕ ОБЯЗАТЕЛЬНЫЕ, но если ты их пройдёшь, то сможешь больше рассказать о себе на интервью.\n" +
                            "Так что недооценивать их уж точно не стоит и при наличии свободного времени и(или) отсутствии задачь " +
                            "не бойся делать именно то, что находится в данных задачах\n\n" +
                            "\uD83E\uDEF5 Так держать! Дорогу осилит идущий! \uD83E\uDEF5"
            )),

    BOSS(2, "Босс этого проекта", "👨‍💼",
            Arrays.asList(
                    new Task("boss_1", "1️⃣ Придумывется", 50,
                            "Ну вы так-то совесть имейте, я еще не сделал"),
                    new Task("boss_2", "2️⃣ Придумывется", 50,
                            "Ну вы так-то совесть имейте, я еще не сделал")
            ),
            "Познать дзен и преисполниться в своем познании",
            Map.of(
                    "boss_1", "Ну вы так-то совесть имейте, я еще не сделал \uD83D\uDD60",
                    "boss_2", "Ну вы так-то совесть имейте, я еще не сделал \uD83D\uDD60"
            ));

    private final int number;
    private final String name;
    private final String emoji;
    private final List<Task> tasks;
    private final String unlockCondition;
    private final Map<String, String> taskDescriptions;

    Level(int number, String name, String emoji, List<Task> tasks,
          String unlockCondition, Map<String, String> taskDescriptions) {
        this.number = number;
        this.name = name;
        this.emoji = emoji;
        this.tasks = tasks;
        this.unlockCondition = unlockCondition;
        this.taskDescriptions = taskDescriptions;
    }

    public static Level getByNumber(int number) {
        return Arrays.stream(values())
                .filter(level -> level.getNumber() == number)
                .findFirst()
                .orElse(MINIBRO);
    }

    public Task getTaskById(String taskId) {
        return tasks.stream()
                .filter(task -> task.getId().equals(taskId))
                .findFirst()
                .orElse(null);
    }

    public String getTaskDescription(String taskId) {
        return taskDescriptions.getOrDefault(taskId, "Описание отсутствует");
    }

    public boolean isTaskInLevel(String taskId) {
        return tasks.stream().anyMatch(task -> task.getId().equals(taskId));
    }

    public String getTaskIdByButtonText(String buttonText) {
        if (buttonText == null || buttonText.isEmpty()) {
            return null;
        }

        // Убираем эмодзи статуса (📝, ✅)
        String cleanText = buttonText
                .replace("📝 ", "")
                .replace("✅ ", "")
                .trim();

        System.out.println("Поиск taskId для текста: \"" + cleanText + "\" в уровне " + this.name);

        // Ищем задачу по точному совпадению имени
        for (Task task : tasks) {
            if (cleanText.equals(task.getName())) {
                System.out.println("Найдено точное совпадение: " + task.getId());
                return task.getId();
            }
        }

        // Ищем задачу по частичному совпадению
        for (Task task : tasks) {
            if (cleanText.contains(task.getName()) || task.getName().contains(cleanText)) {
                System.out.println("Найдено частичное совпадение: " + task.getId());
                return task.getId();
            }
        }

        // Ищем по номеру (1️⃣, 2️⃣, 3️⃣, 4️⃣)
        if (cleanText.contains("1️⃣")) {
            for (Task task : tasks) {
                if (task.getName().contains("1️⃣")) {
                    System.out.println("Найдено по номеру 1️⃣: " + task.getId());
                    return task.getId();
                }
            }
        } else if (cleanText.contains("2️⃣")) {
            for (Task task : tasks) {
                if (task.getName().contains("2️⃣")) {
                    System.out.println("Найдено по номеру 2️⃣: " + task.getId());
                    return task.getId();
                }
            }
        } else if (cleanText.contains("3️⃣")) {
            for (Task task : tasks) {
                if (task.getName().contains("3️⃣")) {
                    System.out.println("Найдено по номеру 3️⃣: " + task.getId());
                    return task.getId();
                }
            }
        } else if (cleanText.contains("4️⃣")) {
            for (Task task : tasks) {
                if (task.getName().contains("4️⃣")) {
                    System.out.println("Найдено по номеру 4️⃣: " + task.getId());
                    return task.getId();
                }
            }
        }

        System.out.println("Не найдено taskId для текста: \"" + cleanText + "\"");
        return null;
    }

    @Getter
    public static class Task {
        private final String id;
        private final String name;
        private final int points;
        private final String shortDescription;

        public Task(String id, String name, int points, String shortDescription) {
            this.id = id;
            this.name = name;
            this.points = points;
            this.shortDescription = shortDescription;
        }
    }
}