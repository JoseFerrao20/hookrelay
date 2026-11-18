.PHONY: up down logs ps app clean

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
