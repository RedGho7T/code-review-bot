package com.groviate.telegramcodereviewbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO для хранения информации об одном измененном файле в MR.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MergeRequestDiff {

    @JsonProperty("old_path")
    private String oldPath; //Путь к файлу до изменений (null, если файл новый)

    @JsonProperty("new_path")
    private String newPath; //Путь к файлу после изменений

    @JsonProperty("new_file")
    private Boolean newFile; //true - если файл новый, false - изменили существующий

    @JsonProperty("deleted_file")
    private Boolean deletedFile; //true если файл удален

    @JsonProperty("renamed_file")
    private Boolean renamedFile; //true если файл переименован

    private String diff; //Строки с изменениями (с + и - для добавленных/удаленных строк)

    @JsonProperty("a_mode")
    private String aMode; // Режим файла "до" (100644, 100755)

    @JsonProperty("b_mode")
    private String bMode; //Режим файла после

    /**
     * Проверяет на новый созданный файл (раньше не существовал)
     *
     * @return true если файл новый, false иначе
     */
    public boolean isNewFile() {
        return newFile != null && newFile;
    }

    /**
     * Проверяет файл на удаление в MR.
     *
     * @return true если файл удалён, false иначе
     */
    public boolean isDeletedFile() {
        return deletedFile != null && deletedFile;
    }

    /**
     * Проверяет переименование файла в этом MR.
     *
     * @return true если файл переименован, false иначе
     */
    public boolean isRenamedFile() {
        return renamedFile != null && renamedFile;
    }
}
