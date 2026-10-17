# Optional shortcuts for the author. The README never depends on make.
.PHONY: demo test test-docker build clean

demo:
	docker compose run --rm --build demo

test:
	docker compose run --rm test

test-docker:
	docker compose run --rm test-docker

build:
	docker compose build demo

clean:
	docker compose down -v --remove-orphans
