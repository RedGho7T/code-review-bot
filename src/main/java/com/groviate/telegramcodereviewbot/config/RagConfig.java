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

    private String url = "http://localhost:8000";

    //Сколько документов извлекать при поиске релевантных стандартов
    private int topK = 5;

    //Минимальный порог подобия для включения документа
    private double similarityThreshold = 0.4;

    //Сколько символов может быть в одном куске документа (chunk)
    private int chunkSize = 1000;

    //Перекрытие между chunks при разбиении документа
    private int chunkOverlap = 200;

    //Размер итогового контекста, выбран оптимальный для экономии цены запросов
    private int maxRagCharsTotal = 8000;

    //Максимальное количество символов документа (chunk'а)
    private int maxRagCharsPerDoc = 1500;

    //Берем два документа максимум из одного источника
    private int maxRagDocsPerSource = 2;

    //Максимум 3 источника
    private int maxRagSources = 3;

    // Максимальная длина текста для конвертирования и отправки в embeddingModel
    private int maxEmbeddingQueryChars = 15000;


}
