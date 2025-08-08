package com.rclabs.mcpgit.core

import cats.effect.*
import cats.effect.std.Semaphore
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.rclabs.mcpgit.git.{GitService, ChangeType}
import com.rclabs.mcpgit.ollama.OllamaClient
import com.rclabs.mcpgit.config.ProcessingConfig

/**
 * Represents the stage of processing where an error occurred.
 */
enum ProcessingStage:
  case GitStatus, GitDiff, OllamaGeneration, GitAdd, GitCommit

/**
 * Represents an error that occurred during file processing.
 *
 * @param filePath The file path where the error occurred
 * @param error The error message
 * @param stage The processing stage where the error occurred
 */
case class ProcessingError(
  filePath: String,
  error: String,
  stage: ProcessingStage
)

/**
 * Represents a successfully processed file.
 *
 * @param filePath The path of the processed file
 * @param commitMessage The generated commit message
 * @param commitHash The resulting commit hash (if successful)
 * @param processingTimeMs Time taken to process this file in milliseconds
 */
case class ProcessedFile(
  filePath: String,
  commitMessage: String,
  commitHash: Option[String],
  processingTimeMs: Long
)

/**
 * Overall result of repository processing.
 *
 * @param processedFiles List of successfully processed files
 * @param errors List of errors encountered during processing
 * @param totalCommits Total number of commits made
 */
case class ProcessingResult(
  processedFiles: List[ProcessedFile],
  errors: List[ProcessingError],
  totalCommits: Int
)

trait CommitProcessor[F[_]] {
  def processRepository(
    repoPath: String,
    model: String,
    commitIndividually: Boolean
  ): F[ProcessingResult]
}

object CommitProcessor {
  def impl[F[_]: Async](
    gitService: GitService[F],
    ollamaClient: OllamaClient[F],
    config: ProcessingConfig
  ): CommitProcessor[F] = new CommitProcessor[F] {

    given Logger[F] = Slf4jLogger.getLogger[F]

    def processRepository(
      repoPath: String,
      model: String,
      commitIndividually: Boolean
    ): F[ProcessingResult] = {
      Logger[F].info(s"Starting repository processing: $repoPath") *>
      Logger[F].debug(s"Configuration: model=$model, commitIndividually=$commitIndividually, maxConcurrent=${config.maxConcurrentFiles}") *>
      (for {
        semaphore <- Semaphore[F](config.maxConcurrentFiles)
        _ <- Logger[F].debug("Created semaphore for concurrent processing")
        isValid <- gitService.isValidRepository(repoPath)
        _ <- Logger[F].debug(s"Repository validation result: $isValid")
        result <- if (isValid) {
          Logger[F].info("Repository is valid, proceeding with processing") *>
          processValidRepository(repoPath, model, commitIndividually, semaphore)
        } else {
          Logger[F].error(s"Invalid git repository: $repoPath") *>
          Async[F].pure(ProcessingResult(
            processedFiles = List.empty,
            errors = List(ProcessingError(repoPath, "Invalid git repository", ProcessingStage.GitStatus)),
            totalCommits = 0
          ))
        }
        _ <- Logger[F].info(s"Repository processing completed: ${result.totalCommits} commits, ${result.errors.length} errors")
      } yield result)
    }

    private def processValidRepository(
      repoPath: String,
      model: String,
      commitIndividually: Boolean,
      semaphore: Semaphore[F]
    ): F[ProcessingResult] = {
      for {
        changes <- gitService.getStatus(repoPath)

        results <- if (commitIndividually) {
          processFilesIndividually(repoPath, model, changes, semaphore)
        } else {
          processFilesBatch(repoPath, model, changes)
        }
      } yield results
    }

    private def processFilesIndividually(
      repoPath: String,
      model: String,
      changes: List[com.rclabs.mcpgit.git.GitFileChange],
      semaphore: Semaphore[F]
    ): F[ProcessingResult] = {
      changes.traverse { change =>
        semaphore.permit.use { _ =>
          processFile(repoPath, model, change)
        }
      }.map { results =>
        val (failures, successes) = results.partitionMap(identity)
        ProcessingResult(
          processedFiles = successes,
          errors = failures,
          totalCommits = successes.length
        )
      }
    }

    private def processFilesBatch(
      repoPath: String,
      model: String,
      changes: List[com.rclabs.mcpgit.git.GitFileChange]
    ): F[ProcessingResult] = {
      // For batch processing, we'd combine all changes into a single commit
      // This is a simplified implementation
      if (changes.isEmpty) {
        Async[F].pure(ProcessingResult(List.empty, List.empty, 0))
      } else {
        val startTime = System.currentTimeMillis()
        for {
          // Add all files
          _ <- changes.traverse(change => gitService.addFile(repoPath, change.filePath))

          // Generate combined commit message
          combinedDiff <- changes.traverse(change =>
            gitService.getDiff(repoPath, change.filePath)
          ).map(_.mkString("\n"))

          commitMessage <- ollamaClient.generateCommitMessage(
            model,
            "multiple files",
            combinedDiff,
            ChangeType.Modified
          )

          commitHash <- gitService.commit(repoPath, commitMessage)
          endTime = System.currentTimeMillis()

          processedFile = ProcessedFile(
            filePath = changes.map(_.filePath).mkString(", "),
            commitMessage = commitMessage,
            commitHash = Some(commitHash),
            processingTimeMs = endTime - startTime
          )
        } yield ProcessingResult(
          processedFiles = List(processedFile),
          errors = List.empty,
          totalCommits = 1
        )
      }
    }

    private def processFile(
      repoPath: String,
      model: String,
      change: com.rclabs.mcpgit.git.GitFileChange
    ): F[Either[ProcessingError, ProcessedFile]] = {
      val startTime = System.currentTimeMillis()

      (for {
        diff <- gitService.getDiff(repoPath, change.filePath)
        commitMessage <- ollamaClient.generateCommitMessage(
          model,
          change.filePath,
          diff,
          change.changeType
        )

        // Truncate message if too long
        truncatedMessage = if (commitMessage.length > config.commitMessageMaxLength) {
          commitMessage.take(config.commitMessageMaxLength - 3) + "..."
        } else commitMessage

        _ <- gitService.addFile(repoPath, change.filePath)
        commitHash <- gitService.commit(repoPath, truncatedMessage)

        endTime = System.currentTimeMillis()
        processedFile = ProcessedFile(
          filePath = change.filePath,
          commitMessage = truncatedMessage,
          commitHash = Some(commitHash),
          processingTimeMs = endTime - startTime
        )
      } yield processedFile).attempt.map {
        case Right(file) => Right(file)
        case Left(error) => Left(ProcessingError(
          filePath = change.filePath,
          error = error.getMessage,
          stage = ProcessingStage.GitCommit
        ))
      }
    }
  }
}

/**
 * Simple implementation of CommitProcessor that doesn't require OllamaClient.
 * Uses GitService directly for cases where we already have CommitProcessor dependency.
 */
class SimpleCommitProcessor[F[_]: Async: Logger](gitService: GitService[F]) extends CommitProcessor[F] {
  def processRepository(
    repoPath: String,
    model: String,
    commitIndividually: Boolean
  ): F[ProcessingResult] = {
    // This is a simplified version that would need OllamaClient for full functionality
    // For now, we'll just validate the repository
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
