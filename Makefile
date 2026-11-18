.PHONY: up down logs ps app clean run smoke build format up-all down-all

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

# Full stack: postgres, rabbitmq, app and the webhook sink, with fast retries for development.
up-all:
	HOOKRELAY_RETRY_INITIAL_DELAY=1s HOOKRELAY_RETRY_MAX_DELAY=5s HOOKRELAY_RETRY_JITTER=0 \
	docker compose --profile app --profile sink up -d --build --wait

down-all:
	docker compose --profile app --profile sink down -v

# Stops everything, including the app and sink profiles, and drops the named volumes.
clean:
	docker compose --profile app --profile sink down -v --remove-orphans
