package com.groviate.telegramcodereviewbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "chroma")
@Data
public class RagConfig {

    private String url = "http://localhost:8000";

    //Сколько топ-документов извлекать при поиске релевантных стандартов
    private int topK = 5;

    //Минимальный порог подобия для включения документа
    private double similarityThreshold = 0.4;

    //Сколько символов может быть в одном куске документа (chunk)
    private int chunkSize = 1000;

    //Перекрытие между chunks при разбиении документа
    private int chunkOverlap = 200;

    // Ограничения RAG результата
    private int maxRagCharsTotal = 8000;
    private int maxRagCharsPerDoc = 1500;
    private int maxRagDocsPerSource = 2;
    private int maxRagSources = 3;

    // Ограничение входа в embeddings
    private int maxEmbeddingQueryChars = 20000;


}
