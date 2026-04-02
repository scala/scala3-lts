package dotty.tools
package dotc
package reporting

import core.Contexts.*
import java.io.{ BufferedReader, PrintWriter }
import Diagnostic.Error

/**
  * This class implements a Reporter that displays messages on a text console
  */
class ConsoleReporter(
  reader: BufferedReader | Null = Console.in,
  writer: PrintWriter = new PrintWriter(Console.err, true),
  echoer: PrintWriter = new PrintWriter(Console.out, true)
) extends ConsoleReporter.AbstractConsoleReporter {
  override def printMessage(msg: String): Unit = { writer.print(msg + "\n"); writer.flush() }
  override def echoMessage(msg: String): Unit = { echoer.println(msg); echoer.flush() }
  override def flush()(using Context): Unit    = writer.flush()

  override def doReport(dia: Diagnostic)(using Context): Unit = {
    super.doReport(dia)
    dia match
      case dia: Error if ctx.settings.Xprompt.value => Reporter.displayPrompt(reader, writer)
      case _                                        =>
  }
}

object ConsoleReporter {
  abstract class AbstractConsoleReporter extends AbstractReporter {
    /** Prints the message. */
    def printMessage(msg: String): Unit

    /** Print the informative message. */
    def echoMessage(msg: String): Unit

    /** Prints the message with the given position indication. */
    def doReport(dia: Diagnostic)(using Context): Unit = {
      printMessage(messageAndPos(dia))
    }
  }
}
