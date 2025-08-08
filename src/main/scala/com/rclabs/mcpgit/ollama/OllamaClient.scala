package com.rclabs.mcpgit.ollama

import cats.effect.*
import cats.syntax.all.*
import org.http4s.*
import org.http4s.client.*
import org.http4s.ember.client.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import io.circe.*
import io.circe.generic.semiauto.*
import com.rclabs.mcpgit.git.ChangeType
import com.rclabs.mcpgit.config.OllamaConfig

/**
 * Request model for Ollama API calls.
 *
 * @param model The name of the Ollama model to use
 * @param prompt The input text prompt for the model
 * @param options Additional generation options (temperature, max_tokens, etc.)
 */
case class OllamaRequest(
  model: String,
  prompt: String,
  options: Map[String, Json] = Map.empty
)

/**
 * Response model from Ollama API calls.
 *
 * @param response The generated text response from the model
 * @param done Whether the generation is complete
 * @param model The model that generated the response
 */
case class OllamaResponse(
  response: String,
  done: Boolean,
  model: String
)

/**
 * High-level interface for interacting with Ollama AI service.
 * Provides AI-powered commit message generation based on git changes.
 *
 * @tparam F The effect type (IO, Task, etc.)
 */
trait OllamaClient[F[_]] {
  /**
   * Generates a commit message by analyzing file changes using AI.
   *
   * @param model The Ollama model name to use for generation
   * @param filePath The path of the file that changed
   * @param diff The git diff content showing what changed
   * @param changeType The type of change (Added, Modified, Deleted, etc.)
   * @return F[String] The generated commit message
   */
  def generateCommitMessage(
    model: String,
    filePath: String,
    diff: String,
    changeType: ChangeType
  ): F[String]

  /**
   * Checks if a specific model is available in the Ollama instance.
   *
   * @param modelName The name of the model to check
   * @return F[Boolean] True if the model is available, false otherwise
   */
  def isModelAvailable(modelName: String): F[Boolean]

  /**
   * Retrieves the list of all available models from Ollama.
   *
   * @return F[List[String]] List of available model names
   */
  def listModels(): F[List[String]]
}

/**
 * Companion object providing OllamaClient implementations and codecs.
 */
object OllamaClient {
  /** JSON decoder for OllamaResponse */
  given Decoder[OllamaResponse] = deriveDecoder[OllamaResponse]

  /** JSON encoder for OllamaRequest */
  given Encoder[OllamaRequest] = deriveEncoder[OllamaRequest]

