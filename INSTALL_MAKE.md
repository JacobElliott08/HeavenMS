# Installing Make on Windows

## Option 1: Using Chocolatey (Recommended)

1. Install Chocolatey if you haven't already (run as Administrator):
```powershell
Set-ExecutionPolicy Bypass -Scope Process -Force; [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072; iex ((New-Object System.Net.WebClient).DownloadString('https://community.chocolatey.org/install.ps1'))
```

2. Install Make:
```powershell
choco install make
```

## Option 2: Using Scoop

1. Install Scoop if you haven't already:
```powershell
Set-ExecutionPolicy RemoteSigned -Scope CurrentUser
irm get.scoop.sh | iex
```

2. Install Make:
```powershell
scoop install make
```

## Option 3: Git Bash (if you have Git installed)

If you have Git for Windows installed, Make is already available in Git Bash:
- Open Git Bash (not PowerShell)
- Navigate to your project: `cd /c/Users/Jake/IdeaProjects/HeavenMS`
- Run make commands: `make`, `make help`, etc.

## Option 4: WSL (Windows Subsystem for Linux)

1. Enable WSL (run as Administrator):
```powershell
wsl --install
```

2. Open Ubuntu/WSL terminal and install make:
```bash
sudo apt update
sudo apt install make
```

3. Navigate to project:
```bash
cd /mnt/c/Users/Jake/IdeaProjects/HeavenMS
```

## Verify Installation

After installing, verify Make is available:
```bash
make --version
```

## Using the Makefile

Once Make is installed, you can use these commands:

### Quick Reference

```bash
# Show all available commands
make help

# Deploy Java code only (most common after code changes)
make

# View logs
make logs

# Stop services
make down

# Deploy database
make sql

# Clean up everything
make clean
```

### Typical Development Workflow

```bash
# 1. Initial deployment (first time)
make up

# 2. After modifying Java code
make

# 3. Check logs
make logs

# 4. Restart if needed
make restart

# 5. Stop when done
make down
```

## If Make Installation Fails

Use the PowerShell commands directly instead. See [DOCKER_COMMANDS.md](DOCKER_COMMANDS.md) for detailed PowerShell command documentation.

Quick example:
```powershell
# Rebuild and deploy Java only
docker-compose up -d --build --no-deps maplestory

# View logs
docker-compose logs -f maplestory
```
