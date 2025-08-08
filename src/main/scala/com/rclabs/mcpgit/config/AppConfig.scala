package com.rclabs.mcpgit.config

import cats.effect.Sync
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Server configuration settings.
 *
 * @param host The host address to bind the server to
 * @param port The port number to listen on
 */
case class ServerConfig(
  host: String,
  port: Int
)

/**
 * Ollama service configuration settings.
 *
 * @param baseUrl The base URL of the Ollama API service
 * @param defaultModel The default model to use for commit message generation
 * @param timeout The request timeout for Ollama API calls
 * @param maxRetries Maximum number of retry attempts for failed requests
 */
case class OllamaConfig(
  baseUrl: String,
  defaultModel: String,
  timeout: FiniteDuration,
  maxRetries: Int
)

/**
 * Git-specific configuration settings.
 *
 * @param maxDiffLines Maximum number of lines to include in diff analysis
 * @param commitPrefix Optional prefix to add to all commit messages
 * @param excludePatterns List of file patterns to exclude from processing
 */
case class GitConfig(
  maxDiffLines: Int,
  commitPrefix: String,
  excludePatterns: List[String]
)

/**
 * Processing configuration settings.
 *
 * @param maxConcurrentFiles Maximum number of files to process concurrently
 * @param commitMessageMaxLength Maximum length for generated commit messages
 */
case class ProcessingConfig(
  maxConcurrentFiles: Int,
  commitMessageMaxLength: Int
)

/**
 * Main application configuration containing all subsystem configurations.
 *
 * @param server Server-related configuration
 * @param ollama Ollama AI service configuration
 * @param git Git operations configuration
 * @param processing File processing configuration
 */
case class AppConfig(
  server: ServerConfig,
  ollama: OllamaConfig,
  git: GitConfig,
  processing: ProcessingConfig
)

object AppConfig {
  def load[F[_]: Sync](): F[AppConfig] = Sync[F].delay {
    val config = ConfigFactory.load()
    val mcpConfig = config.getConfig("mcp-git-ollama")
    
    AppConfig(
      server = ServerConfig(
        host = mcpConfig.getString("server.host"),
        port = mcpConfig.getInt("server.port")
      ),
      ollama = OllamaConfig(
        baseUrl = mcpConfig.getString("ollama.base-url"),
        defaultModel = mcpConfig.getString("ollama.default-model"),
        timeout = mcpConfig.getDuration("ollama.timeout").toMillis.millis,
        maxRetries = mcpConfig.getInt("ollama.max-retries")
      ),
      git = GitConfig(
        maxDiffLines = mcpConfig.getInt("git.max-diff-lines"),
        commitPrefix = mcpConfig.getString("git.commit-prefix"),
        excludePatterns = mcpConfig.getStringList("git.exclude-patterns").asScala.toList
      ),
      processing = ProcessingConfig(
        maxConcurrentFiles = mcpConfig.getInt("processing.max-concurrent-files"),
        commitMessageMaxLength = mcpConfig.getInt("processing.commit-message-max-length")
      )
    )
  }
}
