package com.rclabs.mcpgit.server

import cats.effect.*
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import io.circe.*
import io.circe.syntax.*
import io.circe.generic.auto.*
import com.rclabs.mcpgit.server.MCPProtocol.given
import com.rclabs.mcpgit.core.{CommitProcessor, ProcessingResult, ProcessingError, ProcessingStage}
import com.rclabs.mcpgit.server.{GitShowDiffTool}

/**
 * Handles MCP protocol requests and coordinates with business logic services.
 * Processes initialize, tools/list, and tools/call requests according to MCP specification.
 *
 * @tparam F The effect type (IO, etc.)
 */
class RequestHandler[F[_]: Async: Logger] {
  private val gitService = com.rclabs.mcpgit.git.GitService.impl[F]

  // Create a simple commit processor that just validates repositories
  private val commitProcessor = new CommitProcessor[F] {
    def processRepository(
      repoPath: String,
      model: String,
      commitIndividually: Boolean
    ): F[ProcessingResult] = {
      for {
        isValid <- gitService.isValidRepository(repoPath)
        result <- if (isValid) {
          Logger[F].info(s"Repository $repoPath is valid") *>
          Async[F].pure(ProcessingResult(List.empty, List.empty, 0))
        } else {
          Logger[F].error(s"Invalid repository: $repoPath") *>
          Async[F].pure(ProcessingResult(
            List.empty,
            List(ProcessingError(repoPath, "Invalid repository", ProcessingStage.GitStatus)),
            0
          ))
        }
      } yield result
    }
  }

  def handleRequest(request: MCPRequest): F[MCPResponse] = {
    Logger[F].debug(s"Handling request: ${request.method}") *>
    (request.method match {
      case "initialize" =>
        Logger[F].info("Received initialize request") *>
        handleInitialize(request)
      case "tools/list" =>
        Logger[F].info("Received tools/list request") *>
        handleToolsList(request)
      case "tools/call" =>
        Logger[F].info(s"Received tools/call request with params: ${request.params}") *>
        handleToolCall(request)
      case _ =>
        Logger[F].warn(s"Unknown method: ${request.method}") *>
        Async[F].pure(MCPResponse(
          id = request.id,
          error = Some(MCPError(-32601, s"Method not found: ${request.method}"))
        ))
    })
  }

  private def handleInitialize(request: MCPRequest): F[MCPResponse] = {
    Logger[F].debug("Creating initialize response") *>
    {
      val result = Json.obj(
        "protocolVersion" -> Json.fromString("2024-11-05"),
        "capabilities" -> Json.obj(
          "tools" -> Json.obj()
        ),
        "serverInfo" -> Json.obj(
          "name" -> Json.fromString("mcp-git-ollama-server"),
          "version" -> Json.fromString("0.1.0")
        )
      )

      Logger[F].debug(s"Initialize response created: $result") *>
      Async[F].pure(MCPResponse(
        id = request.id,
        result = Some(result)
      ))
    }
  }

  private def handleToolsList(request: MCPRequest): F[MCPResponse] = {
    Logger[F].debug("Creating tools list response") *>
    {
      val tools: List[MCPTool] = List(GitCommitTool(), GitShowDiffTool())
      val result = Json.obj(
        "tools" -> tools.asJson
      )

      Logger[F].debug(s"Tools list response created with ${tools.length} tools") *>
      Logger[F].debug(s"Tools: ${tools.map(_.name).mkString(", ")}") *>
      Async[F].pure(MCPResponse(
        id = request.id,
        result = Some(result)
      ))
    }
  }

  private def handleToolCall(request: MCPRequest): F[MCPResponse] = {
    for {
      _ <- Logger[F].debug(s"Handling tool call with params: ${request.params}")
      result <- request.params.flatMap(_.get("name")) match {
        case Some(json) if json.asString.contains("git_auto_commit") =>
          for {
            _ <- Logger[F].info("Calling git_auto_commit tool")
            result <- handleGitAutoCommit(request)
          } yield result
        case Some(json) if json.asString.contains("git_show_diff") =>
          for {
            _ <- Logger[F].info("Calling git_show_diff tool")
            result <- handleGitShowDiff(request)
          } yield result
        case Some(json) =>
          val toolName = json.asString.getOrElse("unknown")
          for {
            _ <- Logger[F].warn(s"Unknown tool requested: $toolName")
            result <- Async[F].pure(MCPResponse(
              id = request.id,
              error = Some(MCPError(-32602, s"Unknown tool: $toolName"))
            ))
          } yield result
        case _ =>
          for {
            _ <- Logger[F].error("Invalid tool call parameters - missing 'name' field")
            result <- Async[F].pure(MCPResponse(
              id = request.id,
              error = Some(MCPError(-32602, "Invalid tool call parameters"))
            ))
          } yield result
      }
    } yield result
  }

