package com.rclabs.mcpgit

import cats.effect.*
import cats.data.EitherT
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import com.rclabs.mcpgit.config.AppConfig
import com.rclabs.mcpgit.git.GitService
import com.rclabs.mcpgit.ollama.OllamaClient
import com.rclabs.mcpgit.server.{MCPRequest, MCPResponse, MCPError, GitCommitTool, MCPProtocol}

/**
 * Functional programming approach to the MCP Git-Ollama Server.
 * Uses tagless final pattern, algebraic data types, and pure functional error handling.
 * 
 * Key principles:
 * - Referential transparency: no side effects in pure functions
 * - Composability: small, focused functions that compose well
 * - Type safety: leverages Scala's type system for compile-time guarantees
 * - Error handling: using Either types for explicit error modeling
 * - Resource safety: automatic cleanup using cats-effect Resource
 */
object FunctionalMain extends IOApp {

  /**
   * Sealed trait representing all possible application errors.
   * Uses algebraic data types for comprehensive error handling.
   */
  sealed trait AppError extends Throwable
  
  /** Configuration-related errors */
  case class ConfigError(msg: String) extends AppError
  
  /** Git operation errors */
  case class GitError(msg: String) extends AppError  
  
  /** Ollama AI service errors */
  case class OllamaError(msg: String) extends AppError
  
  /** MCP protocol errors */
  case class MCPError(msg: String) extends AppError
  
  /** Input/Output operation errors */
  case class IOError(msg: String) extends AppError
  
  /**
   * Opaque type for repository paths with validation.
   * Prevents invalid paths from being used throughout the application.
   */
  opaque type RepoPath = String
  
  object RepoPath {
    /**
     * Smart constructor for RepoPath with validation.
     * 
     * @param path The repository path to validate
     * @return Either a GitError if invalid, or a valid RepoPath
     */
    def apply(path: String): Either[GitError, RepoPath] =
      if (path.nonEmpty && !path.contains("..")) Right(path)
      else Left(GitError(s"Invalid repository path: $path"))
  }
  
  extension (rp: RepoPath) 
    /**
     * Extracts the underlying String value from RepoPath.
     * 
     * @return The repository path as String
     */
    def repoValue: String = rp
  
  /**
   * Opaque type for AI model names with validation.
   * Ensures only valid model identifiers are used.
   */
  opaque type ModelName = String
  
  object ModelName {
    /**
     * Smart constructor for ModelName with validation.
     * 
     * @param name The model name to validate
     * @return Either an OllamaError if invalid, or a valid ModelName
     */
    def apply(name: String): Either[OllamaError, ModelName] =
      if (name.nonEmpty && name.matches("[a-zA-Z0-9_-]+")) Right(name)
      else Left(OllamaError(s"Invalid model name: $name"))
  }
  
  extension (mn: ModelName) 
    /**
     * Extracts the underlying String value from ModelName.
     * 
     * @return The model name as String
     */
    def modelValue: String = mn
  
  /**
   * Git operations algebra using tagless final pattern.
   * Provides type-safe git operations with error handling.
   * 
   * @tparam F The effect type (IO, Task, etc.)
   */
  trait GitAlgebra[F[_]] {
    /**
     * Validates if a path is a valid git repository.
     * 
     * @param path The repository path to validate
     * @return F[Either[GitError, Boolean]] - true if valid repository
     */
    def isValidRepo(path: RepoPath): F[Either[GitError, Boolean]]
    
    /**
     * Gets list of changed files in the repository.
     * 
     * @param path The repository path
     * @return F[Either[GitError, List[String]]] - list of changed file paths
     */
    def getChanges(path: RepoPath): F[Either[GitError, List[String]]]
    
    /**
     * Commits a specific file with a message.
     * 
     * @param path The repository path
     * @param file The file path to commit
     * @param message The commit message
     * @return F[Either[GitError, String]] - the commit hash
     */
    def commitFile(path: RepoPath, file: String, message: String): F[Either[GitError, String]]
  }
  
  /**
   * Ollama AI service algebra using tagless final pattern.
   * Provides AI-powered commit message generation.
   * 
   * @tparam F The effect type (IO, Task, etc.)
   */
  trait OllamaAlgebra[F[_]] {
    /**
     * Generates a commit message using AI analysis of file changes.
     * 
     * @param model The AI model to use
     * @param file The file path that changed
     * @param diff The git diff content
     * @return F[Either[OllamaError, String]] - the generated commit message
     */
    def generateMessage(model: ModelName, file: String, diff: String): F[Either[OllamaError, String]]
  }
  
  /**
   * MCP (Model Context Protocol) service algebra.
   * Handles MCP protocol communication and request processing.
   * 
   * @tparam F The effect type (IO, Task, etc.)
   */
  trait MCPAlgebra[F[_]] {
    /**
     * Processes a single MCP protocol line/request.
     * 
     * @param line The JSON-RPC request line
     * @return F[Either[MCPError, String]] - the JSON-RPC response
     */
    def processLine(line: String): F[Either[MCPError, String]]
  }
  
