# MCP Git-Ollama Server

An MCP (Model Context Protocol) server implementation in Scala that automatically generates commit messages for Git repositories using local Ollama models.

## Overview

This server monitors Git repositories, analyzes file changes using `git status` and `git diff`, generates contextual commit messages via Ollama, and commits each file individually with AI-generated messages.

## Prerequisites

1. **Install Ollama locally**
   ```bash
   # Install Ollama (visit https://ollama.ai for installation instructions)
   
   # Pull desired model
   ollama pull llama2
   
   # Start Ollama service
   ollama serve
   ```

2. **Install sbt (Scala Build Tool)**
   ```bash
   # macOS with Homebrew
   brew install sbt
   
   # Or visit https://www.scala-sbt.org/download.html
   ```

## Building and Running

1. **Clone and build the project**
   ```bash
   sbt compile
   ```

2. **Run the server**
   ```bash
   sbt run
   ```

The server will start and listen for MCP protocol messages on stdin/stdout.

## Configuration

Edit `src/main/resources/application.conf` to customize:

- **Ollama settings**: Base URL, default model, timeout
- **Git settings**: Max diff lines, commit prefix, exclude patterns  
- **Processing**: Max concurrent files, commit message length

## MCP Client Integration

The server exposes the `git_auto_commit` tool that can be called by any MCP-compatible client:

```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "method": "tools/call",
  "params": {
    "name": "git_auto_commit",
    "arguments": {
      "repository_path": "/path/to/repo",
      "model": "llama2",
      "commit_individually": true
    }
  }
}
```

## Features

- **Individual file commits**: Each changed file gets its own commit with a tailored message
- **Batch processing**: Option to commit all changes together
- **Concurrent processing**: Configurable parallelism for multiple files
- **Error handling**: Comprehensive error reporting and recovery
- **Flexible prompts**: Different prompt strategies for different change types (add, modify, delete, etc.)

## Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   MCP Client    │◄──►│  MCP Server     │◄──►│     Ollama      │
│   (IDE/CLI)     │    │   (Scala)       │    │   (Local LLM)   │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                              │
                              ▼
                       ┌─────────────────┐
                       │   Git Repository │
                       │   File System    │
                       └─────────────────┘
```

## Testing

Run the test suite:
```bash
sbt test
```

## Development

The project follows a modular architecture:

- `server/` - MCP protocol handling
- `git/` - Git operations and change analysis  
- `ollama/` - Ollama client and prompt generation
- `core/` - Main processing logic
- `config/` - Configuration management

## License

MIT License# Test change
## Testing improved commit messages
