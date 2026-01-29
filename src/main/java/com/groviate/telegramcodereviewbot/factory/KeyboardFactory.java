package com.groviate.telegramcodereviewbot.factory;

import com.groviate.telegramcodereviewbot.entity.Level;
import com.groviate.telegramcodereviewbot.service.UserProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class KeyboardFactory {

    private final UserProgressService userProgressService;

    public ReplyKeyboardMarkup createMainMenuKeyboard(Long chatId) {
        int totalPoints = userProgressService.getUserTotalPoints(chatId);

        boolean level1Unlocked = true;
        boolean level2Unlocked = totalPoints >= 100;
        boolean level3Unlocked = totalPoints >= 200;

        List<KeyboardRow> keyboard = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton("🎯 Выбрать уровень"));
        row1.add(new KeyboardButton("📊 Моя статистика"));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton("ℹ️ О проекте"));
        row2.add(new KeyboardButton("🚀 Первые шаги"));

        KeyboardRow row3 = new KeyboardRow();
        row3.add(new KeyboardButton(level1Unlocked ? "🔓 Уровень 1" : "🔒 Уровень 1"));
        row3.add(new KeyboardButton(level2Unlocked ? "🔓 Уровень 2" : "🔒 Набери 100 очков"));

        KeyboardRow row4 = new KeyboardRow();
        row4.add(new KeyboardButton(level3Unlocked ? "🔓 Уровень 3" : "🔒 Набери 200 очков"));
        row4.add(new KeyboardButton("🏆 Лидерборд"));

        keyboard.add(row1);
        keyboard.add(row2);
        keyboard.add(row3);
        keyboard.add(row4);

        return ReplyKeyboardMarkup.builder()
                .keyboard(keyboard)
                .resizeKeyboard(true)
                .oneTimeKeyboard(false)
                .selective(true)
                .build();
    }

    public ReplyKeyboardMarkup createLevelSelectionKeyboard(Long chatId) {
        List<KeyboardRow> keyboard = new ArrayList<>();

        for (Level level : Level.values()) {
            KeyboardRow row = new KeyboardRow();
            String buttonText = String.format("%s Уровень %d - %s",
                    level.getEmoji(),
                    level.getNumber(),
                    level.getName()
            );
            row.add(new KeyboardButton(buttonText));
            keyboard.add(row);
        }

        KeyboardRow backRow = new KeyboardRow();
        backRow.add(new KeyboardButton("⬅️ Главное меню"));
        keyboard.add(backRow);

        return ReplyKeyboardMarkup.builder()
                .keyboard(keyboard)
                .resizeKeyboard(true)
                .build();
    }

    public ReplyKeyboardMarkup createLevelTasksKeyboard(Long chatId, Level level) {
        List<KeyboardRow> keyboard = new ArrayList<>();

        level.getTasks().forEach(task -> {
            KeyboardRow row = new KeyboardRow();

            boolean completed = userProgressService.isTaskCompleted(chatId, task.id());
            String prefix = completed ? "✅ " : "📝 ";

            row.add(new KeyboardButton(prefix + task.name()));

            keyboard.add(row);
        });

        KeyboardRow backRow = new KeyboardRow();
        backRow.add(new KeyboardButton("⬅️ Назад к уровням"));
        keyboard.add(backRow);

        return ReplyKeyboardMarkup.builder()
                .keyboard(keyboard)
                .resizeKeyboard(true)
                .build();
    }

    public ReplyKeyboardMarkup createTaskDetailKeyboard(Long chatId, String taskId) {
        List<KeyboardRow> keyboard = new ArrayList<>();

        KeyboardRow completeRow = new KeyboardRow();
        completeRow.add(new KeyboardButton("✅ Я выполнил это задание!"));
        keyboard.add(completeRow);

        KeyboardRow backRow = new KeyboardRow();
        backRow.add(new KeyboardButton("⬅️ Назад к задачам"));
        keyboard.add(backRow);

        return ReplyKeyboardMarkup.builder()
                .keyboard(keyboard)
                .resizeKeyboard(true)
                .build();
    }

    public ReplyKeyboardMarkup createFirstStepsKeyboard() {
        List<KeyboardRow> keyboard = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton("Установка окружения"));
        row1.add(new KeyboardButton("Настройка IDE"));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton("Первый запуск"));
        row2.add(new KeyboardButton("Git workflow"));

        KeyboardRow backRow = new KeyboardRow();
        backRow.add(new KeyboardButton("⬅️ Назад в меню"));

        keyboard.add(row1);
        keyboard.add(row2);
        keyboard.add(backRow);

        return ReplyKeyboardMarkup.builder()
                .keyboard(keyboard)
                .resizeKeyboard(true)
                .build();
    }
}
