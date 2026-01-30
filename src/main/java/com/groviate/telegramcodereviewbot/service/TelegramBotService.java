package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.config.TelegramProperties;
import com.groviate.telegramcodereviewbot.dto.TaskCompletionResult;
import com.groviate.telegramcodereviewbot.entity.Level;
import com.groviate.telegramcodereviewbot.factory.KeyboardFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import com.groviate.telegramcodereviewbot.constants.BotButtons;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramBotService implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final String PARSE_MODE_MARKDOWN = "Markdown";

    private static final String STATE_SELECTING_LEVEL = "selecting_level";
    private static final String STATE_VIEWING_TASK = "viewing_task";
    private static final String STATE_FIRST_STEPS = "first_steps";
    private static final String STATE_VIEWING_LEVEL_PREFIX = "viewing_level_";

    private static final java.util.Set<String> ALLOWED_COMMANDS = java.util.Set.of(
            "/start",
            "/help",
            "/menu",
            "/reset",
            "/upscore"
    );


    private final TelegramClient telegramClient;
    private final TelegramProperties telegramProperties;
    private final KeyboardFactory keyboardFactory;
    private final UserProgressService userProgressService;
    private final BroadcastService broadcastService;
    private final LeaderboardService leaderboardService;
    private final TaskDescriptionService taskDescriptionService;

    private final Map<Long, String> userState = new ConcurrentHashMap<>();
    private final Map<Long, String> userLastTask = new ConcurrentHashMap<>();
    private final Map<Long, Level> userCurrentLevel = new ConcurrentHashMap<>();

    /**
     * Возвращает токен Telegram-бота из настроек приложения.
     */
    @Override
    public String getBotToken() {
        return telegramProperties.getBotToken();
    }

    /**
     * Возвращает consumer, который будет обрабатывать входящие Update.
     */
    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    /**
     * Точка входа обработки Telegram Update.
     * Принимаем только приватные текстовые сообщения и игнорируем всё остальное.
     */
    @Override
    public void consume(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        Message message = update.getMessage();

        // защита от петель "бот ответил боту"
        if (message.getFrom() != null && message.getFrom().getIsBot()) {
            return;
        }

        var chat = message.getChat();
        String chatType = (chat != null) ? chat.getType() : "unknown";

        // В группах/каналах молчим всегда
        if (!"private".equals(chatType)) {
            return;
        }

        Long chatId = message.getChatId();

        // hasText() => text уже должен быть задан
        String trimmed = message.getText().trim();
        if (trimmed.isBlank()) {
            return;
        }

        // реагируем только на известные триггеры
        if (!isHandleableInput(trimmed, chatId)) {
            return;
        }

        // Создаём пользователя только если реально будем что-то обрабатывать
        if (message.getFrom() != null) {
            userProgressService.getOrCreateUser(
                    chatId,
                    message.getFrom().getUserName(),
                    message.getFrom().getFirstName()
            );
        }

        log.debug("TG input accepted: chatId={}, type={}, messageId={}, text='{}'",
                chatId, chatType, message.getMessageId(), trimmed);

        handleUserMessage(trimmed, chatId);
    }

    /**
     * Проверяет, является ли текст кнопкой из главного меню (верхний уровень навигации).
     */
    private boolean isMainMenuButton(String text) {
        return BotButtons.BTN_CHOOSE_LEVEL.equals(text)
                || BotButtons.BTN_STATS.equals(text)
                || BotButtons.BTN_ABOUT.equals(text)
                || BotButtons.BTN_FIRST_STEPS.equals(text)
                || BotButtons.BTN_ADVANCED_TASKS.equals(text)
                || BotButtons.BTN_LOCKED_ADVANCED.equals(text)
                || BotButtons.BTN_LEADERBOARD.equals(text)
                || BotButtons.BTN_LOCKED_LEADERBOARD.equals(text);
    }

    /**
     * Фильтр входящих сообщений.
     * Разрешает только команды/кнопки/ожидаемые ответы в рамках текущего state — остальное игнорируем.
     */
    private boolean isHandleableInput(String text, Long chatId) {
        if (text.startsWith("/")) {
            String cmd = normalizeCommand(text);
            return ALLOWED_COMMANDS.contains(cmd);
        }

        if (isGlobalOrNavigationButton(text) || isMainMenuButton(text)) {
            return true;
        }

        String state = userState.get(chatId);

        if (STATE_SELECTING_LEVEL.equals(state)) {
            return true; // выбор уровня
        }

        if (state != null && state.startsWith(STATE_VIEWING_LEVEL_PREFIX)) {
            return true; // выбор задачи на уровне
        }

        if (STATE_VIEWING_TASK.equals(state)) {
            return BotButtons.BTN_TASK_DONE.equals(text)
                    || isGlobalOrNavigationButton(text)
                    || isMainMenuButton(text);
        }

        // Sonar: replace if+return by a single return statement
        return STATE_FIRST_STEPS.equals(state);
    }

    /**
     * Нормализует команду:
     * берём первый токен, отрезаем "@botname" и приводим к lower-case.
     */
    private String normalizeCommand(String raw) {
        if (raw == null) {
            return "";
        }
        String token = raw.trim().split("\\s+")[0];
        int at = token.indexOf('@');
        if (at > 0) {
            token = token.substring(0, at);
        }
        return token.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Центральный роутер текста: команды -> глобальная навигация -> меню -> обработка по state.
     */
    private void handleUserMessage(String messageText, Long chatId) {
        if (messageText == null) {
            return;
        }

        String text = messageText.trim();
        if (text.isBlank()) {
            return;
        }

        // 1) Команды
        if (text.startsWith("/")) {
            String cmd = normalizeCommand(text);
            if (!ALLOWED_COMMANDS.contains(cmd)) {
                log.trace("Такой команды нет: chatId={}, cmd={}", chatId, cmd);
                return;
            }
            handleCommand(cmd, chatId);
            return;
        }

        // 2) Навигация/глобальные кнопки
        if (isGlobalOrNavigationButton(text)) {
            handleGlobalOrNavigationButton(text, chatId);
            return;
        }

        // 3) Главное меню — обрабатываем до state-based
        switch (text) {
            case BotButtons.BTN_ABOUT -> {
                showProjectInfo(chatId);
                return;
            }
            case BotButtons.BTN_FIRST_STEPS -> {
                showFirstSteps(chatId);
                return;
            }
            case BotButtons.BTN_LEADERBOARD -> {
                sendLeaderboardMessage(
                        chatId,
                        leaderboardService.getFormattedLeaderboard(),
                        keyboardFactory.createMainMenuKeyboard(chatId)
                );
                return;
            }

            // ✅ Замки: даём понятную отбивку и логично ведём в выбор уровней
            case BotButtons.BTN_LOCKED_ADVANCED -> {
                redirectToLevelSelectionWithHint(chatId, 200, "Продвинутые задания");
                return;
            }
            case BotButtons.BTN_LOCKED_LEADERBOARD -> {
                redirectToLevelSelectionWithHint(chatId, 100, "Лидерборд");
                return;
            }

            default -> {
                // не меню-кнопка — идём в state-based
            }
        }

        // 4) State-based обработка
        String state = userState.get(chatId);

        if (STATE_SELECTING_LEVEL.equals(state)) {
            handleLevelSelection(text, chatId);
            return;
        }

        if (state != null && state.startsWith(STATE_VIEWING_LEVEL_PREFIX)) {
            handleTaskInLevel(text, chatId, state);
            return;
        }

        if (STATE_VIEWING_TASK.equals(state)) {
            handleTaskAction(text, chatId);
            return;
        }

        if (STATE_FIRST_STEPS.equals(state)) {
            handleFirstSteps(text, chatId);
            return;
        }

        log.trace("Ignore unknown text: chatId={}, text='{}'", chatId, text);
    }

    private void redirectToLevelSelectionWithHint(Long chatId, int requiredPoints, String featureName) {
        // Переводим в выбор уровня
        userState.put(chatId, STATE_SELECTING_LEVEL);
        userCurrentLevel.remove(chatId);

        int currentPoints = userProgressService.getUserTotalPoints(chatId);

        String response = """
                🔒 *%s* пока закрыто.
                Нужно: *%d* очков
                Сейчас: *%d* очков
                
                Чтобы набрать очки — выбирай уровень и выполняй задания 👇
                
                %s
                """.formatted(featureName, requiredPoints, currentPoints, userProgressService.getUserStats(chatId));

        sendMessage(chatId, response, keyboardFactory.createLevelSelectionKeyboard());
    }

    /**
     * Обрабатывает выбор пункта в меню "Первые шаги".
     */
    private void handleFirstSteps(String messageText, Long chatId) {
        if (BotButtons.NAV_BACK_INTO_MENU.equals(messageText) || BotButtons.NAV_MAIN_MENU.equals(messageText)) {
            sendMainMenu(chatId);
            return;
        }

        String response = """
                🚀 Первые шаги
                
                Вы выбрали: *%s*
                
                (Дальше добавим конкретные инструкции для этого пункта)
                """.formatted(messageText);

        sendMessage(chatId, response, keyboardFactory.createFirstStepsKeyboard());
    }

    /**
     * Обрабатывает разрешённые команды бота.
     */
    private void handleCommand(String cmd, Long chatId) {
        log.debug("Обработка команды: chatId={}, command={}", chatId, cmd);

        switch (cmd) {
            case "/start" -> {
                if (!broadcastService.isSubscribed(chatId)) {
                    broadcastService.subscribeUser(chatId);
                    log.info("Пользователь подписан на рассылку: chatId={}", chatId);
                }
                sendMainMenu(chatId);
            }
            case "/help" -> {
                String helpText = """
                        ℹ️ Доступные команды:
                        
                        • /start - начать работу
                        • /help - эта справка
                        • /menu - показать меню
                        """;
                sendMessage(chatId, helpText, keyboardFactory.createMainMenuKeyboard(chatId));
            }
            case "/menu" -> sendMainMenu(chatId);
            case "/reset" -> {
                userProgressService.resetUser(chatId);
                sendMessage(chatId, "Сброс очков!", keyboardFactory.createMainMenuKeyboard(chatId));
            }
            case "/upscore" -> {
                userProgressService.upScore(chatId);
                sendMessage(chatId, "Кол-во + 1000 очков!", keyboardFactory.createMainMenuKeyboard(chatId));
            }
            default -> log.trace("Неизвестная команда: chatId={}, cmd={}", chatId, cmd);
        }
    }

    /**
     * Проверяет, относится ли текст к глобальным кнопкам/навигации.
     */
    private boolean isGlobalOrNavigationButton(String text) {
        return BotButtons.NAV_MAIN_MENU.equals(text)
                || BotButtons.NAV_BACK_LEVELS.equals(text)
                || BotButtons.NAV_BACK_TASKS.equals(text)
                || BotButtons.NAV_BACK_INTO_MENU.equals(text)
                || BotButtons.BTN_STATS.equals(text)
                || BotButtons.BTN_CHOOSE_LEVEL.equals(text)
                || BotButtons.BTN_ADVANCED_TASKS.equals(text);
    }

    /**
     * Обрабатывает глобальные кнопки (навигация, статистика, выбор уровня).
     */
    private void handleGlobalOrNavigationButton(String messageText, Long chatId) {
        switch (messageText) {
            case BotButtons.NAV_BACK_LEVELS, BotButtons.BTN_CHOOSE_LEVEL, BotButtons.BTN_ADVANCED_TASKS ->
                    showLevelSelection(chatId);

            case BotButtons.NAV_BACK_TASKS -> {
                Level level = userCurrentLevel.get(chatId);
                if (level != null) {
                    showLevelTasks(chatId, level);
                } else {
                    showLevelSelection(chatId);
                }
            }

            case BotButtons.BTN_STATS -> showStatistics(chatId);

            default -> sendMainMenu(chatId);
        }
    }

    /**
     * Показывает главное меню и сбрасывает state пользователя.
     */
    private void sendMainMenu(Long chatId) {
        userState.remove(chatId);
        userLastTask.remove(chatId);
        userCurrentLevel.remove(chatId);

        String response = """
                👋 Привет! Я бот для адаптации в проекте.
                Выбери нужный раздел:
                """;
        sendMessage(chatId, response, keyboardFactory.createMainMenuKeyboard(chatId));
    }

    /**
     * Показывает выбор уровня и переводит пользователя в состояние выбора уровня.
     */
    private void showLevelSelection(Long chatId) {
        userState.put(chatId, STATE_SELECTING_LEVEL);
        userCurrentLevel.remove(chatId);

        String response = """
                📊 Выбери уровень:
                
                🔓 - доступен
                🔒 - заблокирован
                
                %s
                """.formatted(userProgressService.getUserStats(chatId));

        sendMessage(chatId, response, keyboardFactory.createLevelSelectionKeyboard());
    }

    /**
     * Показывает список задач выбранного уровня и переводит state в просмотр уровня.
     */
    private void showLevelTasks(Long chatId, Level level) {
        userState.put(chatId, STATE_VIEWING_LEVEL_PREFIX + level.getNumber());
        userCurrentLevel.put(chatId, level);
        userLastTask.remove(chatId);

        String response = """
                %s %s
                
                📝 Задания уровня:
                
                ✅ - выполнено
                ⬜ - не выполнено
                
                Выбери задание:
                """.formatted(level.getEmoji(), level.getName());

        sendMessage(chatId, response, keyboardFactory.createLevelTasksKeyboard(chatId, level));
    }

    /**
     * Отправляет пользователю статистику (текущий уровень, очки, прогресс).
     */
    private void showStatistics(Long chatId) {
        sendMessage(chatId, userProgressService.getUserStats(chatId),
                keyboardFactory.createMainMenuKeyboard(chatId));
    }

    /**
     * Показывает краткую информацию о проекте.
     */
    private void showProjectInfo(Long chatId) {
        String response = """
                ℹ️ *О проекте*
                
                *Название*: Habit Tracker
                *Технологии*: Java, микросервисная архитектура
                *Фреймворк*: Spring Boot
                *Сборка*: Gradle
                *База данных*: PostgreSQL
                
                📅 *Версия*: 1.0.0
                """;
        sendMessage(chatId, response, keyboardFactory.createMainMenuKeyboard(chatId));
    }

    /**
     * Переводит пользователя в раздел "Первые шаги" и показывает соответствующую клавиатуру.
     */
    private void showFirstSteps(Long chatId) {
        userState.put(chatId, STATE_FIRST_STEPS);

        String response = """
                🎯 Первые шаги в проекте
                
                Выбери этап, чтобы получить подробную инструкцию:
                """;
        sendMessage(chatId, response, keyboardFactory.createFirstStepsKeyboard());
    }

    /**
     * Обрабатывает выбор уровня пользователем, проверяет доступность уровня.
     */
    private void handleLevelSelection(String messageText, Long chatId) {
        for (Level level : Level.values()) {
            if (messageText.contains(level.getName())
                    || messageText.contains(level.getEmoji())
                    || messageText.contains("Уровень " + level.getNumber())) {

                if (!userProgressService.isLevelAccessible(chatId, level.getNumber())) {
                    String lockedResponse = """
                            ❌ Уровень заблокирован!
                            
                            Чтобы открыть этот уровень, выполни все задания предыдущего уровня.
                            """;
                    sendMessage(chatId, lockedResponse, keyboardFactory.createLevelSelectionKeyboard());
                    return;
                }

                showLevelTasks(chatId, level);
                return;
            }
        }

        sendMainMenu(chatId);
    }

    /**
     * Из state извлекает номер уровня и маршрутизирует выбор задачи внутри уровня.
     */
    private void handleTaskInLevel(String messageText, Long chatId, String state) {
        String levelNumStr = state.replace(STATE_VIEWING_LEVEL_PREFIX, "");

        final int levelNumber;
        try {
            levelNumber = Integer.parseInt(levelNumStr);
        } catch (NumberFormatException ex) {
            log.warn("Bad state format for level: chatId={}, state={}", chatId, state);
            sendMainMenu(chatId);
            return;
        }

        // getByNumber() гарантирует не-null (fallback на MINIBRO)
        Level level = Level.getByNumber(levelNumber);

        handleTaskSelection(chatId, messageText, level);
    }

    /**
     * Показывает подробности задания (описание, очки, статус выполнения) по выбранной кнопке.
     */
    private void handleTaskSelection(Long chatId, String buttonText, Level level) {
        String taskId = level.getTaskIdByButtonText(buttonText);
        if (taskId == null) {
            sendMessage(chatId, "❌ Не удалось определить задание",
                    keyboardFactory.createMainMenuKeyboard(chatId));
            return;
        }

        Level.Task task = level.getTaskById(taskId);
        if (task == null) {
            sendMessage(chatId, "❌ Задание не найдено",
                    keyboardFactory.createMainMenuKeyboard(chatId));
            return;
        }

        userLastTask.put(chatId, taskId);
        userState.put(chatId, STATE_VIEWING_TASK);

        boolean isCompleted = userProgressService.isTaskCompleted(chatId, taskId);
        String description = taskDescriptionService.getTaskDescription(taskId);

        String response = isCompleted
                ? """
                🎉 %s
                
                %s
                
                ✅ Выполнено
                ⭐ Получено очков: %d
                """.formatted(task.name(), description, task.points())
                : """
                📋 *%s*
                
                %s
                
                ⭐ Очков за выполнение: %d
                
                Когда выполните задание, нажмите кнопку ниже:
                """.formatted(task.name(), description, task.points());

        sendMessage(chatId, response, keyboardFactory.createTaskDetailKeyboard());
    }

    /**
     * Обрабатывает действия внутри просмотра задания (например, "я выполнил").
     */
    private void handleTaskAction(String messageText, Long chatId) {
        if (BotButtons.BTN_TASK_DONE.equals(messageText)) {
            handleTaskCompletionButton(chatId);
            return;
        }
        log.trace("Ignore task action input: chatId={}, text='{}'", chatId, messageText);
    }

    /**
     * Отмечает задание выполненным и показывает результат (очки/разблокировка уровня).
     */
    private void handleTaskCompletionButton(Long chatId) {
        String taskId = userLastTask.get(chatId);
        if (taskId == null) {
            sendMessage(chatId, "❌ Сначала выберите задание",
                    keyboardFactory.createMainMenuKeyboard(chatId));
            return;
        }

        TaskCompletionResult result = userProgressService.completeTask(chatId, taskId);
        sendMessage(chatId, result.message(), keyboardFactory.createMainMenuKeyboard(chatId));

        userLastTask.remove(chatId);

        if (!result.success()) {
            // оставляем state как viewing_task, чтобы пользователь мог нажать “назад”
            userState.put(chatId, STATE_VIEWING_TASK);
            return;
        }

        if (result.levelUnlocked()) {
            Level newLevel = Level.getByNumber(result.newLevelNumber());

            userState.put(chatId, STATE_SELECTING_LEVEL);
            userCurrentLevel.remove(chatId);

            String congrats = """
                    🎊 *ПОЗДРАВЛЯЕМ!*
                    
                    Ты разблокировал новый уровень!
                    %s *%s*
                    """.formatted(newLevel.getEmoji(), newLevel.getName());

            sendMessage(chatId, congrats, keyboardFactory.createLevelSelectionKeyboard());
            return;
        }

        // если уровень не разблокирован — возвращаем к списку задач текущего уровня
        Level level = userCurrentLevel.get(chatId);
        if (level != null) {
            showLevelTasks(chatId, level);
        } else {
            showLevelSelection(chatId);
        }
    }

    /**
     * Универсальная отправка сообщения пользователю (Markdown + клавиатура).
     */
    private void sendMessage(Long chatId, String text, Object keyboard) {
        SendMessage sendMessage = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .parseMode(PARSE_MODE_MARKDOWN)
                .replyMarkup((ReplyKeyboard) keyboard)
                .build();

        try {
            telegramClient.execute(sendMessage);
        } catch (TelegramApiException e) {
            log.warn("Ошибка отправки сообщения: chatId={}, err={}", chatId, e.getMessage(), e);
        }
    }

    /**
     * Отправка сообщения без parseMode (полезно для текстов, которые могут ломать Markdown).
     */
    private void sendLeaderboardMessage(Long chatId, String text, Object keyboard) {
        SendMessage sendMessage = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .parseMode(null)
                .replyMarkup((ReplyKeyboard) keyboard)
                .build();

        try {
            telegramClient.execute(sendMessage);
        } catch (TelegramApiException e) {
            log.warn("Ошибка отправки сообщения: chatId={}, err={}", chatId, e.getMessage(), e);
        }
    }
}
