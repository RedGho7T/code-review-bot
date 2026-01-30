package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.factory.KeyboardFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class BroadcastService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TelegramClient telegramClient;
    private final KeyboardFactory keyboardFactory;
    private final StickerService stickerService;

    private final Set<Long> subscribers = ConcurrentHashMap.newKeySet();
    private final Map<Long, Queue<String>> userMessageQueues = new ConcurrentHashMap<>();
    private final Map<Long, LocalDateTime> lastBroadcastTime = new ConcurrentHashMap<>();

    private final List<String> messageTemplates = List.of(
            """
                    Давай немного помогу разобраться с состоянием «В тупике»
                    
                    Работа со стрессом и состоянием:
                    https://wika.kata.academy/pages/viewpage.action?pageId=40829162
                    
                    _Отправлено: %s_
                    """,
            """
                    Маленький, но полезный совет по работе в проекте который тебе поможет
                    
                    Инициатива в проекте:
                    https://wika.kata.academy/pages/viewpage.action?pageId=47319701
                    
                    _Отправлено: %s_
                    """,
            """
                    А вот этот словарь, явно, поможет тебе понимать коллег и привыкнуть к сленгу
                    
                    https://wika.kata.academy/pages/viewpage.action?pageId=25035466
                    
                    _Отправлено: %s_
                    """,
            """
                    Пройдя этот путь, вот, что ты покоришь
                    
                    Навыки получаемые на проекте:
                    https://wika.kata.academy/pages/viewpage.action?pageId=33161993
                    
                    _Отправлено: %s_
                    """,
            """
                    Вкратце о том, как попасть дальше без лишней воды
                    
                    Выполнение задач для перехода на следующий уровень:
                    https://wika.kata.academy/pages/viewpage.action?pageId=33161989
                    
                    _Отправлено: %s_
                    """,
            """
                    В наше время невозможно обучаться ЭФФЕКТИВНО без нейросетей, держи
                    
                    Как эффективно пользоваться нейросетями:
                    https://wika.kata.academy/pages/viewpage.action?pageId=47319075
                    
                    _Отправлено: %s_
                    """
    );

    public BroadcastService(TelegramClient telegramClient,
                            KeyboardFactory keyboardFactory,
                            StickerService stickerService) {
        this.telegramClient = telegramClient;
        this.keyboardFactory = keyboardFactory;
        this.stickerService = stickerService;
    }

    public void subscribeUser(Long chatId) {
        subscribers.add(chatId);
        initializeUserQueue(chatId);
        log.info("✅ Пользователь {} подписался на рассылку", chatId);
    }

    public boolean isSubscribed(Long chatId) {
        return subscribers.contains(chatId);
    }

    private void initializeUserQueue(Long chatId) {
        Queue<String> queue = new ArrayDeque<>(messageTemplates);
        userMessageQueues.put(chatId, queue);
        log.debug("Очередь сообщений инициализирована: chatId={}, size={}", chatId, queue.size());
    }

    @Scheduled(fixedRateString = "${telegram.broadcast.fixed-rate-ms:5000}")
    public void processMessageQueues() {
        log.debug("Broadcast tick. Subscribers={}", subscribers.size());

        for (Long chatId : subscribers) {
            try {
                sendNextMessage(chatId);
            } catch (Exception e) {
                log.warn("❌ Ошибка обработки очереди для chatId={}", chatId, e);

                String msg = e.getMessage();
                if (msg != null && (msg.contains("bot was blocked") || msg.contains("chat not found")
                        || msg.contains("Forbidden"))) {
                    removeBlockedUser(chatId);
                }
            }
        }
    }

    public void sendNextMessage(Long chatId) throws TelegramApiException {
        if (!subscribers.contains(chatId)) {
            throw new IllegalStateException("Пользователь " + chatId + " не подписан на рассылку");
        }

        Queue<String> queue = userMessageQueues.computeIfAbsent(chatId,
                id -> new ArrayDeque<>(messageTemplates));

        if (queue.isEmpty()) {
            initializeUserQueue(chatId);
            queue = userMessageQueues.get(chatId);
        }

        String template = queue.poll();
        if (template == null) {
            return;
        }

        String finalMessage = template.formatted(LocalDateTime.now().format(TIME_FORMAT));

        sendMessageToUser(chatId, finalMessage);
        lastBroadcastTime.put(chatId, LocalDateTime.now());

        int sent = messageTemplates.size() - queue.size();
        log.debug("Broadcast: chatId={} sent={}/{}", chatId, sent, messageTemplates.size());

        if (queue.isEmpty()) {
            sendCompletionMessage(chatId);
        }
    }

    private void sendCompletionMessage(Long chatId) throws TelegramApiException {
        String completionMessage = """
                Ты получил все советы «Юного разработчика», поздравляю!
                
                Теперь это ты 😎
                """;

        stickerService.sendStickerByName(chatId, "usual");

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(completionMessage)
                .parseMode(ParseMode.MARKDOWN)
                .replyMarkup(keyboardFactory.createMainMenuKeyboard(chatId))
                .build();

        telegramClient.execute(message);

        removeBlockedUser(chatId);
        log.info("Пользователь {} завершил последовательность сообщений", chatId);
    }

    private void sendMessageToUser(Long chatId, String text) throws TelegramApiException {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode(ParseMode.MARKDOWN)
                .replyMarkup(keyboardFactory.createMainMenuKeyboard(chatId))
                .build();

        telegramClient.execute(message);
    }

    private void removeBlockedUser(Long chatId) {
        subscribers.remove(chatId);
        userMessageQueues.remove(chatId);
        lastBroadcastTime.remove(chatId);
        log.info("Удален пользователь из рассылки chatId={}", chatId);
    }
}