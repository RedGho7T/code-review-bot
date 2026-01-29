package com.groviate.telegramcodereviewbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendSticker;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Map;

@Slf4j
@Service
public class StickerService {

    private final TelegramClient telegramClient;

    private final Map<String, String> stickerMap = Map.of(
            "usual", "CAACAgIAAxkBAAK7PWlzffvpjnpbvgKVr7ult_Q9mn0kAAL4mAACOvihS2g-HPcRolcWOAQ",
            "first", "CAACAgIAAxkBAAK7SmlzooHnH9D2KYbEDFVDt9OjyiiHAAJtjQACFregS1cdlmo9uwXYOAQ"
    );

    public StickerService(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    public void sendSticker(Long chatId, String stickerId) throws TelegramApiException {
        SendSticker sendSticker = SendSticker.builder()
                .chatId(chatId.toString())
                .sticker(new InputFile(stickerId))
                .build();

        telegramClient.execute(sendSticker);
    }

    public void sendStickerByName(Long chatId, String stickerName) throws TelegramApiException {
        String stickerId = stickerMap.get(stickerName);
        if (stickerId == null) {
            log.warn("❌ Стикер '{}' не найден", stickerName);
            return;
        }
        sendSticker(chatId, stickerId);
    }
}