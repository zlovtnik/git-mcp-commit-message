#!/bin/bash

# MCP Git-Ollama Server Startup Script
# This script runs the pre-compiled JAR directly without SBT

# Get the script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Change to the project directory
cd "$SCRIPT_DIR"

# Set environment variables
export JAVA_OPTS="-Dcats.effect.warnOnNonMainThreadDetected=false -Xmx2G -Xms1G"

# Find the assembly JAR
JAR_FILE="target/scala-3.3.6/mcp-git-ollama-server-assembly-0.1.0.jar"

# Check if JAR exists
if [ ! -f "$JAR_FILE" ]; then
    echo "Error: Assembly JAR not found at $JAR_FILE" >&2
    echo "Please run 'sbt assembly' first" >&2
    exit 1
fi

# Log startup information to stderr so it doesn't interfere with MCP protocol
echo "Starting MCP Git-Ollama Server (JAR mode)..." >&2
echo "Project directory: $(pwd)" >&2
echo "JAR file: $JAR_FILE" >&2

# Run the JAR directly - no SBT output to interfere with MCP protocol
exec java $JAVA_OPTS -jar "$JAR_FILE"