  /**
   * Creates an OllamaClient implementation using HTTP4S client.
   *
   * @param config The Ollama service configuration (base URL, timeouts, etc.)
   * @tparam F The effect type with Async capability
   * @return Resource[F, OllamaClient[F]] A managed OllamaClient instance
   */
  def impl[F[_]: Async](config: OllamaConfig): Resource[F, OllamaClient[F]] =
    EmberClientBuilder.default[F].build.map { client =>
      new OllamaClient[F] {

        def generateCommitMessage(
          model: String,
          filePath: String,
          diff: String,
          changeType: ChangeType
        ): F[String] = {
          val prompt = generatePrompt(changeType, filePath, diff)
          val request = OllamaRequest(
            model = model,
            prompt = prompt,
            options = Map(
              "temperature" -> Json.fromDoubleOrNull(0.3), // Lower temperature for more focused output
              "max_tokens" -> Json.fromInt(200), // Increased for better commit messages
              "top_p" -> Json.fromDoubleOrNull(0.9), // Allow more diverse responses
              "stop" -> Json.arr(Json.fromString("\n")) // Only stop at newline, not period
            )
          )
          
          val uri = Uri.unsafeFromString(s"${config.baseUrl}/api/generate")
          val httpRequest = Request[F](Method.POST, uri)
            .withEntity(request)

          client.expect[OllamaResponse](httpRequest).map { response =>
            cleanCommitMessage(response.response)
          }
        }

        def isModelAvailable(modelName: String): F[Boolean] =
          listModels().map(_.contains(modelName))

        def listModels(): F[List[String]] = {
          val uri = Uri.unsafeFromString(s"${config.baseUrl}/api/tags")
          val httpRequest = Request[F](Method.GET, uri)
          
          client.expect[Json](httpRequest).map { json =>
            json.hcursor.downField("models").as[List[Json]] match {
              case Right(models) =>
                models.flatMap(_.hcursor.downField("name").as[String].toOption)
              case Left(_) => List.empty
            }
          }
        }

        /**
         * Generates context-aware prompts for different types of git changes.
         *
         * @param changeType The type of git change
         * @param filePath The path of the changed file
         * @param diff The git diff content
         * @return String The formatted prompt for the AI model
         */
        private def generatePrompt(changeType: ChangeType, filePath: String, diff: String): String = {
          val baseInstruction = "You are a git commit message generator. Return ONLY the commit message, nothing else. No explanations, no quotes, no extra text."

          val truncatedDiff = if (diff.length > 1000) diff.take(1000) + "..." else diff

          changeType match {
            case ChangeType.Added =>
              s"""$baseInstruction
                 
                 Task: Generate a commit message for adding file: $filePath
                 Changes:
                 $truncatedDiff
                 
                 Format: Use conventional commits (feat:, fix:, docs:, etc.) or simple present tense
                 Examples: "Add user authentication", "feat: implement login system", "Create README file"
                 
                 Commit message:""".stripMargin
                 
            case ChangeType.Modified =>
              s"""$baseInstruction
                 
                 Task: Generate a commit message for modifying file: $filePath
                 Changes:
                 $truncatedDiff
                 
                 Format: Use conventional commits (feat:, fix:, docs:, etc.) or simple present tense
                 Examples: "Fix login validation", "Update API documentation", "Refactor user service"
                 
                 Commit message:""".stripMargin
                 
            case ChangeType.Deleted =>
              s"""$baseInstruction
                 
                 Task: Generate a commit message for deleting file: $filePath
                 
                 Format: Use conventional commits or simple present tense
                 Examples: "Remove deprecated API", "Delete unused components", "Clean up test files"
                 
                 Commit message:""".stripMargin
                 
            case ChangeType.Renamed =>
              s"""$baseInstruction
                 
                 Task: Generate a commit message for renaming file: $filePath
                 
                 Format: Use conventional commits or simple present tense
                 Examples: "Rename UserService to AuthService", "Move config files to settings/"
                 
                 Commit message:""".stripMargin
                 
            case ChangeType.Copied =>
              s"""$baseInstruction
                 
                 Task: Generate a commit message for copying file: $filePath
                 
                 Format: Use conventional commits or simple present tense
                 Examples: "Copy template for new feature", "Duplicate config for staging"
                 
                 Commit message:""".stripMargin
          }
        }
        
        /**
         * Cleans and normalizes the raw AI response to produce a proper commit message.
         *
         * @param rawResponse The raw response from the AI model
         * @return String The cleaned commit message following git conventions
         */
        private def cleanCommitMessage(rawResponse: String): String = {
          val cleaned = rawResponse
            .trim
            .replaceAll("^[\"'`]", "") // Remove leading quotes
            .replaceAll("[\"'`]$", "") // Remove trailing quotes
            .replaceAll("^(Commit message:|Message:|Here's the commit message:|The commit message is:)\\s*", "") // Remove common prefixes
            .replaceAll("\\n.*", "") // Take only the first line
            .trim
          
          // Ensure it doesn't end with a period (git convention)
          val withoutPeriod = if (cleaned.endsWith(".")) cleaned.dropRight(1) else cleaned
          
          // Capitalize first letter if it's not already
          if (withoutPeriod.nonEmpty && withoutPeriod.head.isLower) {
            withoutPeriod.head.toUpper + withoutPeriod.tail
          } else {
            withoutPeriod
          }
        }
      }
    }
}