  private def handleGitAutoCommit(request: MCPRequest): F[MCPResponse] = {
    val params = request.params.flatMap(_.get("arguments"))

    for {
      _ <- Logger[F].debug(s"Git auto commit arguments: $params")
      result <- (for {
        repoPath <- extractStringParam(params, "repository_path")
        model = extractStringParam(params, "model").getOrElse("llama2")
        commitIndividually = extractBooleanParam(params, "commit_individually").getOrElse(true)
      } yield (repoPath, model, commitIndividually)) match {
        case Some((repoPath, model, commitIndividually)) =>
          for {
            _ <- Logger[F].info(s"Processing repository: $repoPath with model: $model, individual commits: $commitIndividually")
            result <- commitProcessor.processRepository(repoPath, model, commitIndividually)
              .flatMap { result =>
                for {
                  _ <- Logger[F].info(s"Repository processing completed: ${result.totalCommits} commits, ${result.errors.length} errors")
                  _ <- Logger[F].debug(s"Processing result: $result")
                  resultJson = Json.obj(
                    "content" -> Json.arr(
                      Json.obj(
                        "type" -> Json.fromString("text"),
                        "text" -> Json.fromString(formatProcessingResult(result))
                      )
                    )
                  )
                  response <- Async[F].pure(MCPResponse(id = request.id, result = Some(resultJson)))
                } yield response
              }
              .handleErrorWith { error =>
                for {
                  _ <- Logger[F].error(s"Repository processing failed: ${error.getMessage}")
                  _ <- Logger[F].debug(error)("Error details")
                  response <- Async[F].pure(MCPResponse(
                    id = request.id,
                    error = Some(MCPError(-32603, s"Processing failed: ${error.getMessage}"))
                  ))
                } yield response
              }
          } yield result
        case None =>
          for {
            _ <- Logger[F].error("Missing required parameter: repository_path")
            response <- Async[F].pure(MCPResponse(
              id = request.id,
              error = Some(MCPError(-32602, "Missing required parameter: repository_path"))
            ))
          } yield response
      }
    } yield result
  }

  private def handleGitShowDiff(request: MCPRequest): F[MCPResponse] = {
    val params = request.params.flatMap(_.get("arguments"))

    for {
      _ <- Logger[F].debug(s"Git show diff arguments: $params")
      result <- extractStringParam(params, "repository_path") match {
        case Some(repoPath) =>
          for {
            _ <- Logger[F].info(s"Showing diff for repository: $repoPath")
            isValid <- gitService.isValidRepository(repoPath)
            response <- if (isValid) {
              val filePath = extractStringParam(params, "file_path")
              val staged = extractBooleanParam(params, "staged").getOrElse(false)

              for {
                diffOutput <- (filePath, staged) match {
                  case (Some(file), true) => gitService.getFileDiff(repoPath, file, staged = true)
                  case (Some(file), false) => gitService.getFileDiff(repoPath, file, staged = false)
                  case (None, true) => gitService.getStagedDiff(repoPath)
                  case (None, false) => gitService.getAllDiff(repoPath)
                }
                _ <- Logger[F].debug(s"Got diff output of length: ${diffOutput.length}")
                resultText = if (diffOutput.trim.isEmpty) {
                  "No changes found."
                } else {
                  s"Git diff output:\n\n$diffOutput"
                }
                resultJson = Json.obj(
                  "content" -> Json.arr(
                    Json.obj(
                      "type" -> Json.fromString("text"),
                      "text" -> Json.fromString(resultText)
                    )
                  )
                )
                response <- Async[F].pure(MCPResponse(id = request.id, result = Some(resultJson)))
              } yield response
            } else {
              for {
                _ <- Logger[F].error(s"Invalid git repository: $repoPath")
                response <- Async[F].pure(MCPResponse(
                  id = request.id,
                  error = Some(MCPError(-32603, s"Invalid git repository: $repoPath"))
                ))
              } yield response
            }
          } yield response
        case None =>
          for {
            _ <- Logger[F].error("Missing required parameter: repository_path")
            response <- Async[F].pure(MCPResponse(
              id = request.id,
              error = Some(MCPError(-32602, "Missing required parameter: repository_path"))
            ))
          } yield response
      }
    } yield result
  }

  private def extractStringParam(params: Option[Json], key: String): Option[String] =
    params.flatMap(_.hcursor.downField(key).as[String].toOption)

  private def extractBooleanParam(params: Option[Json], key: String): Option[Boolean] =
    params.flatMap(_.hcursor.downField(key).as[Boolean].toOption)

  private def formatProcessingResult(result: com.rclabs.mcpgit.core.ProcessingResult): String = {
    val sb = new StringBuilder()
    sb.append(s"Processing completed: ${result.totalCommits} commits made\n\n")

    if (result.processedFiles.nonEmpty) {
      sb.append("Successfully processed files:\n")
      result.processedFiles.foreach { file =>
        sb.append(s"- ${file.filePath}: ${file.commitMessage}\n")
        file.commitHash.foreach(hash => sb.append(s"  Commit: ${hash.take(8)}\n"))
        sb.append(s"  Processing time: ${file.processingTimeMs}ms\n")
      }
      sb.append("\n")
    }

    if (result.errors.nonEmpty) {
      sb.append("Errors encountered:\n")
      result.errors.foreach { error =>
        sb.append(s"- ${error.filePath}: ${error.error} (stage: ${error.stage})\n")
      }
    }

    sb.toString()
  }
}