  /**
   * Creates a GitAlgebra implementation using the provided GitService.
   * 
   * @param gitService The underlying git service implementation
   * @tparam F The effect type with Sync capability
   * @return A GitAlgebra instance
   */
  def gitAlgebra[F[_]: Sync](gitService: GitService[F]): GitAlgebra[F] = 
    new GitAlgebra[F] {
      def isValidRepo(path: RepoPath): F[Either[GitError, Boolean]] =
        gitService.isValidRepository(path.repoValue)
          .map(Right(_))
          .handleError(e => Left(GitError(e.getMessage)))
      
      def getChanges(path: RepoPath): F[Either[GitError, List[String]]] =
        gitService.getStatus(path.repoValue)
          .map(changes => Right(changes.map(_.filePath)))
          .handleError(e => Left(GitError(e.getMessage)))
      
      def commitFile(path: RepoPath, file: String, message: String): F[Either[GitError, String]] =
        (for {
          _ <- gitService.addFile(path.repoValue, file)
          hash <- gitService.commit(path.repoValue, message)
        } yield hash).map(Right(_)).handleError(e => Left(GitError(e.getMessage)))
    }
  
  /**
   * Creates an OllamaAlgebra implementation using the provided OllamaClient.
   * 
   * @param client The underlying Ollama client implementation
   * @tparam F The effect type with Sync capability
   * @return An OllamaAlgebra instance
   */
  def ollamaAlgebra[F[_]: Sync](client: OllamaClient[F]): OllamaAlgebra[F] =
    new OllamaAlgebra[F] {
      def generateMessage(model: ModelName, file: String, diff: String): F[Either[OllamaError, String]] =
        client.generateCommitMessage(model.modelValue, file, diff, com.rclabs.mcpgit.git.ChangeType.Modified)
          .map(Right(_))
          .handleError(e => Left(OllamaError(e.getMessage)))
    }
  
  // ...existing code for mcpAlgebra, run method, and runServer...

  /**
   * Creates an MCPAlgebra implementation using the provided Git and Ollama algebras.
   * This is the core business logic that orchestrates git operations and AI generation.
   * 
   * @param git The git operations algebra
   * @param ollama The Ollama AI algebra
   * @tparam F The effect type with Sync capability
   * @return An MCPAlgebra instance
   */
  def mcpAlgebra[F[_]: Sync](
    git: GitAlgebra[F], 
    ollama: OllamaAlgebra[F]
  ): MCPAlgebra[F] = new MCPAlgebra[F] {
    import MCPProtocol.given
    
    def processLine(line: String): F[Either[MCPError, String]] = {
      val program = for {
        // Parse JSON
        json <- EitherT.fromEither[F](
          parse(line).leftMap(e => MCPError(s"JSON parse error: ${e.getMessage}"))
        )
        
        // Decode MCP request
        request <- EitherT.fromEither[F](
          json.as[MCPRequest].leftMap(e => MCPError(s"MCP decode error: ${e.getMessage}"))
        )
        
        // Handle request
        response <- handleRequest(request)
        
        // Encode response
        responseJson = response.asJson.noSpaces
        
      } yield responseJson
      
      program.value
    }
    
    /**
     * Routes MCP requests to appropriate handlers based on method.
     * 
     * @param request The parsed MCP request
     * @return EitherT[F, MCPError, MCPResponse] - the response or error
     */
    private def handleRequest(request: MCPRequest): EitherT[F, MCPError, MCPResponse] = {
      request.method match {
        case "initialize" => handleInitialize(request)
        case "tools/list" => handleToolsList(request)
        case "tools/call" => handleToolCall(request)
        case _ => EitherT.leftT[F, MCPResponse](MCPError(s"Unknown method: ${request.method}"))
      }
    }
    
    /**
     * Handles MCP initialize requests.
     * 
     * @param request The initialize request
     * @return EitherT[F, MCPError, MCPResponse] - server capabilities and info
     */
    private def handleInitialize(request: MCPRequest): EitherT[F, MCPError, MCPResponse] = {
      val result = Json.obj(
        "protocolVersion" -> Json.fromString("2024-11-05"),
        "capabilities" -> Json.obj("tools" -> Json.obj()),
        "serverInfo" -> Json.obj(
          "name" -> Json.fromString("mcp-git-ollama-server"),
          "version" -> Json.fromString("0.1.0")
        )
      )
      EitherT.pure[F, MCPError](MCPResponse(id = request.id, result = Some(result)))
    }
    
    /**
     * Handles tools/list requests to advertise available tools.
     * 
     * @param request The tools list request
     * @return EitherT[F, MCPError, MCPResponse] - list of available tools
     */
    private def handleToolsList(request: MCPRequest): EitherT[F, MCPError, MCPResponse] = {
      val tools = List(GitCommitTool())
      val result = Json.obj("tools" -> tools.asJson)
      EitherT.pure[F, MCPError](MCPResponse(id = request.id, result = Some(result)))
    }
    
    /**
     * Handles tools/call requests to execute git commit automation.
     * 
     * @param request The tool call request with parameters
     * @return EitherT[F, MCPError, MCPResponse] - execution results
     */
    private def handleToolCall(request: MCPRequest): EitherT[F, MCPError, MCPResponse] = {
      for {
        // Extract parameters
        params <- EitherT.fromOption[F](
          request.params.flatMap(_.get("arguments")),
          MCPError("Missing arguments")
        )
        
        repoPathStr <- EitherT.fromOption[F](
          params.hcursor.downField("repository_path").as[String].toOption,
          MCPError("Missing repository_path")
        )
        
        modelStr = params.hcursor.downField("model").as[String].toOption.getOrElse("llama2")
        
        // Validate inputs
        repoPath <- EitherT.fromEither[F](
          RepoPath(repoPathStr).leftMap(e => MCPError(e.msg))
        )
        
        model <- EitherT.fromEither[F](
          ModelName(modelStr).leftMap(e => MCPError(e.msg))
        )
        
        // Process repository
        result <- processRepository(repoPath, model)
        
        // Create response
        content = Json.obj(
          "content" -> Json.arr(
            Json.obj(
              "type" -> Json.fromString("text"),
              "text" -> Json.fromString(result)
            )
          )
        )
        
      } yield MCPResponse(id = request.id, result = Some(content))
    }
    
    /**
     * Processes a git repository to generate and apply AI commit messages.
     * 
     * @param repoPath The validated repository path
     * @param model The validated AI model name
     * @return EitherT[F, MCPError, String] - processing results summary
     */
    private def processRepository(repoPath: RepoPath, model: ModelName): EitherT[F, MCPError, String] = {
      for {
        // Validate repository
        isValid <- EitherT(git.isValidRepo(repoPath))
          .leftMap(e => MCPError(e.msg))
        
        _ <- EitherT.cond[F](isValid, (), MCPError("Invalid repository"))
        
        // Get changes
        changes <- EitherT(git.getChanges(repoPath))
          .leftMap(e => MCPError(e.msg))
        
        // Process each file
        results <- changes.traverse { file =>
          processFile(repoPath, model, file)
        }
        
        // Format results
        summary = s"Processed ${results.length} files successfully"
        details = results.map(r => s"- $r").mkString("\n")
        
      } yield s"$summary\n\n$details"
    }
    
    /**
     * Processes a single file in the repository: generates commit message and commits the file.
     * 
     * @param repoPath The repository path
     * @param model The AI model name
     * @param file The file path to process
     * @return EitherT[F, MCPError, String] - result summary for the file
     */
    private def processFile(repoPath: RepoPath, model: ModelName, file: String): EitherT[F, MCPError, String] = {
      for {
        // Generate commit message (simplified - no diff for now)
        message <- EitherT(ollama.generateMessage(model, file, ""))
          .leftMap(e => MCPError(e.msg))
        
        // Commit file
        hash <- EitherT(git.commitFile(repoPath, file, message))
          .leftMap(e => MCPError(e.msg))
        
      } yield s"$file: $message (${hash.take(8)})"
    }
  }
  
