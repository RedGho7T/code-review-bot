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



# Инструкция по запуску проекта

## 1. Переменные окружения и конфиги

### 1.1. Локальный запуск (IDE / bootRun)

**Файл:** `.env.properties` (в корне проекта)

Создайте файл в корне проекта (рядом с `build.gradle`) с именем `.env.properties`

**Пример:**

_________________________________________________

```env
TELEGRAM_BOT_TOKEN=

TELEGRAM_BOT_USERNAME=

OPENAI_API_KEY=

GITLAB_API_URL=https://gitlab.groviate.com/api/v4

GITLAB_API_TOKEN=

LOGGING_LEVEL_ROOT=INFO

LOGGING_LEVEL_COM_GROVIATE=DEBUG

SERVER_SERVLET_CONTEXT_PATH=/telegram-bot

SPRING_PROFILES_ACTIVE=local

CHROMA_URL=http://localhost:8000

DB_URL=jdbc:postgresql://localhost:5432/review_bot

DB_USER=review_bot

DB_PASSWORD=review_bot

GITLAB_WEBHOOK_SECRET=
```

_________________________________________________

**Почему это работает:**

В `application.yml` подключён импорт:

```yaml
spring:
  config:
    import: "optional:file:./.env.properties"
```

Это означает:

- локально Spring подхватит переменные из файла
- если файла нет — запуск не упадёт (`optional`)

### 1.2. Прод (Docker Compose)

**Файл:** `.env` (в корне проекта)

Docker Compose сам читает `.env` рядом с `docker-compose.yml`.

**Пример `.env`:**

_________________________________________________

```env
TELEGRAM_BOT_TOKEN=

TELEGRAM_BOT_USERNAME=

OPENAI_API_KEY=

GITLAB_API_URL=https://gitlab.groviate.com/api/v4

GITLAB_API_TOKEN=

LOGGING_LEVEL_ROOT=INFO

LOGGING_LEVEL_COM_GROVIATE=DEBUG

SERVER_SERVLET_CONTEXT_PATH=/telegram-bot

SPRING_PROFILES_ACTIVE=local

CHROMA_URL=http://localhost:8000

DB_URL=jdbc:postgresql://localhost:5432/review_bot

DB_USER=review_bot

DB_PASSWORD=review_bot

GITLAB_WEBHOOK_SECRET=
```
_________________________________________________

> В Docker обычно **НЕ** кладут `.env.properties` внутрь контейнера — секреты передаются env-переменными через compose.

---

## 2. Локальный запуск (сервис в IDE, зависимости в Docker)

### 2.1. Поднять Postgres + Chroma в Docker

В корне проекта:

```bash
docker compose up -d postgres chromadb
```

Проверить, что всё поднялось:

```bash
docker compose ps
```

**Ожидаемо:**

- Postgres доступен на `localhost:5432`
- Chroma доступен на `localhost:8000`

### 2.2. Запуск приложения локально

**Вариант A — из IDE**

1. Убедитесь, что `.env.properties` в корне проекта заполнен
2. Запустите `TelegramCodeReviewBotApplication` (Run)

**Вариант B — через Gradle**

```bash
./gradlew bootRun
```

Профиль можно включать по желанию через env/IDE:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

> Если конфиги настроены корректно — обычно обходится без явного указания профиля.

---

## 3. Продакшен запуск в Docker (всё в Docker)

### 3.1. Сборка JAR

В корне проекта:

```bash
./gradlew clean bootJar
```

### 3.2. Запуск Docker Compose (prod)

```bash
docker compose --profile prod up -d --build
```

Проверить статус:

```bash
docker compose --profile prod ps
```

Посмотреть логи приложения:

```bash
docker compose --profile prod logs -f app
```

---

## 4. Ключевые различия local vs prod

### DB и Chroma URL

**Локально (app на хосте):**

- DB: `jdbc:postgresql://localhost:5432/review_bot`
- Chroma: `http://localhost:8000`

**В Docker (app внутри сети compose):**

- DB: `jdbc:postgresql://postgres:5432/review_bot`
- Chroma: `http://chromadb:8000`

> ⚠️ **Важно:** Нельзя использовать `localhost` внутри контейнера для доступа к другим контейнерам. Используйте имена сервисов из `docker-compose.yml`.

---

## 5. Быстрая диагностика проблем

### 5.1. Проверить, что переменные дошли в контейнер app

```bash
docker compose --profile prod exec app sh -lc 'env | sort | grep -E "GITLAB|OPENAI|TELEGRAM|DB_|CHROMA|SPRING_"'
```

### 5.2. Проверить, что docker compose реально подставил значения

```bash
docker compose --profile prod config
```

Если видите где-то пустые строки вместо токена — значит `.env` не заполнен или лежит не рядом с `docker-compose.yml`.

### 5.3. Сбросить БД (осторожно: удалит данные)

Если миграции/схема "поехали" и это тестовая среда:

```bash
docker compose --profile prod down -v
docker compose --profile prod up -d --build
```

---

## 6. Рекомендуемый порядок действий для нового разработчика

### Локально

1. Склонировать проект
2. Создать `.env.properties` и заполнить переменные
3. Поднять зависимости:
   ```bash
   docker compose up -d postgres chromadb
   ```
4. Запустить приложение из IDE или:
   ```bash
   ./gradlew bootRun
   ```

### Docker (prod)

1. Создать `.env` и заполнить переменные
2. Собрать JAR:
   ```bash
   ./gradlew clean bootJar
   ```
3. Запустить:
   ```bash
   docker compose --profile prod up -d --build
   ```
4. Проверить логи:
   ```bash
   docker compose --profile prod logs -f app
   ```
