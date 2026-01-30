package com.groviate.telegramcodereviewbot.factory;

import com.groviate.telegramcodereviewbot.entity.Level;
import com.groviate.telegramcodereviewbot.service.UserProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import com.groviate.telegramcodereviewbot.constants.BotButtons;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class KeyboardFactory {

    private final UserProgressService userProgressService;


    public ReplyKeyboardMarkup createMainMenuKeyboard(Long chatId) {
        int totalPoints = userProgressService.getUserTotalPoints(chatId);

        List<KeyboardRow> keyboard = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton(BotButtons.BTN_CHOOSE_LEVEL));
        row1.add(new KeyboardButton(BotButtons.BTN_STATS));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton(BotButtons.BTN_ABOUT));
        row2.add(new KeyboardButton(BotButtons.BTN_FIRST_STEPS));

        KeyboardRow row3 = new KeyboardRow();
        if (totalPoints >= 200) {
            row3.add(BotButtons.BTN_ADVANCED_TASKS);
        } else {
            row3.add(BotButtons.BTN_LOCKED_ADVANCED);
        }

        if (totalPoints >= 100) {
            row3.add(BotButtons.BTN_LEADERBOARD);
        } else {
            row3.add(BotButtons.BTN_LOCKED_LEADERBOARD);
        }

        keyboard.add(row1);
        keyboard.add(row2);
        keyboard.add(row3);

        return ReplyKeyboardMarkup.builder()
                .keyboard(keyboard)
                .resizeKeyboard(true)
                .oneTimeKeyboard(false)
                .selective(true)
                .build();
    }

    public ReplyKeyboardMarkup createLevelSelectionKeyboard() {
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
        backRow.add(new KeyboardButton(BotButtons.NAV_MAIN_MENU));
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
        backRow.add(new KeyboardButton(BotButtons.NAV_BACK_LEVELS));
        keyboard.add(backRow);

        return ReplyKeyboardMarkup.builder()
                .keyboard(keyboard)
                .resizeKeyboard(true)
                .build();
    }

    public ReplyKeyboardMarkup createTaskDetailKeyboard() {
        List<KeyboardRow> keyboard = new ArrayList<>();

        KeyboardRow completeRow = new KeyboardRow();
        completeRow.add(new KeyboardButton(BotButtons.BTN_TASK_DONE));
        keyboard.add(completeRow);

        KeyboardRow backRow = new KeyboardRow();
        backRow.add(new KeyboardButton(BotButtons.NAV_BACK_TASKS));
        keyboard.add(backRow);

        return ReplyKeyboardMarkup.builder()
                .keyboard(keyboard)
                .resizeKeyboard(true)
                .build();
    }

    public ReplyKeyboardMarkup createFirstStepsKeyboard() {
        List<KeyboardRow> keyboard = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton(BotButtons.FS_ENV_SETUP));
        row1.add(new KeyboardButton(BotButtons.FS_IDE_SETUP));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton(BotButtons.FS_FIRST_RUN));
        row2.add(new KeyboardButton(BotButtons.FS_GIT_WORKFLOW));

        KeyboardRow backRow = new KeyboardRow();
        backRow.add(new KeyboardButton(BotButtons.NAV_BACK_INTO_MENU));

        keyboard.add(row1);
        keyboard.add(row2);
        keyboard.add(backRow);

        return ReplyKeyboardMarkup.builder()
                .keyboard(keyboard)
                .resizeKeyboard(true)
                .build();
    }
}
