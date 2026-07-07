# HH Vacancy Manager

Многопользовательский трекер вакансий с HH.ru: сбор через RSS, полный текст — через headless-браузер, оценка релевантности — через LLM, отдельный личный кабинет и набор поисков на каждого пользователя.

## Как это работает

```
RSS (discovery, по каждому поиску) → новый hh_id?
  → scraper sidecar (Playwright, реальный браузер) → полная карточка вакансии
  → AI-анализ (LLM, критерии конкретного поиска) → Telegram-уведомление
```

- **RSS** — единственный эндпоинт hh.ru, отдающий данные без блокировки ботов, но не содержащий текста вакансии — только id и заголовок.
- **`scraper/`** — отдельный Node.js/Playwright-сервис с одним тёплым Chromium; ходит на страницу вакансии как настоящий браузер (`api.hh.ru` и HTML-страницы для обычных HTTP-клиентов закрыты DDoS-Guard) и парсит `schema.org/JobPosting` JSON-LD + `data-qa`-селекторы.
- **AI-анализ** — вызывает внешний LLM (OpenRouter/GitHub Models/др., настраивается в админке) с промптом, собранным из критериев конкретного поиска (город, районы, зарплата, желаемые/нежелательные навыки, свободный текст `ai_notes`) и профиля кандидата. Если два разных поиска (в том числе разных пользователей) дают одинаковые критерии для одной и той же вакансии — оценка переиспользуется без повторного вызова AI.

## Пользователи и доступ

Сервис рассчитан на несколько человек с независимыми наборами поисков:

- **Логин по сессии** (username/password, BCrypt), у каждого пользователя роль `user` или `admin`.
- **Личный кабинет** — до 3 поисков на пользователя, поля (запросы, регион, график, мин. зарплата, районы, навыки, слова-исключения, заметка для AI) редактируются в UI без перезапуска сервиса; там же — профиль (город, опыт/бэкграунд для AI) и смена пароля.
- **Админка** (роль `admin`) — создание пользователей, сброс пароля, деактивация/удаление; настройки AI-провайдеров и пайплайна.
- Обычный пользователь видит и запускает только свои поиски/вакансии; admin — всё.
- При самом первом запуске на пустой БД создаётся аккаунт `admin` со случайным паролем (в лог `journalctl -u hh-gui`), и если существует `config/profiles/default.yaml` — из него один раз импортируются пользователи и их поиски (дальше этот файл не читается).

## Стек

- **Backend:** Java 17+, Spring Boot 3.3.5, SQLite (JDBC, без ORM)
- **Scraper sidecar:** Node.js, Playwright (headless Chromium)
- **Frontend:** HTML/CSS/JS (vanilla, без сборки и фреймворков)
- **AI:** любой OpenAI-совместимый chat-completions API (настраивается в админке, с fallback-цепочкой провайдеров)

## Структура

```
hh-vacancy-manager/
├── backend/    # Spring Boot приложение (вся бизнес-логика, БД, API, статика фронтенда)
├── scraper/    # Node.js/Playwright sidecar — полный текст вакансии
├── config/
│   └── profiles/default.yaml.example  # шаблон для одноразового импорта при первом запуске
├── scripts/    # systemd unit-файлы (hh-gui.service, hh-scraper.service)
└── .github/    # CI/CD (сборка + деплой на self-hosted runner)
```

`collector/` в корне репозитория — старый Python-пайплайн (RSS/Avito/Telegram-парсеры, regex-скоринг), заменён полностью описанным выше пайплайном и больше не используется.

## Быстрый старт

### Требования

- Java 17+, Maven 3.8+
- Node.js 18+ (для `scraper/`)

### Запуск

```bash
# Backend
cd backend
mvn package -DskipTests
java -jar target/hh-gui-1.0.0-SNAPSHOT.jar
# или: mvn spring-boot:run

# Scraper sidecar (отдельный процесс, обязателен для полноценного сбора)
cd scraper
npm ci
npx playwright install --with-deps chromium
node server.js
```

