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

### Фаза 6: готовность к продакшену (надёжность / мониторинг / тестирование)

- **Кастомные доменные исключения** (единый формат ошибок и “машинные” коды):
    - `CodeReviewBotException` (base)
    - `AiReviewException` (ошибки OpenAI / временные сбои)
    - `AiNonRetryableException` (ошибки запроса, которые не надо ретраить и не должны “ломать” circuit breaker)
    - `GitlabClientException` (ошибки GitLab API)
    - `RagContextException` (ошибки получения RAG контекста)
    - `WebhookValidationException` (ошибки валидации webhook)
    - `ReviewProcessingException` (ошибки процесса/очереди/оркестрации)
- **Resilience4j**:
    - `@Retry` для OpenAI (3 попытки)
    - `@Retry` для GitLab (2 попытки)
    - `@CircuitBreaker` для OpenAI (на уровне `CodeReviewService`)
    - “ignore-exceptions” для circuit breaker (например, `AiNonRetryableException`)
- **Единые метрики** через Micrometer + Actuator:
    - `code_review_total{result=success|failed|skipped}`
    - `code_review_duration_seconds{result=...}` (Timer → в Prometheus добавляется `_seconds`)
    - `rag_context_total{result=success|failed|skipped|empty}`
    - `rag_context_duration_seconds{result=...}`
    - плюс стандартные `resilience4j_*` метрики (retry/circuitbreaker)
- **Health indicator**:
    - `CodeReviewHealthIndicator` помечает сервис как `DOWN`, если `openai` circuit breaker в состоянии `OPEN`
- **Prometheus endpoint включён**: `/actuator/prometheus`
- **Глобальный обработчик ошибок API**: `CodeReviewExceptionHandler` возвращает единый JSON (timestamp/path/error/message)

---

## Как работает (High-level)

1. **GitLab** отправляет webhook на:
    - `POST /api/webhook/gitlab/merge-request`
2. Контроллер валидирует `X-Gitlab-Token`, проверяет событие и `action` (open/reopen/update), извлекает `projectId` и `mrIid`, отправляет ревью в очередь.
3. `ReviewOrchestrator`:
    - получает MR и diffs через GitLab API (**GitLab обёрнут в `@Retry(name="gitlab")`**),
    - пропускает Draft/WIP и не-opened MR,
    - фиксирует статус в БД (RUNNING) и блокирует дубль по `head_sha`,
    - запускает `CodeReviewService`,
    - публикует результат через `GitLabCommentService`,
    - обновляет статус (SUCCESS/FAILED).
4. `CodeReviewService` (**обёрнут в `@CircuitBreaker(name="openai")`**):
    - собирает `{rag_context}` (если включено). Ошибки RAG **не валят** ревью, а метятся метриками как `failed/empty/skipped`.
    - формирует промпт из шаблонов (`system-prompt.txt`, `user-prompt.txt`),
    - вызывает OpenAI через `AiChatGateway` (обычно `OpenAiChatGateway`) — там стоит **`@Retry(name="openai")`**,
    - парсит JSON в `CodeReviewResult`.
5. `GitLabCommentService` публикует общий комментарий и (опционально) inline обсуждения.

---

## Основные компоненты

### Webhook / Оркестрация
- **`MergeRequestWebhookController`** — прием webhook, валидация secret, постановка задач.
- **`ReviewOrchestrator`** — оркестрация всего пайплайна MR → diffs → AI → GitLab.

### GitLab API
- **`GitLabMergeRequestClient`** — получение MR/changes/diff refs, публикация comments/discussions.  
  Ключевые методы обёрнуты в **`@Retry(name="gitlab")`**, ошибки поднимаются как `GitlabClientException`.

### AI Review
- **`AiChatConfig`** — создание `ChatClient` (Spring AI).
- **`AiChatGateway` / `OpenAiChatGateway`** — шлюз к OpenAI (Retry + классификация ошибок).
- **`CodeReviewService`** — сборка промпта, вызов модели, парсинг результата. Обёрнут в **CircuitBreaker**.
- **`PromptTemplateService`** — подстановка `{title}`, `{description}`, `{rag_context}`, `{code_changes}` в шаблоны.

### RAG (ChromaDB)
- **`DocumentIndexingService`** — индексирует документы из `resources/rag-documents/` при старте.
- **`RagContextService`** — embedding + query в ChromaDB + сборка контекста с лимитами.
- **`MergeRequestRagContextProvider`** — собирает итоговый RAG контекст (Full/Economy стратегия).

