.PHONY: up down logs ps app clean run smoke build format

run:
	./gradlew bootRun

# Needs the app running (make up && make run, or make app).
smoke:
	./scripts/smoke-test.sh

build:
	./gradlew build

format:
	./gradlew spotlessApply

up:
	docker compose up -d --wait

down:
	docker compose down

logs:
	docker compose logs -f

ps:
	docker compose ps

app:
	docker compose --profile app up -d --build

# Stops everything, including the app profile, and drops the named volumes.
clean:
	docker compose --profile app down -v --remove-orphans
