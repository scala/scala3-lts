package dotty.tools
package repl

import org.junit.Test
import org.junit.AfterClass

import vulpix.*

final class JSR223Tests:
  import JSR223Tests.{*, given}

  @Test def filetests: Unit =
    given TestGroup = TestGroup("runWithCompiler")
    compileFilesInDir("test-resources/jsr223", TestConfiguration.withReplOptions)
      .checkRuns()
  end filetests

end JSR223Tests

object JSR223Tests extends ParallelTesting