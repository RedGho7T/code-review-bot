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

    private static final String BTN_MAIN_MENU = "Главное меню";
    private static final String BTN_CHOOSE_LEVEL = "🎯 Выбрать уровень";
    private static final String BTN_STATS = "📊 Моя статистика";
    private static final String BTN_ABOUT = "ℹ️ О проекте";
    private static final String BTN_FIRST_STEPS = "🚀 Первые шаги";

    private static final String NAV_BACK_MENU = "⬅️ Главное меню";
    private static final String NAV_BACK_LEVELS = "⬅️ Назад к уровням";
    private static final String NAV_BACK_TASKS = "⬅️ Назад к задачам";
    private static final String NAV_BACK_INTO_MENU = "⬅️ Назад в меню";

    private static final String BTN_TASK_DONE = "✅ Я выполнил это задание!";

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


    @Override
    public String getBotToken() {
        return telegramProperties.getBotToken();
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        Message message = update.getMessage();
        Long chatId = message.getChatId();
        String text = message.getText();

        var chat = message.getChat();

        String chatType = chat != null ? chat.getType() : "unknown";
        String chatTitle = chat != null ? chat.getTitle() : null;
        String chatUsername = chat != null ? chat.getUserName() : null;

        String fromUsername = message.getFrom() != null ? message.getFrom().getUserName() : null;
        String fromFirstName = message.getFrom() != null ? message.getFrom().getFirstName() : null;

        // Для логирования ID группы
        log.info(
                "TG update: " +
                        "chatId={}, chatType={}, chatTitle='{}', chatUsername='@{}', " +
                        "from='@{}'({}), messageId={}, text='{}'",
                chatId,
                chatType,
                chatTitle,
                chatUsername,
                fromUsername,
                fromFirstName,
                message.getMessageId(),
                text
        );

        // Telegram user может отсутствовать (редко), но guard всё равно полезен
        if (message.getFrom() != null) {
            userProgressService.getOrCreateUser(
                    chatId,
                    message.getFrom().getUserName(),
                    message.getFrom().getFirstName()
            );
        }

        handleUserMessage(text, chatId);
    }

    private void handleUserMessage(String messageText, Long chatId) {
        if (messageText.startsWith("/")) {
            handleCommand(messageText, chatId);
            return;
        }

        // Глобальные кнопки/навигация — обрабатываем раньше любых state
        if (isGlobalOrNavigationButton(messageText)) {
            handleGlobalOrNavigationButton(messageText, chatId);
            return;
        }

        String state = userState.get(chatId);

        if (STATE_SELECTING_LEVEL.equals(state)) {
            handleLevelSelection(messageText, chatId);
            return;
        }

        if (state != null && state.startsWith(STATE_VIEWING_LEVEL_PREFIX)) {
            handleTaskInLevel(messageText, chatId, state);
            return;
        }

        if (STATE_VIEWING_TASK.equals(state)) {
            handleTaskAction(messageText, chatId);
            return;
        }

        // Основное меню
        switch (messageText) {
            case BTN_MAIN_MENU -> sendMainMenu(chatId);
            case BTN_ABOUT -> showProjectInfo(chatId);
            case BTN_FIRST_STEPS -> showFirstSteps(chatId);
            case "🏆 Лидерборд" -> sendMessage(chatId, leaderboardService.getFormattedLeaderboard(),
                    keyboardFactory.createMainMenuKeyboard(chatId));
            default -> sendMessage(chatId, "🤔 Я не понял запрос. Выбери вариант из клавиатуры.",
                    keyboardFactory.createMainMenuKeyboard(chatId));
        }
    }

    private void handleCommand(String command, Long chatId) {
        log.debug("Обработка команды: chatId={}, command={}", chatId, command);

        switch (command.toLowerCase()) {
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
                        
                        Основные:
                        • /start - начать работу
                        • /help - эта справка
                        • /menu - показать меню
                        • /reset - сбросить прогресс
                        • /upscore - +1000 очков (админ)
                        
                        💡 Совет: Просто нажимай кнопки в меню!
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
            default -> sendMessage(chatId, "🤔 Неизвестная команда. Напиши /help для списка команд.",
                    keyboardFactory.createMainMenuKeyboard(chatId));
        }
    }

    private boolean isGlobalOrNavigationButton(String text) {
        return NAV_BACK_MENU.equals(text)
                || NAV_BACK_LEVELS.equals(text)
                || NAV_BACK_TASKS.equals(text)
                || NAV_BACK_INTO_MENU.equals(text)
                || BTN_STATS.equals(text)
                || BTN_CHOOSE_LEVEL.equals(text);
    }

    private void handleGlobalOrNavigationButton(String messageText, Long chatId) {
        switch (messageText) {
            case NAV_BACK_MENU, NAV_BACK_INTO_MENU -> sendMainMenu(chatId);
            case NAV_BACK_LEVELS, BTN_CHOOSE_LEVEL -> showLevelSelection(chatId);
            case NAV_BACK_TASKS -> {
                Level level = userCurrentLevel.get(chatId);
                if (level != null) showLevelTasks(chatId, level);
                else showLevelSelection(chatId);
            }
            case BTN_STATS -> showStatistics(chatId);
            default -> sendMainMenu(chatId);
        }
    }

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

    private void showLevelSelection(Long chatId) {
        userState.put(chatId, STATE_SELECTING_LEVEL);
        userCurrentLevel.remove(chatId);

        String response = """
                📊 Выбери уровень:
                
                🔓 - доступен
                🔒 - заблокирован
                
                %s
                """.formatted(userProgressService.getUserStats(chatId));

        sendMessage(chatId, response, keyboardFactory.createLevelSelectionKeyboard(chatId));
    }

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

    private void showStatistics(Long chatId) {
        sendMessage(chatId, userProgressService.getUserStats(chatId),
                keyboardFactory.createMainMenuKeyboard(chatId));
    }

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

    private void showFirstSteps(Long chatId) {
        userState.put(chatId, STATE_FIRST_STEPS);

        String response = """
                🎯 Первые шаги в проекте
                
                Выбери этап, чтобы получить подробную инструкцию:
                """;
        sendMessage(chatId, response, keyboardFactory.createFirstStepsKeyboard());
    }

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
                    sendMessage(chatId, lockedResponse, keyboardFactory.createLevelSelectionKeyboard(chatId));
                    return;
                }

                showLevelTasks(chatId, level);
                return;
            }
        }

        sendMainMenu(chatId);
    }

    private void handleTaskInLevel(String messageText, Long chatId, String state) {
        String levelNumStr = state.replace(STATE_VIEWING_LEVEL_PREFIX, "");
        int levelNumber = Integer.parseInt(levelNumStr);
        Level level = Level.getByNumber(levelNumber);

        if (level == null) {
            sendMainMenu(chatId);
            return;
        }

        handleTaskSelection(chatId, messageText, level);
    }

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

        sendMessage(chatId, response, keyboardFactory.createTaskDetailKeyboard(chatId, taskId));
    }

    private void handleTaskAction(String messageText, Long chatId) {
        if (BTN_TASK_DONE.equals(messageText)) {
            handleTaskCompletionButton(chatId);
            return;
        }

        sendMessage(chatId, "🤔 Выберите действие из кнопок ниже",
                keyboardFactory.createMainMenuKeyboard(chatId));
    }

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

            sendMessage(chatId, congrats, keyboardFactory.createLevelSelectionKeyboard(chatId));
            return;
        }

        // если уровень не разблокирован — возвращаем к списку задач текущего уровня
        Level level = userCurrentLevel.get(chatId);
        if (level != null) showLevelTasks(chatId, level);
        else showLevelSelection(chatId);
    }

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

    public TaskDescriptionService getTaskDescriptionService() {
        return taskDescriptionService;
    }
}
