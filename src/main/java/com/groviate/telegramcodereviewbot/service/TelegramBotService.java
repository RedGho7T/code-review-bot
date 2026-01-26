package com.groviate.telegramcodereviewbot.service;


import com.groviate.telegramcodereviewbot.config.TgBotConfig;
import com.groviate.telegramcodereviewbot.entity.Level;
import com.groviate.telegramcodereviewbot.factory.KeyboardFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TelegramBotService extends TelegramLongPollingBot {

    @Lazy
    @Autowired
    private BroadcastService broadcastService;

    private final TgBotConfig config;
    private final KeyboardFactory keyboardFactory;
    private final UserProgressService userProgressService;

    private final Map<Long, String> userState = new ConcurrentHashMap<>();
    private final Map<Long, Integer> selectedLevel = new ConcurrentHashMap<>();
    private final Map<Long, String> userLastTask = new ConcurrentHashMap<>();
    private final Map<Long, Level> userCurrentLevel = new ConcurrentHashMap<>();

    public TelegramBotService(TgBotConfig config, KeyboardFactory keyboardFactory,
                              UserProgressService userProgressService) {
        super(config.getBotToken());
        this.config = config;
        this.keyboardFactory = keyboardFactory;
        this.userProgressService = userProgressService;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            Message message = update.getMessage();
            Long chatId = message.getChatId();
            String text = message.getText();
            User user = message.getFrom();

            String username = user.getUserName() != null ? "@" + user.getUserName() : "без юзернейма";

            System.out.println("Получено сообщение от " + username + ": " + text);

            // Получаем или создаем пользователя
            userProgressService.getOrCreateUser(
                    chatId,
                    user.getUserName(),
                    user.getFirstName(),
                    user.getLastName()
            );

            // Обрабатываем сообщение
            handleUserMessage(text, chatId);
        }
    }

    private void handleUserMessage(String messageText, Long chatId) {
        // Проверяем, начинается ли сообщение с команды
        if (messageText.startsWith("/")) {
            handleCommand(messageText, chatId);
            return; // После обработки команды выходим
        }

        String state = userState.get(chatId);

        // ВСЕГДА сначала проверяем навигационные кнопки
        if (isNavigationButton(messageText)) {
            handleNavigationButton(messageText, chatId, userState.get(chatId));
            return;
        }

        // Если пользователь в состоянии выбора уровня
        if ("selecting_level".equals(state)) {
            handleLevelSelection(messageText, chatId);
            return;
        }

        // Если пользователь просматривает конкретный уровень
        if (state != null && state.startsWith("viewing_level_")) {
            handleTaskInLevel(messageText, chatId, state);
            return;
        }

        // Если пользователь просматривает задачу
        if ("viewing_task".equals(state)) {
            handleTaskAction(messageText, chatId);
            return;
        }

        // Обработка основных команд меню
        switch (messageText) {
            case "Главное меню":
                sendMainMenu(chatId);
                break;

            case "🎯 Выбрать уровень":
                showLevelSelection(chatId);
                break;

            case "📊 Моя статистика":
                showStatistics(chatId);
                break;

            case "ℹ️ О проекте":
                showProjectInfo(chatId);
                break;

            case "🚀 Первые шаги":
                showFirstSteps(chatId);
                break;

            default:
                sendMessage(chatId, "🤔 Я не понял запрос. Выбери вариант из клавиатуры.",
                        keyboardFactory.createMainMenuKeyboard(chatId));
        }
    }

    /**
     * Обработка текстовых команд (ИСПРАВЛЕННАЯ)
     */
    private void handleCommand(String command, Long chatId) {
        System.out.println("⌨️ Обработка команды: " + command);

        switch (command.toLowerCase()) {
            case "/start":
                // ПОДПИСЫВАЕМ ПОЛЬЗОВАТЕЛЯ НА РАССЫЛКУ ПРИ СТАРТЕ
                if (!broadcastService.isSubscribed(chatId)) {
                    broadcastService.subscribeUser(chatId);
                    System.out.println("✅ Пользователь " + chatId + " подписан на рассылку");
                }
                sendMainMenu(chatId);
                break;

            case "/help":
                String helpText = "ℹ️ Доступные команды:\n\n" +
                        "Основные:\n" +
                        "• /start - начать работу\n" +
                        "• /help - эта справка\n\n" +
                        "Кнопки меню:\n" +
                        "• 🚀 Первые шаги - гайд по онбордингу\n" +
                        "• 🎯 Выбрать уровень - путь становления Senior'ом\n" +
                        "• 📊 Моя статистика - твои достижения\n" +
                        "• ℹ️ О проекте - информация\n\n" +
                        "💡 Совет: Просто нажимай кнопки в меню!";
                sendMessage(chatId, helpText, keyboardFactory.createMainMenuKeyboard(chatId));
                break;

            case "/menu":
                sendMainMenu(chatId);
                break;

            default:
                sendMessage(chatId, "🤔 Неизвестная команда. Напиши /help для списка команд.",
                        keyboardFactory.createMainMenuKeyboard(chatId));
        }
    }

    /**
     * Проверить, является ли кнопка навигационной
     */
    private boolean isNavigationButton(String text) {
        return text.equals("⬅️ Главное меню") ||
                text.equals("⬅️ Назад к уровням") ||
                text.equals("⬅️ Назад к задачам") ||
                text.equals("📊 Моя статистика") ||
                text.equals("🎯 Выбрать уровень");
    }

    /**
     * Обработка навигационных кнопок
     */
    private void handleNavigationButton(String messageText, Long chatId, String state) {
        switch (messageText) {
            case "⬅️ Главное меню":
                sendMainMenu(chatId);
                break;

            case "⬅️ Назад к уровням":
                showLevelSelection(chatId);
                break;

            case "⬅️ Назад к задачам":
                // Возвращаемся к задачам текущего уровня
                Level level = userCurrentLevel.get(chatId);
                if (level != null) {
                    showLevelTasks(chatId, level);
                } else {
                    showLevelSelection(chatId);
                }
                break;

            case "📊 Моя статистика":
                showStatistics(chatId);
                break;

            case "🎯 Выбрать уровень":
                showLevelSelection(chatId);
                break;
        }
    }

    /**
     * Показать главное меню
     */
    private void sendMainMenu(Long chatId) {
        userState.remove(chatId);
        userLastTask.remove(chatId);
        userCurrentLevel.remove(chatId);

        String response = "👋 Привет! Я бот для адаптации в проекте.\n" +
                "Выбери нужный раздел:";
        sendMessage(chatId, response, keyboardFactory.createMainMenuKeyboard(chatId));
    }

    /**
     * Показать выбор уровней
     */
    private void showLevelSelection(Long chatId) {
        userState.put(chatId, "selecting_level");
        userCurrentLevel.remove(chatId);

        String response = "📊 Выбери уровень:\n\n" +
                "🔓 - доступен\n" +
                "🔒 - заблокирован\n\n" +
                userProgressService.getUserStats(chatId);
        sendMessage(chatId, response, keyboardFactory.createLevelSelectionKeyboard(chatId));
    }

    /**
     * Показать задачи уровня
     */
    private void showLevelTasks(Long chatId, Level level) {
        userState.put(chatId, "viewing_level_" + level.getNumber());
        userCurrentLevel.put(chatId, level);
        userLastTask.remove(chatId);

        String response = String.format(
                "%s *%s*\n\n" +
                        "📝 Задания уровня:\n\n" +
                        "✅ - выполнено\n" +
                        "⬜ - не выполнено\n\n" +
                        "Выбери задание:",
                level.getEmoji(), level.getName()
        );

        sendMessage(chatId, response, keyboardFactory.createLevelTasksKeyboard(chatId, level));
    }

    /**
     * Показать статистику
     */
    private void showStatistics(Long chatId) {
        String stats = userProgressService.getUserStats(chatId);
        sendMessage(chatId, stats, keyboardFactory.createMainMenuKeyboard(chatId));
    }

    /**
     * Показать информацию о проекте
     */
    private void showProjectInfo(Long chatId) {
        String response = "ℹ️ О проекте\n\n" +
                "Название: Habit Tracker\n" +
                "Технологии: Java, микросервисная архитектура\n" +
                "Фреймворк: Spring Boot\n" +
                "Сборка: Gradle 8.5\n" +
                "База данных: PostgreSQL, MongoDB\n" +
                "Брокер сообщений: Kafka\n" +
                "Кэширование: Redis\n" +
                "Контейнеры и оркестрация: Docker, MiniKube\n" +
                "Мониторинг и логирование: Prometheus, Grafana, Grafana Loki, SLF4J\n" +
                "Документация: Swagger, JavaDoc\n" +
                "Миграции БД: Liquibase\n" +
                "Тестирование: JUnit, SpringTest, Postman, Mockito, Testcontainers\n" +
                "Инструменты: Postman, Swagger, curl для API тестов\n" +
                "Покрытие тестами: JaCoCO\n" +
                "Качество кода: CheckStyle, SonarQube plugin IDEA, SonarQube server\n" +
                "Контроль версий: Git, GitLab\n" +
                "CI/CD: GitLab CI\n" +
                "Команда: Твоя awesome команда!\n\n" +
                "📅 Версия: 1.0.0";
        sendMessage(chatId, response, keyboardFactory.createMainMenuKeyboard(chatId));
    }

    /**
     * Показать первые шаги
     */
    private void showFirstSteps(Long chatId) {
        userState.put(chatId, "first_steps");

        String response = "🎯 Первые шаги в проекте\n\n" +
                "Выбери этап, чтобы получить подробную инструкцию:";
        sendMessage(chatId, response, keyboardFactory.createFirstStepsKeyboard());
    }

    /**
     * Обработка выбора уровня
     */
    private void handleLevelSelection(String messageText, Long chatId) {
        // Ищем уровень по имени или эмодзи
        for (Level level : Level.values()) {
            if (messageText.contains(level.getName()) ||
                    messageText.contains(level.getEmoji()) ||
                    messageText.contains("Уровень " + level.getNumber())) {

                // Проверяем доступность
                if (!userProgressService.isLevelAccessible(chatId, level.getNumber())) {
                    String lockedResponse = "❌ Уровень заблокирован!\n\n" +
                            "Чтобы открыть этот уровень, выполни все задания предыдущего уровня.";
                    sendMessage(chatId, lockedResponse, keyboardFactory.createLevelSelectionKeyboard(chatId));
                    return;
                }

                // Показываем задания уровня
                showLevelTasks(chatId, level);
                return;
            }
        }

        // Если не найден уровень, возвращаем в меню
        sendMainMenu(chatId);
    }

    /**
     * Обработка выбора задачи внутри уровня
     */
    private void handleTaskInLevel(String messageText, Long chatId, String state) {
        // Извлекаем номер уровня из состояния
        String levelNumStr = state.replace("viewing_level_", "");
        int levelNumber = Integer.parseInt(levelNumStr);
        Level level = Level.getByNumber(levelNumber);

        if (level == null) {
            sendMainMenu(chatId);
            return;
        }

        // Это должна быть кнопка задачи
        handleTaskSelection(chatId, messageText, level);
    }

    /**
     * Обработка выбора конкретной задачи
     */
    private void handleTaskSelection(Long chatId, String buttonText, Level level) {
        System.out.println("Обработка выбора задачи: \"" + buttonText +
                "\" для уровня: " + level.getName());

        // Получаем taskId из текста кнопки
        String taskId = level.getTaskIdByButtonText(buttonText);
        if (taskId == null) {
            System.out.println("❌ Не удалось определить taskId для кнопки: " + buttonText);
            sendMessage(chatId, "❌ Не удалось определить задание",
                    keyboardFactory.createMainMenuKeyboard(chatId));
            return;
        }

        System.out.println("TaskId найден: " + taskId);

        // Находим задачу
        Level.Task task = level.getTaskById(taskId);
        if (task == null) {
            System.out.println("Задание не найдено по taskId: " + taskId);
            sendMessage(chatId, "❌ Задание не найдено",
                    keyboardFactory.createMainMenuKeyboard(chatId));
            return;
        }

        // Сохраняем текущую задачу для пользователя
        userLastTask.put(chatId, taskId);
        userState.put(chatId, "viewing_task");

        // Проверяем, выполнена ли задача
        boolean isCompleted = userProgressService.isTaskCompleted(chatId, taskId);

        // Формируем сообщение
        String response;
        if (isCompleted) {
            response = String.format(
                    "🎉 %s\n\n" +
                            "%s\n\n" +
                            "✅ Выполнено\n" +
                            "⭐ Получено очков: %d",
                    task.getName(),
                    level.getTaskDescription(taskId),
                    task.getPoints()
            );
        } else {
            response = String.format(
                    "📋 *%s*\n\n" +
                            "%s\n\n" +
                            "⭐ Очков за выполнение: %d\n\n" +
                            "Когда выполните задание, нажмите кнопку ниже:",
                    task.getName(),
                    level.getTaskDescription(taskId),
                    task.getPoints()
            );
        }

        sendMessage(chatId, response, keyboardFactory.createTaskDetailKeyboard(chatId, taskId));
    }

    /**
     * Обработка действий в просмотре задачи
     */
    private void handleTaskAction(String messageText, Long chatId) {
        switch (messageText) {
            case "✅ Я выполнил это задание!":
                handleTaskCompletionButton(chatId);

            // Возвращаемся к задачам текущего уровня
            Level level = userCurrentLevel.get(chatId);
            if (level != null) {
                showLevelTasks(chatId, level);
            } else {
                showLevelSelection(chatId);
            }
            break;
            default:
                sendMessage(chatId, "🤔 Выберите действие из кнопок ниже",
                        keyboardFactory.createMainMenuKeyboard(chatId));
        }
    }

    /**
     * Обработка нажатия кнопки "Я выполнил это задание!"
     */
    private void handleTaskCompletionButton(Long chatId) {
        // Получаем последнюю выбранную задачу
        String taskId = userLastTask.get(chatId);
        if (taskId == null) {
            sendMessage(chatId, "❌ Сначала выберите задание",
                    keyboardFactory.createMainMenuKeyboard(chatId));
            return;
        }

        // Выполняем задание
        UserProgressService.TaskCompletionResult result =
                userProgressService.completeTask(chatId, taskId);

        // Отправляем результат
        sendMessage(chatId, result.getMessage(),
                keyboardFactory.createMainMenuKeyboard(chatId));

        // Очищаем последнюю задачу
        userLastTask.remove(chatId);
        userState.remove(chatId);

        // Если разблокирован новый уровень
        if (result.isLevelUnlocked() && result.getUser() != null) {
            try {
                Thread.sleep(1000);

                Level newLevel = Level.getByNumber(result.getUser().getCurrentLevel());
                String congratsMessage = String.format(
                        "🎊 *ПОЗДРАВЛЯЕМ!*\n\n" +
                                "Ты разблокировал новый уровень!\n" +
                                "%s *%s*",
                        newLevel.getEmoji(), newLevel.getName()
                );

                sendMessage(chatId, congratsMessage,
                        keyboardFactory.createLevelSelectionKeyboard(chatId));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Извлечь taskId из текста кнопки (улучшенная версия)
     */
//    private String extractTaskIdFromButton(String buttonText, Level level) {
//        if (level == null) {
//            return null;
//        }
//
//        // Убираем эмодзи и лишний текст
//        String cleanText = buttonText
//                .replace("📝 ", "")
//                .replace("✅ ", "")
//                .replace("⏳ ", "")
//                .replaceAll("\\s*\\(.*\\)", "")
//                .trim();
//
//        // Для каждого уровня свои правила преобразования
//        switch (level.getNumber()) {
//            case 1: // Микрочелик
//                switch (cleanText) {
//                    case "1️⃣ Первое задание":
//                        return "minibro_1";
//                    case "2️⃣ Второе задание":
//                        return "minibro_2";
//                    case "3️⃣ Третье задание":
//                        return "minibro_3";
//                    case "4️⃣ Четвертое задание":
//                        return "minibro_4";
//                    default:
//                        return null;
//                }
//            case 2: // Босс этого проекта
//                switch (cleanText) {
//                    case "📁 Архитектура проекта":
//                        return "boss_1";
//                    case "🔧 Code Review процесс":
//                        return "boss_2";
//                    default:
//                        return null;
//                }
//            default:
//                return null;
//        }
//    }


    /**
     * Универсальный метод отправки сообщения с клавиатурой
     */
    private void sendMessage(Long chatId, String text, ReplyKeyboardMarkup keyboard) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("Markdown");

        if (keyboard != null) {
            message.setReplyMarkup(keyboard);
        }

        try {
            execute(message);
            System.out.println("✅ Sent message to chat " + chatId);
        } catch (TelegramApiException e) {
            System.out.println("❌ Ошибка отправки: " + e.getMessage());
        }
    }

    /**
     * Извлечь taskId из текста кнопки (ПРОСТАЯ версия с использованием Level)
     */
    private String extractTaskIdFromButton(String buttonText, Level level) {
        if (level == null) {
            System.out.println("Уровень null при извлечении taskId");
            return null;
        }

        System.out.println("Извлечение taskId из кнопки: \"" + buttonText +
                "\" для уровня: " + level.getName());

        // Используем новый метод Level
        String taskId = level.getTaskIdByButtonText(buttonText);

        System.out.println("Результат: " + (taskId != null ? taskId : "не найден"));
        return taskId;
    }

    @Override
    public String getBotUsername() {
        return config.getBotUsername();
    }
}