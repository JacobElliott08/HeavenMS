# HeavenMS Docker Deployment - PowerShell Commands
# ================================================
# This file documents PowerShell commands for deploying HeavenMS with Docker
# Use these if you prefer PowerShell or cannot install Make

# ============================================
# JAVA CODE DEPLOYMENT (Most Common Use Case)
# ============================================

# Rebuild and deploy Java code only (without touching database)
docker-compose build maplestory
docker-compose up -d --no-deps maplestory

# Alternative: Single command to rebuild and deploy Java only
docker-compose up -d --build --no-deps maplestory


# ============================================
# FULL DEPLOYMENT (Java + Database)
# ============================================

# First time setup - deploy everything
docker-compose up -d --build

# Or split into two steps:
docker-compose build
docker-compose up -d


# ============================================
# DATABASE DEPLOYMENT
# ============================================

# Start/deploy database only
docker-compose up -d db

# Rebuild database (WARNING: may reset data!)
docker-compose up -d --force-recreate db


# ============================================
# RESTART SERVICES
# ============================================

# Restart Java application only (no rebuild)
docker-compose restart maplestory

# Restart database only
docker-compose restart db

# Restart all services
docker-compose restart


# ============================================
# VIEW LOGS
# ============================================

# View Java application logs (follow mode)
docker-compose logs -f maplestory

# View database logs (follow mode)
docker-compose logs -f db

# View all logs
docker-compose logs -f

# View last 100 lines of Java logs
docker-compose logs --tail=100 maplestory

# View logs without follow mode (shows and exits)
docker-compose logs maplestory


# ============================================
# STOP SERVICES
# ============================================

# Stop all services (containers remain, can restart quickly)
docker-compose stop

# Stop specific service
docker-compose stop maplestory
docker-compose stop db

# Stop and remove all containers
docker-compose down

# Stop and remove containers + volumes (WARNING: deletes database data!)
docker-compose down -v


# ============================================
# STATUS AND DIAGNOSTICS
# ============================================

# Check service status
docker-compose ps

# Execute command in running container (e.g., bash shell)
docker-compose exec maplestory bash
docker-compose exec db bash

# Execute MySQL command in database
docker-compose exec db mysql -u root heavenms

# View resource usage
docker stats


# ============================================
# CLEANUP
# ============================================

# Remove stopped containers
docker-compose rm -f

# Full cleanup (containers, networks, volumes)
docker-compose down -v

# Remove all HeavenMS images
docker rmi heavenms-maplestory


# ============================================
# COMMON WORKFLOWS
# ============================================

# *** WORKFLOW 1: After modifying Java code ***
# Rebuild and deploy Java only (database keeps running)
docker-compose build maplestory
docker-compose up -d --no-deps maplestory
docker-compose logs -f maplestory

# *** WORKFLOW 2: After modifying SQL scripts ***
# WARNING: This recreates database - backup first!
docker-compose down db
docker-compose up -d db

# *** WORKFLOW 3: Fresh start (clean slate) ***
docker-compose down -v
docker-compose up -d --build

# *** WORKFLOW 4: Quick restart after config change ***
docker-compose restart maplestory
docker-compose logs -f maplestory


# ============================================
# TROUBLESHOOTING
# ============================================

# View container details
docker-compose ps -a

# Inspect container
docker inspect heavenms-maplestory-1

# Check network
docker network ls
docker network inspect heavenms_heavenms

# Rebuild everything from scratch
docker-compose down -v
docker-compose build --no-cache
docker-compose up -d

# View build output (verbose)
docker-compose build --progress=plain maplestory
