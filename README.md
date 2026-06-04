# HH Vacancy Manager

Веб-приложение для управления вакансиями с HH.ru. Парсинг RSS → AI-скоринг → веб-интерфейс для фильтрации и отслеживания.

## Стек

- **Backend:** Java 17+, Spring Boot 3.x, SQLite
- **Frontend:** HTML/CSS/JS (vanilla, без фреймворков)
- **Скоринг:** YAML-правила (regex-based)
- **Сбор данных:** Python RSS-парсер

## Структура

```
hh-vacancy-manager/
├── backend/          # Spring Boot приложение
├── collector/        # Python RSS-парсер
├── config/           # Конфигурация (YAML)
│   ├── rules.yaml           # Правила скоринга
│   └── profiles/            # Профили поиска
├── scripts/          # Утилиты (миграция и т.д.)
└── .github/          # CI/CD
```

## Быстрый старт

### Требования

- Java 17+
- Maven 3.8+
- Python 3.11+ (для парсера)

### Запуск

```bash
# Сборка
cd backend
mvn package -DskipTests

# Запуск
java -jar target/hh-gui-1.0.0-SNAPSHOT.jar

# Или через Maven
mvn spring-boot:run
```

Приложение доступно на `http://localhost:8080`

### Конфигурация

1. Скопируйте `config/profiles/default.yaml.example` → `config/profiles/default.yaml`
2. Отредактируйте под свои нужды
3. Правила скоринга: `config/rules.yaml`

### Сбор вакансий

```bash
cd collector
pip install pyyaml
python rss_parser.py
```

## API

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/api/vacancies` | Список вакансий (фильтры, пагинация) |
| GET | `/api/vacancies/{id}` | Детали вакансии |
| POST | `/api/vacancies` | Создать вакансию |
| PUT | `/api/vacancies/{id}` | Обновить вакансию |
| DELETE | `/api/vacancies/{id}` | Удалить |
| POST | `/api/vacancies/bulk-status` | Массовое обновление статуса |
| POST | `/api/import` | Импорт списка вакансий |
| GET | `/api/stats` | Статистика |

## Лицензия

MIT
