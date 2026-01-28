package com.groviate.telegramcodereviewbot.service;

import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendSticker;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Service
public class StickerService {

    private final TelegramBotService botService;

    private final java.util.Map<String, String> stickerMap = java.util.Map.of(
            "usual", "CAACAgIAAxkBAAK7PWlzffvpjnpbvgKVr7ult_Q9mn0kAAL4mAACOvihS2g-HPcRolcWOAQ",
            "first", "CAACAgIAAxkBAAK7SmlzooHnH9D2KYbEDFVDt9OjyiiHAAJtjQACFregS1cdlmo9uwXYOAQ"

    );

    public StickerService(TelegramBotService botService) {
        this.botService = botService;
    }

    /**
     * Отправляет стикер по ID
     */
    public void sendSticker(Long chatId, String stickerId) throws TelegramApiException {
        SendSticker sendSticker = new SendSticker();
        sendSticker.setChatId(chatId.toString());
        sendSticker.setSticker(new InputFile(stickerId));

        botService.execute(sendSticker);
    }

    /**
     * Отправляет стикер по названию
     */
    public void sendStickerByName(Long chatId, String stickerName) throws TelegramApiException {
        String stickerId = stickerMap.get(stickerName);
        if (stickerId != null) {
            sendSticker(chatId, stickerId);
        } else {
            System.err.println("❌ Стикер '" + stickerName + "' не найден");
        }
    }
}
