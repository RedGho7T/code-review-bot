package com.groviate.telegramcodereviewbot.constants;

public final class BotButtons {
    private BotButtons() {}

    // Главное меню
    public static final String BTN_CHOOSE_LEVEL = "🎯 Выбрать уровень";
    public static final String BTN_STATS = "📊 Моя статистика";
    public static final String BTN_ABOUT = "ℹ️ О проекте";
    public static final String BTN_FIRST_STEPS = "🚀 Первые шаги";

    // Пороговые кнопки (очки)
    public static final String BTN_ADVANCED_TASKS = "🚀 Продвинутые задания";
    public static final String BTN_LOCKED_ADVANCED = "🔒 Набери 200 очков";

    public static final String BTN_LEADERBOARD = "🏆 Лидерборд";
    public static final String BTN_LOCKED_LEADERBOARD = "🔒 Набери 100 очков";

    // Навигация
    public static final String NAV_MAIN_MENU = "⬅️ Главное меню";
    public static final String NAV_BACK_LEVELS = "⬅️ Назад к уровням";
    public static final String NAV_BACK_TASKS = "⬅️ Назад к задачам";
    public static final String NAV_BACK_INTO_MENU = "⬅️ Назад в меню";

    // Действие по задаче
    public static final String BTN_TASK_DONE = "✅ Я выполнил это задание!";

    // Первые шаги (пункты)
    public static final String FS_ENV_SETUP = "Установка окружения";
    public static final String FS_IDE_SETUP = "Настройка IDE";
    public static final String FS_FIRST_RUN = "Первый запуск";
    public static final String FS_GIT_WORKFLOW = "Git workflow";
}