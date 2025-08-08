package com.rclabs.mcpgit.git

import cats.effect.Sync
import cats.syntax.all.*
import java.io.File
import java.nio.file.{Files, Paths}
import scala.sys.process.*

/**
 * Enumeration of Git change types for files in a repository.
 */
enum ChangeType:
  case Added, Modified, Deleted, Renamed, Copied

/**
 * Represents a file change in a Git repository.
 * 
 * @param filePath The path to the changed file
 * @param changeType The type of change (Added, Modified, Deleted, etc.)
 * @param linesAdded Number of lines added in this change
 * @param linesDeleted Number of lines deleted in this change
 */
case class GitFileChange(
  filePath: String,
  changeType: ChangeType,
  linesAdded: Int,
  linesDeleted: Int
)

/**
 * Service trait for Git operations.
 * Provides an abstraction over Git commands for repository management.
 */
trait GitService[F[_]] {
  /** Get the status of all files in the repository */
  def getStatus(repoPath: String): F[List[GitFileChange]]
  
  /** Get the diff for a specific file */
  def getDiff(repoPath: String, filePath: String): F[String]
  
  /** Get the diff for all changes in the repository */
  def getAllDiff(repoPath: String): F[String]
  
  /** Get the diff for staged changes only */
  def getStagedDiff(repoPath: String): F[String]
  
  /** Get the diff for a specific file, optionally staged */
  def getFileDiff(repoPath: String, filePath: String, staged: Boolean = false): F[String]
  
  /** Add a file to the Git staging area */
  def addFile(repoPath: String, filePath: String): F[Unit]
  
  /** Commit staged changes with the given message */
  def commit(repoPath: String, message: String): F[String]
  
  /** Check if the given path is a valid Git repository */
  def isValidRepository(repoPath: String): F[Boolean]
}

/**
 * Companion object providing concrete implementations of GitService.
 */
object GitService {
  def impl[F[_]: Sync]: GitService[F] = new GitService[F] {
    
    def getStatus(repoPath: String): F[List[GitFileChange]] = 
      Sync[F].delay {
        val cmd = Process(Seq("git", "status", "--porcelain"), new File(repoPath))
        val output = cmd.!!
        
        output.split("\n").filter(_.nonEmpty).map { line =>
          val status = line.take(2)
          val filePath = line.drop(3)
          val changeType = status.trim match {
            case "A" | "??" => ChangeType.Added
            case "M" => ChangeType.Modified
            case "D" => ChangeType.Deleted
            case "R" => ChangeType.Renamed
            case "C" => ChangeType.Copied
            case _ => ChangeType.Modified
          }
          
          // Get line counts from diff
          val (added, deleted) = try {
            val diffCmd = Process(Seq("git", "diff", "--numstat", filePath), new File(repoPath))
            val diffOutput = diffCmd.!!.trim
            if (diffOutput.nonEmpty) {
              val parts = diffOutput.split("\t")
              (parts(0).toIntOption.getOrElse(0), parts(1).toIntOption.getOrElse(0))
            } else (0, 0)
          } catch {
            case _: Exception => (0, 0)
          }
          
          GitFileChange(filePath, changeType, added, deleted)
        }.toList
      }
    
    def getDiff(repoPath: String, filePath: String): F[String] =
      Sync[F].delay {
        val cmd = Process(Seq("git", "diff", filePath), new File(repoPath))
        cmd.!!
      }

    def getAllDiff(repoPath: String): F[String] =
      Sync[F].delay {
        val cmd = Process(Seq("git", "diff"), new File(repoPath))
        cmd.!!
      }

    def getStagedDiff(repoPath: String): F[String] =
      Sync[F].delay {
        val cmd = Process(Seq("git", "diff", "--cached"), new File(repoPath))
        cmd.!!
      }

    def getFileDiff(repoPath: String, filePath: String, staged: Boolean): F[String] =
      Sync[F].delay {
        val cmd = if (staged)
          Process(Seq("git", "diff", "--cached", "--", filePath), new File(repoPath))
        else
          Process(Seq("git", "diff", "--", filePath), new File(repoPath))

        cmd.!!
      }

    def addFile(repoPath: String, filePath: String): F[Unit] =
      Sync[F].delay {
        val cmd = Process(Seq("git", "add", filePath), new File(repoPath))
        cmd.!!
        ()
      }
    
    def commit(repoPath: String, message: String): F[String] =
      Sync[F].delay {
        val cmd = Process(Seq("git", "commit", "-m", message), new File(repoPath))
        cmd.!!
      }
    
    def isValidRepository(repoPath: String): F[Boolean] =
      Sync[F].delay {
        Files.exists(Paths.get(repoPath, ".git"))
      }
  }
}