Backend — `http://localhost:8080`, scraper sidecar — `http://localhost:8095` (порт настраивается через `app.scraper.url` / `SCRAPER_URL`).

При первом запуске в логе появится сгенерированный пароль admin — им нужно войти и сменить пароль через личный кабинет. Дальше поиски создаются через UI (кабинет — для себя, админка — для новых пользователей), править `config/profiles/*.yaml` руками не нужно.

### Тесты

```bash
cd backend && mvn test
```

## API

Всё под `/api/**`, кроме `/api/auth/**`, требует активной сессии (см. `AuthInterceptor`). Не-админ всегда видит только свои данные, даже если явно укажет чужой `person`/`searchName` в запросе.

**Авторизация** (`/api/auth`, без сессии)

| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/login` | Вход, устанавливает сессионную cookie |
| POST | `/logout` | Выход |
| GET | `/me` | Текущий пользователь |
| PUT | `/me` | Обновить профиль (город, опыт для AI) |
| POST | `/change-password` | Сменить свой пароль |

**Вакансии** (`/api/vacancies`)

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/vacancies` | Список (фильтры, пагинация, сортировка) |
| GET | `/vacancies/{id}` | Детали + история + теги |
| POST | `/vacancies` | Создать вручную |
| PUT | `/vacancies/{id}` | Обновить |
| PUT | `/vacancies/{id}/status` | Сменить статус (избранное/отклик/отклонено/обман) |
| POST | `/vacancies/{id}/reset-score` | Сбросить AI-оценку на «не оценено» |
| POST | `/vacancies/{id}/tags` | Добавить тег |
| POST | `/vacancies/bulk-status` | Массовая смена статуса |
| DELETE | `/vacancies/{id}` | Удалить |
| POST | `/import` | Импорт списка вакансий |
| GET | `/stats` | Сводная статистика (счётчики, средний скор/ЗП, топ районов/тегов) |

**Личный кабинет — поиски** (`/api/searches`)

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/searches` | Свои поиски |
| POST | `/searches` | Создать (максимум 3 на пользователя) |
| PUT | `/searches/{id}` | Обновить |
| DELETE | `/searches/{id}` | Удалить |

**Пайплайн** (`/api/pipeline`)

| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/pipeline/run` | Полный цикл: сбор → скрейп → AI → уведомление (`?person=&searchName=` — по одному поиску) |
| POST | `/pipeline/reanalyze` | Сброс + переоценка подходящих вакансий |
| POST | `/pipeline/analyze-pending` | Доанализировать необработанное без лимита |
| GET | `/pipeline/status` | Текущие счётчики (scoped по пользователю) |
| GET | `/pipeline/reanalyze/count` | Сколько вакансий доступно для переоценки |
| GET | `/pipeline/jobs` | Список доступных (person, searchName) пар |

**Админка** (`/api/admin/users`, только роль `admin`)

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/admin/users` | Список пользователей |
| POST | `/admin/users` | Создать (пароль — свой или сгенерированный) |
| PUT | `/admin/users/{id}` | Обновить (имя/город/роль/активность) |
| POST | `/admin/users/{id}/reset-password` | Сбросить пароль |
| DELETE | `/admin/users/{id}` | Удалить (вместе с его поисками) |

**Настройки** (`/api/settings`, только роль `admin`) — параметры пайплайна и цепочка AI-провайдеров; `/api/settings/notifications` — вкл/выкл Telegram-уведомлений.

**AI-статус** (`/api/ai/status`, `/api/ai/reset-provider`, `/api/ai/force-fallback`) — состояние rate-limit/cooldown, ручное переключение провайдера.

## Деплой

Self-hosted GitHub Actions runner собирает `backend/` (`mvn package`) и `scraper/` (`npm ci` + установка Chromium), раскладывает по `/opt/hh-gui`, накатывает миграции схемы SQLite (`ALTER TABLE ... || true`, идемпотентно) и перезапускает `hh-gui.service` и `hh-scraper.service` (юниты — в `scripts/`). Подробности — `.github/workflows/deploy.yml`.

## Лицензия

MIT
