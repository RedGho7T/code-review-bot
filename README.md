# Telegram Code Review Bot (GitLab MR → AI Review)

Spring Boot приложение, которое автоматически делает code review для **GitLab Merge Request** с помощью **Spring AI + OpenAI**, а затем публикует результат в MR комментариями (общий комментарий + опционально inline-комментарии по строкам diff). Поддерживает RAG (Retrieval-Augmented Generation) на базе **ChromaDB** и набора внутренних документов со стандартами кодирования.

---

## Возможности

- **Автоматический запуск ревью** по GitLab Webhook на события Merge Request.
- **Асинхронная обработка** MR в отдельном пуле потоков (webhook отвечает быстро).
- **Публикация результата в GitLab**:
  - общий Markdown-комментарий,
  - (опционально) inline-комментарии к конкретным строкам diff.
- **RAG (опционально)**: подтягивание релевантных стандартов кодирования через ChromaDB.
- **Хранение статуса ревью в Postgres** (`mr_review_status`) — дедупликация по `head_sha`, защита от повторов.

---

## Как работает (High-level)

1. **GitLab** отправляет webhook на:
   - `POST /api/webhook/gitlab/merge-request`
2. Контроллер валидирует `X-Gitlab-Token`, проверяет событие и `action` (open/reopen/update), извлекает `projectId` и `mrIid`, отправляет ревью в очередь.
3. `ReviewOrchestrator`:
   - получает MR и diffs через GitLab API,
   - пропускает Draft/WIP и не-opened MR,
   - фиксирует статус в БД (RUNNING) и блокирует дубль по `head_sha`,
   - запускает `CodeReviewService`,
   - публикует результат через `GitLabCommentService`,
   - обновляет статус (SUCCESS/FAILED).
4. `CodeReviewService`:
   - (опционально) собирает `{rag_context}` через RAG,
   - формирует промпт из шаблонов (`system-prompt.txt`, `user-prompt.txt`),
   - вызывает OpenAI через Spring AI `ChatClient`,
   - парсит JSON в `CodeReviewResult`.
5. `GitLabCommentService` публикует общий комментарий и (опционально) inline обсуждения.

---

## Основные компоненты

### Webhook / Оркестрация
- **`MergeRequestWebhookController`** — прием webhook, валидация secret, постановка задач.
- **`ReviewOrchestrator`** — оркестрация всего пайплайна MR → diffs → AI → GitLab.

### GitLab API
- **`GitLabMergeRequestClient`** — получение MR/changes/diff refs, публикация comments/discussions.

### AI Review
- **`AiChatConfig`** — создание `ChatClient` (Spring AI).
- **`CodeReviewService`** — сборка промпта, вызов модели, парсинг результата.
- **`PromptTemplateService`** — подстановка `{title}`, `{description}`, `{rag_context}`, `{code_changes}` в шаблоны.

### RAG (ChromaDB)
- **`DocumentIndexingService`** — индексирует документы из `resources/rag-documents/` при старте.
- **`RagContextService`** — embedding + query в ChromaDB + сборка контекста с лимитами.

### Публикация и форматирование
- **`CommentFormatterService`** — Markdown формат ответа.
- **`InlineCommentPlannerService`** — планирование inline-comment’ов по лимитам.

### Асинхронность и надежность
- **`AsyncConfig`** — пул `reviewExecutor`.
- **`ReviewStatus` (+ repository)** — хранение статуса в Postgres.

---

## RAG: режимы Full vs Economy (переключаются флагом)

### Зачем два режима
- **FULL**: RAG строится *для каждого файла* — “полнее”, но дороже и часто раздувает `{rag_context}`.
- **ECONOMY**: дешевле:
  - 1 общий RAG по всему MR (сводка diffs),
  - + отдельный RAG только для **top-N** самых больших файлов,
  - жёсткие лимиты на размер и дедупликация повторов.

