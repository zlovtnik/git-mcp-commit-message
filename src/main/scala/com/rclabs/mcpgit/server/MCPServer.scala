package com.rclabs.mcpgit.server

import cats.effect.*
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import com.rclabs.mcpgit.core.CommitProcessor
import com.rclabs.mcpgit.server.MCPProtocol.given

/**
 * MCP (Model Context Protocol) server implementation.
 * Handles JSON-RPC communication over stdin/stdout.
 *
 * @tparam F The effect type (IO, etc.)
 */
class MCPServer[F[_]: Async](commitProcessor: CommitProcessor[F])(using Logger[F]) {
  private val requestHandler = new RequestHandler[F]

  def start(): F[Unit] = {
    for {
      _ <- Logger[F].info("🚀 MCP Git-Ollama Server starting...")
      _ <- Logger[F].info("📡 Listening on stdin for JSON-RPC requests...")
      reader <- Async[F].delay(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)))
      _ <- Logger[F].debug("Starting read loop...")
      _ <- readLoop(reader)
    } yield ()
  }
  
  private def readLoop(reader: BufferedReader): F[Unit] = {
    for {
      _ <- Logger[F].debug("Waiting for input line...")
      _ <- Logger[F].debug(s"Reader ready: ${reader.ready()}")
      _ <- Logger[F].debug(s"System.in available: ${System.in.available()}")
      lineOpt <- Async[F].delay {
        try {
          val line = reader.readLine()
          println(s"DEBUG: Read line from BufferedReader: '$line'")
          Option(line)
        } catch {
          case ex: Exception => 
            println(s"DEBUG: Exception reading line: ${ex.getMessage}")
            None
        }
      }
      _ <- Logger[F].debug(s"Received line: ${lineOpt.getOrElse("null")}")
      _ <- lineOpt match {
        case Some(line) if line.trim.nonEmpty =>
          for {
            _ <- Logger[F].debug(s"Processing non-empty line: $line")
            _ <- processLine(line.trim)
            _ <- Logger[F].debug("Line processed, continuing to read...")
            _ <- readLoop(reader)
          } yield ()
        case Some(line) =>
          // Empty line, continue reading
          Logger[F].debug("Received empty line, continuing...") *>
          readLoop(reader)
        case None =>
          Logger[F].info("EOF received, shutting down")
      }
    } yield ()
  }
  
  private def processLine(line: String): F[Unit] = {
    for {
      _ <- Logger[F].debug(s"Parsing JSON from line: $line")
      result <- parse(line) match {
        case Right(json) =>
          for {
            _ <- Logger[F].debug(s"Successfully parsed JSON: $json")
            result <- json.as[MCPRequest] match {
              case Right(request) =>
                for {
                  _ <- Logger[F].info(s"Processing MCP request: ${request.method} (id: ${request.id})")
                  _ <- Logger[F].debug(s"Request params: ${request.params}")
                  response <- requestHandler.handleRequest(request)
                  responseJson = response.asJson.noSpaces
                  _ <- Logger[F].debug(s"Generated response: $responseJson")
                  _ <- Async[F].delay(println(responseJson))
                  _ <- Logger[F].info(s"Sent response for request ${request.id}")
                } yield ()
              case Left(decodeError) =>
                for {
                  _ <- Logger[F].error(s"Failed to decode MCP request from JSON: $json")
                  _ <- Logger[F].error(s"Decode error: $decodeError")
                  _ <- sendErrorResponse(None, -32700, "Parse error")
                } yield ()
            }
          } yield result
        case Left(parseError) =>
          for {
            _ <- Logger[F].error(s"Failed to parse JSON from line: $line")
            _ <- Logger[F].error(s"Parse error: $parseError")
            _ <- sendErrorResponse(None, -32700, "Parse error")
          } yield ()
      }
    } yield result
  }
  
  private def sendErrorResponse(id: Option[String], code: Int, message: String): F[Unit] = {
    val response = MCPResponse(
      id = id,
      error = Some(MCPError(code, message))
    )
    Async[F].delay(println(response.asJson.noSpaces))
  }
}

object MCPServer {
  def impl[F[_]: Async: Logger](commitProcessor: CommitProcessor[F]): MCPServer[F] =
    new MCPServer[F](commitProcessor)
}
