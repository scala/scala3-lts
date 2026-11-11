package dotty

import java.nio.file.*

/** Runtime properties from defines or environment */
object Properties {

  /** If property is unset or FALSE we consider it `false` */
  private def propIsTrue(name: String): Boolean =
    sys.props.getOrElse(name, "FALSE") == "TRUE"

  /** Are we running on the CI? */
  val isRunByCI: Boolean = sys.env.isDefinedAt("DOTTY_CI_RUN")

  /** Filter out tests not matching the regex supplied by "dotty.tests.filter"
   *  define
   */
  val testsFilter: List[String] = sys.props.get("dotty.tests.filter").fold(Nil)(_.split(',').toList)

  /** Run only failed tests */
  val rerunFailed: Boolean = propIsTrue("dotty.tests.rerunFailed")

  /** Tests should override the checkfiles with the current output */
  val testsUpdateCheckfile: Boolean = propIsTrue("dotty.tests.updateCheckfiles")

  /** Enable Scoverage instrumentation for compilation tests */
  val testsInstrumentCoverage: Boolean = propIsTrue("dotty.tests.instrumentCoverage")

  /** dotty-interfaces jar */
  def dottyInterfaces: String = sys.props("dotty.tests.classes.dottyInterfaces").nn

  /** dotty-compiler jar */
  def dottyCompiler: String = sys.props("dotty.tests.classes.dottyCompiler").nn

  /** dotty-repl jar */
  def dottyRepl: String = sys.props("dotty.tests.classes.dottyRepl").nn

  /** scala3-directives-parser jar */
  def dottyDirectivesParser: String = sys.props("dotty.tests.classes.dottyDirectivesParser").nn

  /** coursier interface jar, used by the REPL to resolve dependencies */
  def coursierInterface: String = sys.props("dotty.tests.classes.coursierInterface").nn

  /** dotty-staging jar */
  def dottyStaging: String = sys.props("dotty.tests.classes.dottyStaging").nn

  /** dotty-tasty-inspector jar */
  def dottyTastyInspector: String = sys.props("dotty.tests.classes.dottyTastyInspector").nn

  /** tasty-core jar */
  def tastyCore: String = sys.props("dotty.tests.classes.tastyCore").nn

  /** compiler-interface jar */
  def compilerInterface: String = sys.props("dotty.tests.classes.compilerInterface").nn

  /** scala-library jar */
  def scalaLibrary: String = sys.props("dotty.tests.classes.scalaLibrary").nn

  // TODO: Remove this once we migrate the test suite
  def usingScalaLibraryCCTasty: Boolean = true

  // TODO: Remove this once we migrate the test suite
  def usingScalaLibraryTasty: Boolean = true

  /** scala-asm jar */
  def scalaAsm: String = sys.props("dotty.tests.classes.scalaAsm").nn

  /** jline-terminal jar */
  def jlineTerminal: String = sys.props("dotty.tests.classes.jlineTerminal").nn

  /** jline-reader jar */
  def jlineReader: String = sys.props("dotty.tests.classes.jlineReader").nn

  /** scala-xml jar */
  def scalaXml: String = sys.props("dotty.tests.classes.scalaXml").nn

  /** scalajs-javalib jar */
  def scalaJSJavalib: String = sys.props("dotty.tests.classes.scalaJSJavalib").nn

  /** scalajs-scalalib jar */
  def scalaJSScalalib: String = sys.props("dotty.tests.classes.scalaJSScalalib").nn

  /** scalajs-library jar */
  def scalaJSLibrary: String = sys.props("dotty.tests.classes.scalaJSLibrary").nn
}
