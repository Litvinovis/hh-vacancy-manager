# Makefile для HH Vacancy Manager

.PHONY: build run test clean migrate docker-build docker-run

# ─── Backend ───

build:
	cd backend && mvn package -DskipTests

run:
	cd backend && mvn spring-boot:run

test:
	cd backend && mvn test

clean:
	cd backend && mvn clean
	rm -rf data/*.db

# ─── Миграция данных ───

migrate:
	python3 scripts/migrate_legacy.py \
		--legacy-db /home/clawd/hh-gui/vacancies.db \
		--new-db data/vacancies.db

# ─── Docker ───

docker-build:
	cd backend && docker build -t hh-gui:latest .

docker-run:
	docker run -p 8080:8080 \
		-v $(PWD)/data:/app/data \
		-v $(PWD)/config:/app/config \
		hh-gui:latest

# ─── Сбор вакансий ───

collect:
	cd collector && python3 rss_parser.py
