package com.groviate.telegramcodereviewbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Для хранения и управления параметрами RAG. Загружается из application.yml
 */
@Component
@ConfigurationProperties(prefix = "chroma")
@Data
public class RagConfig {

    // Подключение к ChromaDB
    private String url = "http://localhost:8000";


    // Поиск релевантных документов (retrieval)

    /**
     * Сколько кандидатов (документов/чанков) запрашивать из ChromaDB за один поиск.
     * Используется при query() в Chroma.
     */
    private int topK = 5;

    /**
     * Минимальный порог похожести (similarity) для включения документа в итоговый контекст.
     * Документы ниже порога будут отброшены.
     */
    private double similarityThreshold = 0.4;


    // Индексация RAG-документов (чанки)

    /**
     * Размер чанка (в символах) при разбиении документов стандартов на части перед загрузкой в ChromaDB.
     * Влияет на “детализацию” поиска: меньше chunkSize = более точные фрагменты, но больше чанков.
     */
    private int chunkSize = 1000;

    /**
     * Перекрытие (overlap) между чанками при разбиении.
     * Нужен, чтобы важные фразы на границе чанков не терялись.
     */
    private int chunkOverlap = 200;


    // Лимиты контекста на один запрос RAG (внутри RagContextService)
    // Эти параметры контролируют, сколько текста вернёт метод getContextForCode(code)
    // из найденных документов: общий лимит, лимит на документ, лимиты по источникам.

    /**
     * Максимальный общий размер (символов) контекста, который собирается из найденных документов
     * в рамках ОДНОГО вызова RAG (getContextForCode).
     */
    private int maxRagCharsTotal = 8000;

    /**
     * Максимальный размер (символов) одного документа/чанка, добавляемого в контекст.
     * Если документ длиннее — будет обрезан.
     */
    private int maxRagCharsPerDoc = 1500;

    /**
     * Максимум документов (чанков) из одного источника (одного файла стандартов).
     * Нужен, чтобы контекст не “залип” в одном документе.
     */
    private int maxRagDocsPerSource = 2;

    /**
     * Максимум различных источников (файлов стандартов), из которых можно взять чанки.
     */
    private int maxRagSources = 3;


    // Ограничение входа для embeddings

    /**
     * Максимальная длина текста (символов), отправляемого в embedding model.
     * Если вход длиннее — обрежем, чтобы не улететь в лимиты токенов/размер запроса.
     */
    private int maxEmbeddingQueryChars = 15000;


    // Стратегия сборки RAG-контекста для MR (общий + top-N файлов)

    /**
     * Экономичный режим RAG:
     * True — 1 общий RAG по MR + доп. RAG для top-N самых больших файлов (дешевле и обычно достаточно)</li>
     * False — RAG по каждому файлу (дороже, может давать больше “локальных” правил)</li>
     */
    private boolean ragEconomyMode = true;

    /**
     * Сколько “самых больших” файлов (по размеру diff) брать для дополнительного RAG в economy mode.
     */
    private int ragTopFiles = 5;


    // Бюджеты итогового RAG, который попадёт в промпт (после объединения)

    /**
     * Максимальный размер (символов) итогового RAG-контекста, который попадёт в промпт.
     * Ограничение применяется после объединения общего контекста + контекста по топ-файлам.
     */
    private int ragMaxContextCharsTotal = 12000;

    /**
     * Бюджет символов на общий RAG по всему MR (один запрос по “сводке” MR/изменений).
     */
    private int ragMaxContextCharsGeneral = 4500;

    /**
     * Бюджет символов на RAG для одного файла из топ-N самых больших diffs.
     */
    private int ragMaxContextCharsPerFile = 1500;

    /**
     * Индексация документов при старте
     */
    private boolean indexingEnabled = true;
}
