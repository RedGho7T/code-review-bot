package com.groviate.telegramcodereviewbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO для десериализации JSON ответов от GitLab API в Java объекты.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MergeRequest {

    @JsonProperty("diffs")
    private List<MergeRequestDiff> diffs;

    //Основные поля
    @JsonProperty("id") //Глобальный ID MR во всем GitLab
    private Integer id;

    @JsonProperty("iid") //Уникальный id MR внутри проекта
    private Integer iid;

    @JsonProperty("project_id") //ID проекта (audit-service = 24)
    private Integer projectId;

    @JsonProperty("title") //Название MR
    private String title;

    @JsonProperty("description") // Полное описание MR
    private String description;

    //Ветки
    @JsonProperty("source_branch") //Откуда делаем MR ("feature/fix")
    private String sourceBranch;

    @JsonProperty("target_branch")
    private String targetBranch; //Куда слияние -> dev

    //Статусы
    @JsonProperty("state")
    private String status; //Статус MR, возможные значения: "opened", "merged", "closed"

    @JsonProperty("merge_status")
    private String mergeStatus; // Возможность текущего слияния ("can_be_merged" / "cannot_be_merged")

    @JsonProperty("detailed_merge_status")
    private String detailedMrStatus; //"conflict", "preparing", "mergeable"...

    @JsonProperty("has_conflicts")
    private Boolean hasConflicts; // true - есть конфликты мерджа / false - можно смержить

    //Автор и статистика
    @JsonProperty("author")
    private Author author; // Информация об авторе

    @JsonProperty("user_notes_count")
    private Integer userNotesCount; //Количество комментариев

    //Время
    @JsonProperty("created_at")
    private String createdAt; //Когда создан MR

    @JsonProperty("updated_at")
    private String updatedAt; //Время последнего изменения

    @JsonProperty("merged_at")
    private String mergedAt; //Когда было слияние

    @JsonProperty("closed_at")
    private String closedAt; //Когда был закрыт

    //Дополнительные
    @JsonProperty("web_url")
    private String webUrl; //Ссылка на MR

    @JsonProperty("sha")
    private String sha; //Git-хэш последнего коммита

    @JsonProperty("draft")
    private Boolean draft; //true - черновик MR

    @JsonProperty("work_in_progress")
    private Boolean workInProgress; //true - MR помечен как черновик и не готов к слиянию

    @JsonProperty("squash")
    private Boolean squash; //true = делать сжатие в один коммит при мерже

    @JsonProperty("diff_refs")
    private MergeRequestDiffRefs diffRefs;

    /**
     * Вложенный класс для автора MR
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Author {
        @JsonProperty("id")
        private Integer id; //Уникальный id на Gitlab

        @JsonProperty("name")
        private String name; //Имя в профиле Gitlab

        @JsonProperty("username")
        private String username; //Ник в Gitlab

        @JsonProperty("state")
        private String state; //Статус пользователя на Gitlab

        @JsonProperty("avatar_url")
        private String avatarUrl;

        @JsonProperty("web_url")
        private String webUrl;
    }
}
