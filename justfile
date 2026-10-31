# Labs64.IO :: Reporting

# List available recipes
default:
    @just --list

# Build the jar, tests included
build:
    mvn -B package

# Run the tests (ReportingApiIT needs Docker and skips itself without it)
test:
    mvn -B test

# Run the service from the published image with a demo tenant (docker-compose.images.yml)
demo:
    docker compose -f docker-compose.images.yml up -d --wait
