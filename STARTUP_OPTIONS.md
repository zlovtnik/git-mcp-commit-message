# MCP Server Startup Options

This document explains the different ways to start the MCP Git-Ollama Server.

## Option 1: Script-based Startup (Recommended)

Use the provided startup script that runs the pre-compiled JAR:

### MCP Configuration
```json
{
  "mcpServers": {
    "git-ollama": {
      "command": "/Users/rcs/git-mcp-commit-message/start-mcp-server.sh",
      "args": [],
      "cwd": "/Users/rcs/git-mcp-commit-message",
      "env": {},
      "disabled": false,
      "autoApprove": ["git_auto_commit"]
    }
  }
}
```

### Prerequisites
```bash
# Build the assembly JAR once
sbt assembly
```

### Benefits
- ✅ No SBT logging interference
- ✅ Fast startup (no compilation)
- ✅ Clean MCP protocol communication
- ✅ Easy debugging (logs to stderr)

## Option 2: Direct JAR Execution

Run the JAR directly through Java:

### MCP Configuration
```json
{
  "mcpServers": {
    "git-ollama": {
      "command": "java",
      "args": [
        "-Dcats.effect.warnOnNonMainThreadDetected=false",
        "-Xmx2G",
        "-Xms1G",
        "-jar",
        "/Users/rcs/git-mcp-commit-message/target/scala-3.3.6/mcp-git-ollama-server-assembly-0.1.0.jar"
      ],
      "cwd": "/Users/rcs/git-mcp-commit-message",
      "env": {},
      "disabled": false,
      "autoApprove": ["git_auto_commit"]
    }
  }
}
```

### Benefits
- ✅ Maximum control over JVM settings
- ✅ No shell script dependency
- ✅ Clean protocol communication

## Option 3: SBT Development Mode

For development only (may have logging interference):

### MCP Configuration
```json
{
  "mcpServers": {
    "git-ollama": {
      "command": "/opt/homebrew/bin/sbt",
      "args": ["--batch", "--error", "run"],
      "cwd": "/Users/rcs/git-mcp-commit-message",
      "env": {
        "JAVA_OPTS": "-Dcats.effect.warnOnNonMainThreadDetected=false"
      },
      "disabled": false,
      "autoApprove": ["git_auto_commit"]
    }
  }
}
```

### When to Use
- 🔧 Active development
- 🔧 Code changes frequently
- ⚠️ May have logging interference

## Troubleshooting

### 1. Rebuild JAR after code changes
```bash
sbt assembly
```

### 2. Test server manually
```bash
./start-mcp-server.sh
# Then send test JSON:
{"jsonrpc": "2.0", "id": "1", "method": "initialize", "params": {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {"name": "test", "version": "1.0"}}}
```

### 3. Check Ollama is running
```bash
curl http://localhost:11434/api/tags
```

### 4. Verify logs
Check stderr output for startup messages:
```
Starting MCP Git-Ollama Server (JAR mode)...
Project directory: /Users/rcs/git-mcp-commit-message
JAR file: target/scala-3.3.6/mcp-git-ollama-server-assembly-0.1.0.jar
```

## Recommended Setup

1. **Build once**: `sbt assembly`
2. **Use script config**: `mcp-config-script.json`
3. **Test manually**: `./start-mcp-server.sh`
4. **Restart MCP client** to pick up new configuration
