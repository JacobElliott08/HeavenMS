# HeavenMS Makefile
# Targets for deploying Java code and SQL separately

.PHONY: all build up down restart logs clean sql sql-rebuild help

# Default: rebuild and deploy Java code only (without touching database)
all: build up-java

# Build the Java application Docker image
build:
	@echo "Building HeavenMS Java application..."
	docker-compose build maplestory

# Deploy Java code only (rebuilds and restarts maplestory service)
up-java:
	@echo "Deploying Java application (database will not be restarted)..."
	docker-compose up -d --no-deps maplestory

# Full deployment (both Java and SQL)
up:
	@echo "Deploying all services (Java + Database)..."
	docker-compose up -d

# Deploy/restart database only
sql:
	@echo "Starting database service..."
	docker-compose up -d db

# Rebuild and redeploy database (WARNING: may recreate database)
sql-rebuild:
	@echo "WARNING: Rebuilding database - this may reset your data!"
	@echo "Press Ctrl+C within 5 seconds to cancel..."
	@sleep 5
	docker-compose up -d --force-recreate db

# Stop all services
down:
	@echo "Stopping all services..."
	docker-compose down

# Restart Java application only
restart:
	@echo "Restarting Java application..."
	docker-compose restart maplestory

# View logs (Java application)
logs:
	docker-compose logs -f maplestory

# View database logs
logs-db:
	docker-compose logs -f db

# View all logs
logs-all:
	docker-compose logs -f

# Clean up containers and images
clean:
	@echo "Cleaning up containers, networks, and volumes..."
	docker-compose down -v
	docker-compose rm -f

# Help target - show available commands
help:
	@echo "HeavenMS Docker Makefile Commands:"
	@echo ""
	@echo "  make / make all     - Rebuild and deploy Java code only (RECOMMENDED)"
	@echo "  make build          - Build the Java application Docker image"
	@echo "  make up-java        - Deploy Java code without touching database"
	@echo "  make up             - Deploy all services (Java + Database)"
	@echo "  make down           - Stop all services"
	@echo "  make restart        - Restart Java application only"
	@echo "  make logs           - View Java application logs (follow mode)"
	@echo "  make logs-db        - View database logs"
	@echo "  make logs-all       - View all logs"
	@echo "  make sql            - Start/deploy database only"
	@echo "  make sql-rebuild    - Rebuild database (WARNING: may reset data)"
	@echo "  make clean          - Remove all containers, networks, and volumes"
	@echo "  make help           - Show this help message"
	@echo ""
	@echo "Quick Start:"
	@echo "  1. First deployment:     make up"
	@echo "  2. After code changes:   make"
	@echo "  3. View logs:            make logs"
	@echo "  4. Stop everything:      make down"
