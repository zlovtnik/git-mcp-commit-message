package com.rclabs.mcpgit.server

import io.circe.*
import io.circe.syntax.*
import io.circe.generic.semiauto.*
import cats.syntax.functor.*

/**
 * Model Context Protocol (MCP) request structure following JSON-RPC 2.0 specification.
 *
 * @param jsonrpc The JSON-RPC version (always "2.0")
 * @param id Optional request identifier for matching responses
 * @param method The MCP method name (e.g., "initialize", "tools/list", "tools/call")
 * @param params Optional parameters for the method call
 */
case class MCPRequest(
  jsonrpc: String = "2.0",
  id: Option[String],
  method: String,
  params: Option[Map[String, Json]]
)

/**
 * Model Context Protocol (MCP) response structure following JSON-RPC 2.0 specification.
 *
 * @param jsonrpc The JSON-RPC version (always "2.0")
 * @param id Request identifier matching the original request
 * @param result Optional success result data
 * @param error Optional error information if the request failed
 */
case class MCPResponse(
  jsonrpc: String = "2.0",
  id: Option[String],
  result: Option[Json] = None,
  error: Option[MCPError] = None
)

/**
 * Error information structure for MCP protocol errors.
 *
 * @param code Numeric error code following JSON-RPC conventions
 * @param message Human-readable error description
 * @param data Optional additional error data
 */
case class MCPError(
  code: Int,
  message: String,
  data: Option[Json] = None
)

/**
 * Common trait for MCP tools
 */
trait MCPTool {
  def name: String
  def description: String
  def inputSchema: Json
}

/**
 * Tool definition for the git auto-commit functionality.
 * Defines the schema and parameters for AI-powered git commit automation.
 *
 * @param name The tool identifier ("git_auto_commit")
 * @param description Human-readable description of what the tool does
 * @param inputSchema JSON schema defining the expected input parameters
 */
case class GitCommitTool(
  name: String = "git_auto_commit",
  description: String = "Analyze git changes and commit files with AI-generated messages",
  inputSchema: Json = Json.obj(
    "type" -> Json.fromString("object"),
    "properties" -> Json.obj(
      "repository_path" -> Json.obj(
        "type" -> Json.fromString("string"),
        "description" -> Json.fromString("Path to the git repository")
      ),
      "model" -> Json.obj(
        "type" -> Json.fromString("string"),
        "description" -> Json.fromString("Ollama model to use"),
        "default" -> Json.fromString("llama2")
      ),
      "commit_individually" -> Json.obj(
        "type" -> Json.fromString("boolean"),
        "description" -> Json.fromString("Commit each file separately"),
        "default" -> Json.fromBoolean(true)
      )
    ),
    "required" -> Json.arr(Json.fromString("repository_path"))
  )
) extends MCPTool

/**
 * Tool definition for showing git diffs.
 * Allows viewing the diff of changes in a git repository.
 *
 * @param name The tool identifier ("git_show_diff")
 * @param description Human-readable description of what the tool does
 * @param inputSchema JSON schema defining the expected input parameters
 */
case class GitShowDiffTool(
  name: String = "git_show_diff",
  description: String = "Show git diff for changes in the repository",
  inputSchema: Json = Json.obj(
    "type" -> Json.fromString("object"),
    "properties" -> Json.obj(
      "repository_path" -> Json.obj(
        "type" -> Json.fromString("string"),
        "description" -> Json.fromString("Path to the git repository")
      ),
      "file_path" -> Json.obj(
        "type" -> Json.fromString("string"),
        "description" -> Json.fromString("Specific file to show diff for (optional)")
      ),
      "staged" -> Json.obj(
        "type" -> Json.fromString("boolean"),
        "description" -> Json.fromString("Show staged changes only"),
        "default" -> Json.fromBoolean(false)
      )
    ),
    "required" -> Json.arr(Json.fromString("repository_path"))
  )
) extends MCPTool

/**
 * Companion object providing JSON codecs for MCP protocol types.
 */
object MCPProtocol {
  /** JSON encoder/decoder for MCPRequest */
  given Codec[MCPRequest] = deriveCodec[MCPRequest]
  
  /** JSON encoder/decoder for MCPResponse */
  given Codec[MCPResponse] = deriveCodec[MCPResponse]
  
  /** JSON encoder/decoder for MCPError */
  given Codec[MCPError] = deriveCodec[MCPError]
  
  /** JSON encoder/decoder for GitCommitTool */
  given Codec[GitCommitTool] = deriveCodec[GitCommitTool]
  
  /** JSON encoder/decoder for GitShowDiffTool */
  given Codec[GitShowDiffTool] = deriveCodec[GitShowDiffTool]
  
  /** JSON encoder for MCPTool trait */
  given Encoder[MCPTool] = Encoder.instance {
    case tool: GitCommitTool => tool.asJson
    case tool: GitShowDiffTool => tool.asJson
  }
  
  /** JSON decoder for MCPTool trait */
  given Decoder[MCPTool] = 
    Decoder[GitCommitTool].widen[MCPTool].or(Decoder[GitShowDiffTool].widen[MCPTool])
}
