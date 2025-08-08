package com.rclabs.mcpgit.git

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import java.nio.file.{Files, Path}
import java.io.File

class GitServiceSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {
  
  "GitService" should {
    "detect valid git repository" in {
      val gitService = GitService.impl[IO]
      
      // Test with a temporary directory that's not a git repo
      val tempDir = Files.createTempDirectory("test-repo")
      try {
        gitService.isValidRepository(tempDir.toString).asserting(_ should be(false))
      } finally {
        Files.deleteIfExists(tempDir)
      }
    }
    
    "handle invalid repository path" in {
      val gitService = GitService.impl[IO]
      
      gitService.isValidRepository("/nonexistent/path").asserting(_ should be(false))
    }
  }
}
