package com.groviate.telegramcodereviewbot.factory;


import com.groviate.telegramcodereviewbot.entity.Level;
import com.groviate.telegramcodereviewbot.entity.User;
import com.groviate.telegramcodereviewbot.service.UserProgressService;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.ArrayList;
import java.util.List;

@Component
public class KeyboardFactory {

    private final UserProgressService userProgressService;

    public KeyboardFactory(UserProgressService userProgressService) {
        this.userProgressService = userProgressService;
    }

    // Главное меню
    public ReplyKeyboardMarkup createMainMenuKeyboard(Long chatId) {
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setSelective(true);
        keyboardMarkup.setResizeKeyboard(true);
        keyboardMarkup.setOneTimeKeyboard(false);

        List<KeyboardRow> keyboard = new ArrayList<>();

        // Первый ряд
        KeyboardRow row1 = new KeyboardRow();
        row1.add("🚀 Первые шаги");
        row1.add("\uD83C\uDFAF Выбрать уровень");

        // Второй ряд
        KeyboardRow row2 = new KeyboardRow();
        row2.add("ℹ️ О проекте");
        row2.add("\uD83D\uDCCA Моя статистика");

        KeyboardRow row3 = new KeyboardRow();

        User user = userProgressService.getOrCreateUser(chatId, "", "", "");

        if (user.getTotalPoints() >= 200) {
            row3.add("🚀 Продвинутые задания");
        }

        if (user.getTotalPoints() >= 100) {
            row3.add("🏆 Лидерборд");
        }

        keyboard.add(row1);
        keyboard.add(row2);
        keyboard.add(row3);

        keyboardMarkup.setKeyboard(keyboard);
        return keyboardMarkup;
    }

    // Меню первых шагов
    public ReplyKeyboardMarkup createFirstStepsKeyboard() {
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setResizeKeyboard(true);

        List<KeyboardRow> keyboard = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("1. Установка окружения");
        row1.add("2. Настройка IDE");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("3. Первый запуск");
        row2.add("4. Git workflow");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("⬅️ Назад в меню");

        keyboard.add(row1);
        keyboard.add(row2);
        keyboard.add(row3);

        keyboardMarkup.setKeyboard(keyboard);
        return keyboardMarkup;
    }

    public ReplyKeyboardMarkup createLevelSelectionKeyboard(Long chatId) {
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setResizeKeyboard(true);
        keyboardMarkup.setOneTimeKeyboard(false);

        List<KeyboardRow> keyboard = new ArrayList<>();
        KeyboardRow currentRow = new KeyboardRow();

        // Получаем все уровни
        Level[] levels = Level.values();

        for (int i = 0; i < levels.length; i++) {
            Level level = levels[i];

            // Проверяем доступность уровня
            boolean isAccessible = userProgressService.isLevelAccessible(chatId, level.getNumber());
            String buttonText = isAccessible ?
                    level.getEmoji() + " " + level.getName() :
                    "🔒 Уровень " + level.getNumber();

            currentRow.add(buttonText);

            // Добавляем ряд когда в нем 2 элемента ИЛИ это последний элемент
            if (currentRow.size() == 2 || i == levels.length - 1) {
                keyboard.add(currentRow);
                currentRow = new KeyboardRow(); // Создаем новый ряд
            }
        }

        // Добавляем кнопку назад
        KeyboardRow backRow = new KeyboardRow();
        backRow.add("⬅️ Главное меню");
        keyboard.add(backRow);

        keyboardMarkup.setKeyboard(keyboard);
        return keyboardMarkup;
    }

    /**
     * Клавиатура заданий для конкретного уровня
     */
    public ReplyKeyboardMarkup createLevelTasksKeyboard(Long chatId, Level level) {
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setResizeKeyboard(true);
        keyboardMarkup.setOneTimeKeyboard(false);
        keyboardMarkup.setSelective(true);

        List<KeyboardRow> keyboard = new ArrayList<>();
        KeyboardRow currentRow = new KeyboardRow();
        int countInRow = 0;

        for (Level.Task task : level.getTasks()) {
            // Проверяем статус задачи
            boolean isCompleted = userProgressService.isTaskCompleted(chatId, task.getId());

            String buttonText = isCompleted
                    ? "✅ " + task.getName()
                    : "📝 " + task.getName();

            currentRow.add(buttonText);
            countInRow++;

            // Если уже 2 кнопки в ряду — добавляем ряд в клавиатуру и начинаем новый
            if (countInRow == 2) {
                keyboard.add(currentRow);
                currentRow = new KeyboardRow();
                countInRow = 0;
            }
        }

        // Если остались кнопки в «незаполненном» ряду, тоже добавляем его
        if (!currentRow.isEmpty()) {
            keyboard.add(currentRow);
        }

        KeyboardRow backRow = new KeyboardRow();
        backRow.add("⬅️ Назад к уровням");
        keyboard.add(backRow);

        keyboardMarkup.setKeyboard(keyboard);
        return keyboardMarkup;
    }

    /**
     * Клавиатура для страницы задачи
     */
    public ReplyKeyboardMarkup createTaskDetailKeyboard(Long chatId, String taskId) {
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setResizeKeyboard(true);
        keyboardMarkup.setOneTimeKeyboard(true);
        keyboardMarkup.setSelective(true);

        List<KeyboardRow> keyboard = new ArrayList<>();

        // Проверяем, выполнена ли уже эта задача
        boolean isCompleted = userProgressService.isTaskCompleted(chatId, taskId);

        if (!isCompleted) {
            // Если задача не выполнена - кнопка для отметки выполнения
            KeyboardRow completeRow = new KeyboardRow();
            completeRow.add("✅ Я выполнил это задание!");
            keyboard.add(completeRow);
        }

        // Кнопка возврата - ОБЯЗАТЕЛЬНО добавить
        KeyboardRow backRow = new KeyboardRow();
        backRow.add("⬅️ Назад к задачам");
        keyboard.add(backRow);

        keyboardMarkup.setKeyboard(keyboard);
        return keyboardMarkup;
    }
}