  // Main application
  def run(args: List[String]): IO[ExitCode] = {
    given Logger[IO] = Slf4jLogger.getLogger[IO]
    
    val program = for {
      _ <- Logger[IO].info("🚀 Functional MCP Git-Ollama Server starting...")
      
      // Load config
      config <- AppConfig.load[IO]().handleErrorWith(e => 
        Logger[IO].error(s"Config error: ${e.getMessage}") *> 
        IO.raiseError(ConfigError(e.getMessage))
      )
      
      // Initialize services
      result <- OllamaClient.impl[IO](config.ollama).use { ollamaClient =>
        val gitService = GitService.impl[IO]
        val git = gitAlgebra(gitService)
        val ollama = ollamaAlgebra(ollamaClient)
        val mcp = mcpAlgebra(git, ollama)
        
        Logger[IO].info("✅ Services initialized, starting server...") *>
        runServer(mcp)
      }
      
    } yield result
    
    program.handleErrorWith { error =>
      Logger[IO].error(s"❌ Application error: ${error.getMessage}")
    }.as(ExitCode.Success)
  }
  
  // Pure functional server loop
  private def runServer(mcp: MCPAlgebra[IO])(using Logger[IO]): IO[Unit] = {
    def serverLoop(reader: BufferedReader): IO[Unit] = {
      for {
        lineOpt <- IO.delay(Option(reader.readLine()))
        _ <- lineOpt match {
          case Some(line) if line.trim.nonEmpty =>
            for {
              _ <- Logger[IO].debug(s"📥 Processing: ${line.take(50)}...")
              result <- mcp.processLine(line.trim)
              _ <- result match {
                case Right(response) => 
                  IO.println(response) *> 
                  Logger[IO].debug("✅ Response sent")
                case Left(error) => 
                  Logger[IO].error(s"❌ Processing error: ${error.msg}")
              }
              _ <- serverLoop(reader)
            } yield ()
          case Some(_) => 
            serverLoop(reader) // Empty line, continue
          case None => 
            Logger[IO].info("📡 EOF received, shutting down")
        }
      } yield ()
    }
    
    for {
      reader <- IO.delay(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)))
      _ <- Logger[IO].info("📡 Server listening on stdin...")
      _ <- serverLoop(reader)
    } yield ()
  }
}
