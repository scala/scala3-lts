package dotty.tools.scaladoc
package noLinkWarnings

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkWarningsTest extends ScaladocTest("noLinkWarnings"):

  override def args = Scaladoc.Args(
    name = "test",
    tastyFiles = tastyFiles(name),
    output = getTempDir().getRoot,
    projectVersion = Some("1.0")
  )

  @Test
  def runTest(): Unit = afterRendering {
    val diagnostics = summon[DocContext].compilerContext.reportedDiagnostics
    assertEquals("There should be exactly one warning", 1, diagnostics.warningMsgs.size)
    assertNoErrors(diagnostics)
  }