### Публикация и форматирование
- **`CommentFormatterService`** — Markdown формат ответа.
- **`InlineCommentPlannerService`** — планирование inline-comment’ов по лимитам.
- **`GitLabCommentService`** — публикация общего комментария + inline.

### Асинхронность / надёжность / мониторинг
- **`AsyncConfig`** — пул `reviewExecutor`.
- **`ReviewMetricsService`** — счётчики и таймеры ревью + RAG.
- **`CodeReviewHealthIndicator`** — health по состоянию openai circuit breaker.
- **`CodeReviewExceptionHandler`** — единый JSON-ответ для доменных ошибок контроллеров.
- **`ReviewStatus` (+ repository)** — хранение статуса в Postgres.

---

## Resilience4j: Retry / CircuitBreaker (конфигурация)

В `application.yml` задаются параметры:

```yaml
resilience4j:
  retry:
    instances:
      openai:
        max-attempts: 3
        wait-duration: 500ms
      gitlab:
        max-attempts: 2
        wait-duration: 300ms
  circuitbreaker:
    instances:
      openai:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        permitted-number-of-calls-in-half-open-state: 5
        wait-duration-in-open-state: 30s
        ignore-exceptions:
          - com.groviate.telegramcodereviewbot.exception.AiNonRetryableException
```

> Важно: если используется Spring AI retry автоконфигурация — обычно её лучше “заглушить” (например, `spring.ai.retry.max-attempts=1`), чтобы **не получить двойной retry** (Spring Retry + Resilience4j одновременно).

---

## Метрики / мониторинг (Prometheus)

Actuator endpoints включены (см. `management.endpoints.web.exposure.include`).

### Полезные endpoints

> ⚠️ Если у тебя задан `SERVER_SERVLET_CONTEXT_PATH=/telegram-bot`, то префикс добавится ко всем URL:  
> например `/telegram-bot/actuator/prometheus`

- `GET /actuator/health` — здоровье приложения (включая `codeReview`)
- `GET /actuator/prometheus` — метрики Prometheus
- `GET /actuator/metrics` — список метрик
- `GET /actuator/circuitbreakers` и `/actuator/circuitbreakerevents`
- `GET /actuator/retries` и `/actuator/retryevents`

### Кастомные метрики (твои)

**Code review:**
- `code_review_total{result="success|failed|skipped"}`
- `code_review_duration_seconds{result="success|failed|skipped"}`

**RAG:**
- `rag_context_total{result="success|failed|skipped|empty"}`
- `rag_context_duration_seconds{result="success|failed|skipped|empty"}`

### Метрики Resilience4j (автоматические)
- `resilience4j_retry_calls_total{kind=... , name=openai|gitlab}`
- `resilience4j_circuitbreaker_state{name="openai", state="closed|open|half_open"...}`
- и другие `resilience4j_*`

---

## Health Indicator

`CodeReviewHealthIndicator` проверяет состояние circuit breaker `openai`:
- если `OPEN` → `Health.down()`
- иначе → `Health.up()`

Смотри в `GET /actuator/health` (детали включены: `show-details: always`).

---

## Тестирование

### Unit tests
Запуск:
```bash
./gradlew test
```

Ожидаемые основные unit-наборы:
- `CodeReviewService` (парсинг, fallback, поведение при пустом ответе, обработка RAG empty/failed)
- `CommentFormatterService` (форматирование markdown, лимиты, устойчивость к null)

### Integration tests
Запуск (если у тебя выделен отдельный sourceSet):
```bash
./gradlew integrationTest
```

Типичный интеграционный сценарий:
- webhook → controller → orchestrator → client mocks/stubs → проверка статуса/ответа/метрик

---

## RAG: режимы Full vs Economy (переключаются флагом)

### Зачем два режима
- **FULL**: RAG строится *для каждого файла* — “полнее”, но дороже и часто раздувает `{rag_context}`.
- **ECONOMY**: дешевле:
    - 1 общий RAG по всему MR (сводка diffs),
    - + отдельный RAG только для **top-N** самых больших файлов,
    - жёсткие лимиты на размер и дедупликация повторов.

---

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

**Вариант C — самый простой и постоянный через application.properties**

1. В application.properties добавить строчку: spring.profiles.default=local
2. Запустить `TelegramCodeReviewBotApplication` (Run)

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

### 5.4. Проверить метрики / Prometheus

```bash
curl -s http://localhost:8080/actuator/prometheus | head -n 50
```

Ищи:
- `code_review_total`
- `rag_context_total`
- `resilience4j_retry_calls_total`
- `resilience4j_circuitbreaker_state`

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
