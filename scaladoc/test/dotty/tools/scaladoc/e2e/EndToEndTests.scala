package dotty.tools.scaladoc
package e2e

import java.nio.file.Files
import java.io.File
import org.junit.Test
import org.junit.Assert.*
import dotty.tools.scaladoc.util.IO
import dotty.tools.dotc.reporting.Reporter

/** End-to-end Scaladoc runs that compile sources at test time and assert on diagnostics. */
class EndToEndTests:
  
  private def run(dirName: String, fileNames: String*)(callback: (File, Reporter) => Unit): Unit =
    val root = Files.createTempDirectory("scaladoc-e2e")
    try
      val output = root.resolve("classes")
      compileStage(output, Nil, fileNames.map(copyTestResource(root, dirName, _))*)
      val docOutput = root.resolve("doc").toFile
      java.nio.file.Files.createDirectories(docOutput.toPath)
      val tasty = collectTastyFiles(output)
      assert(tasty.nonEmpty, s"Expected .tasty files under $output")
      val args = Array(
        "-project", "Test Project Name",
        "-d", docOutput.toString,
        "-cp", Seq(output.toString, javaClasspath).mkString(java.io.File.pathSeparator)
      ) ++ tasty.map(_.toString)
      val reporter = (new dotty.tools.scaladoc.Main).run(args)
      callback(docOutput, reporter)
    finally
      IO.delete(root.toFile)

  private def ensureContains(docOutput: File, packageName: String, fileName: String, texts: String*): Unit = {
    val contents = IO.read(new File(new File(docOutput, packageName), fileName).toPath)
    texts.foreach(text =>
      assertTrue(s"$packageName/$fileName should contain '$text'.\nFull contents:\n$contents", contents.contains(text))
    )
  }

  @Test
  def i26627 =
    val root = Files.createTempDirectory("scaladoc-i26627")
    try
      val output = root.resolve("classes")
      compileStage(output, Nil, copyTestResource(root, "i26627", "lazyFuture.scala"))

      val ctx = testContext
      val docOutput = root.resolve("doc").toFile
      val tasty = collectTastyFiles(output)
      assert(tasty.nonEmpty, s"Expected .tasty files under $output")
      Scaladoc.run(
        testArgs(tasty, docOutput).copy(
          classpath = Seq(output.toString, javaClasspath)
            .mkString(java.io.File.pathSeparator)
        )
      )(using ctx)

      val diagnostics = ctx.reportedDiagnostics
      val linkWarnings = diagnostics.warningMsgs.filter(msg =>
        msg.contains("Couldn't resolve a member for the given link query")
          || msg.contains("Unable to find a link for")
      )
      org.junit.Assert.assertEquals(
        s"Unexpected unresolved link warnings:\n${linkWarnings.mkString("\n")}",
        Nil,
        linkWarnings
      )
      assertNoErrors(diagnostics)
    finally IO.delete(root.toFile)

  @Test
  def exportMethods(): Unit = run("exports", "exports.scala") { (out, _) =>
    ensureContains(out, "pkg", "Midrange.html", "parameterless method 1", "method with empty parameter list 1", "method with one parameter 1")
    ensureContains(out, "pkg", "Treble.html", "parameterless method 2", "method with empty parameter list 2", "method with one parameter 2")
  }

  @Test
  def warningPosition(): Unit = run("warningPosition", "warningPosition.scala") { (_, reporter) =>
    assertEquals(
      reporter.allWarnings.mkString("\n"),
      2,
      reporter.allWarnings.count(m => m.message.contains("Couldn't resolve"))
    )
  }
