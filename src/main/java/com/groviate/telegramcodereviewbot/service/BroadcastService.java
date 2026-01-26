package com.groviate.telegramcodereviewbot.service;


import com.groviate.telegramcodereviewbot.factory.KeyboardFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BroadcastService {

    private final TelegramBotService botService;
    private final KeyboardFactory keyboardFactory;
    private final StickerService stickerService;

    // Храним chatId пользователей
    private final Set<Long> subscribers = ConcurrentHashMap.newKeySet();

    // Храним очередь сообщений для каждого пользователя
    private final Map<Long, Queue<String>> userMessageQueues = new ConcurrentHashMap<>();
    private final Map<Long, LocalDateTime> lastBroadcastTime = new ConcurrentHashMap<>();

    private final List<String> messageSequence = Arrays.asList(
            "Давай немного помогу разобраться с состоянием «В тупике»\n\n" +
                    "Работа со стрессом и состоянием: \n https://wika.kata.academy/pages/viewpage.action?pageId=40829162" +
                    " \n\nОтправлено: " + getCurrentTime(),
            "Маленький, но полезный совет по работе в проекте который тебе поможет\n\n" +
                    "Инициатива в проекте:\n https://wika.kata.academy/pages/viewpage.action?pageId=47319701" +
                    " \n\nОтправлено: " + getCurrentTime(),
            "А вот этот словарь, явно, поможет тебе понимать коллег и привыкнуть к сленгу\n\n" +
            "https://wika.kata.academy/pages/viewpage.action?pageId=25035466" +
                    " \n\n Отправлено: " + getCurrentTime(),
            "Пройдя этот путь, вот, что ты покоришь\n\n" +
                    "Навыки получаемые на проекте:\n https://wika.kata.academy/pages/viewpage.action?pageId=33161993" +
                    " \n\nОтправлено: " + getCurrentTime(),
            "Вкратце о том, как попасть дальше без лишней воды\n\n" +
                    "Выполнение задач для перехода на следующий уровень:\n" +
                    " https://wika.kata.academy/pages/viewpage.action?pageId=33161989" +
                    " \n\nОтправлено: " + getCurrentTime(),
            "В наше время невозможно обучаться ЭФФЕКТИВНО без нейросетей, держи\n\n" +
                    "Как эффективно пользоваться нейросетями: \n" +
                    " https://wika.kata.academy/pages/viewpage.action?pageId=47319075 \n\nОтправлено: " + getCurrentTime()
    );

    public BroadcastService(TelegramBotService botService, KeyboardFactory keyboardFactory, StickerService stickerService) {
        this.botService = botService;
        this.keyboardFactory = keyboardFactory;
        this.stickerService = stickerService;
    }

    /**
     * Регистрируем пользователя для рассылки
     * */
    public void subscribeUser(Long chatId) {
        subscribers.add(chatId);
        System.out.println("✅ Пользователь " + chatId + " подписался на рассылку");

        // Инициализируем очередь сообщений для нового пользователя
        initializeUserQueue(chatId);
    }

    // Отписываем пользователя
    public void unsubscribeUser(Long chatId) {
        subscribers.remove(chatId);
        userMessageQueues.remove(chatId);
        lastBroadcastTime.remove(chatId);
        System.out.println("❌ Пользователь " + chatId + " отписался от рассылки");
    }

    public boolean isSubscribed(Long chatId) {
        return subscribers.contains(chatId);
    }

    public Set<Long> getSubscribers() {
        return new HashSet<>(subscribers);
    }

    // Инициализирует очередь сообщений для пользователя
    private void initializeUserQueue(Long chatId) {
        Queue<String> queue = new ArrayDeque<>(messageSequence);
        userMessageQueues.put(chatId, queue);
        System.out.println("Инициализирована очередь из " + queue.size() + " сообщений для пользователя " + chatId);
    }

    /**
     * Запускает рассылку следующего сообщения (кол-во счетчик * кол-во таймер * милисекунды)
     * */
    @Scheduled(fixedRate = 1 * 5 * 1000)
    public void processMessageQueues() {
        System.out.println("Проверка очередей сообщений...");

        for (Long chatId : subscribers) {
            try {
                sendNextMessage(chatId);
            } catch (Exception e) {
                System.err.println("❌ Ошибка обработки очереди для пользователя " + chatId + ": " + e.getMessage());

                // Если пользователь заблокировал бота, удаляем его
                if (e.getMessage() != null && (
                        e.getMessage().contains("bot was blocked") ||
                                e.getMessage().contains("chat not found") ||
                                e.getMessage().contains("Forbidden"))) {
                    removeBlockedUser(chatId);
                }
            }
        }
    }

    // Отправляет следующее сообщение конкретному пользователю
    public void sendNextMessage(Long chatId) throws TelegramApiException {
        if (!subscribers.contains(chatId)) {
            throw new IllegalStateException("Пользователь " + chatId + " не подписан на рассылку");
        }

        Queue<String> queue = userMessageQueues.get(chatId);

        // Если очередь не инициализирована или пуста
        if (queue == null || queue.isEmpty()) {
            // Переинициализируем очередь
            initializeUserQueue(chatId);
            queue = userMessageQueues.get(chatId);
        }

        // Берем следующее сообщение из очереди
        String message = queue.poll();

        if (message != null) {
            // Добавляем текущее время к сообщению
            String finalMessage = message.replace("_Отправлено: " + getCurrentTime() + "_",
                    "_Отправлено: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + "_");

            sendMessageToUser(chatId, finalMessage);
            lastBroadcastTime.put(chatId, LocalDateTime.now());

            System.out.println(String.format(
                    "Пользователю %d отправлено сообщение %d/%d",
                    chatId,
                    messageSequence.size() - queue.size(),
                    messageSequence.size()
            ));

            // Если очередь пуста после отправки
            if (queue.isEmpty()) {
                sendCompletionMessage(chatId);
            }
        }
    }

    /**
     * Отправляет сообщение об окончании последовательности (ачивку)
     * */
    private void sendCompletionMessage(Long chatId) throws TelegramApiException {
        String completionMessage = " Ты получил все советы «Юного разработчки», поздравляю!\n\n" +
                "Теперь это ты ";
                stickerService.sendStickerByName(chatId, "usual");

        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(completionMessage);
        message.setReplyMarkup(keyboardFactory.createMainMenuKeyboard(chatId));

        botService.execute(message);

        removeBlockedUser(chatId);

        System.out.println("Пользователь " + chatId + " завершил последовательность сообщений");
    }

    /**
     * Отправка сообщения пользователю (вспомогательный метод)
     * */
    private void sendMessageToUser(Long chatId, String text) throws TelegramApiException {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setReplyMarkup(keyboardFactory.createMainMenuKeyboard(chatId));

        botService.execute(message);
    }


    /**
     * Удаление пользователя из рассылки
     * */
    private void removeBlockedUser(Long chatId) {
        subscribers.remove(chatId);
        userMessageQueues.remove(chatId);
        lastBroadcastTime.remove(chatId);
        System.out.println("Удален пользователь для рассылки " + chatId + " пошёл всю последовательность");
    }

    // Получение текущего времени в красивом формате
    private String getCurrentTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }
}