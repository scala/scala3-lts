import scala.language.experimental.subCases

enum E:
  case A(e: E)
  case B(e: E)
  case C

  def f: E = ???
end E
import E.*

object Test:
  val e: E = ???

  e match // warn: match may not be exhaustive: It would fail on pattern case: E.A(_) | E.B(_)
    case A(e1) if e1.f match
      case B(_) => 11
      case C => 12
    case B(e1) if e1.f match
      case C => 21
      case A(_) => 22
    case C => 3

  e match // warn: match may not be exhaustive: It would fail on pattern case: E.B(_)
    case A(e1) if e1.f match
      case B(_) => 11
      case C => 12
    case B(e1) if e1.f match
      case C => 21
      case A(_) => 22
    case A(_) => 3 // nowarn: should not be reported as unreachable
    case A(_) => 3 // warn: unreacheable
    case C => 4

enum Color:
  case Red, Green, Blue

case class Wrapper(c: Color)

def wrappedColorName(w: Wrapper): String =
  w match // warn: match may not be exhaustive: It would fail on pattern case: Wrapper(Red)
    case Wrapper(c) if c match
      case Color.Green => "green"
      case Color.Blue => "blue"

enum AB:
  case A, B

def testBoundVarReachability(ab: AB) = ab match
  case x if x match
    case AB.A => "a"
    case AB.B => "b"
  case AB.A => "unreachable" // warn

def testParamIndexReachability(w: Wrapper) = w match
  case Wrapper(c) if c match
    case Color.Red   => "red"
    case Color.Green => "green"
  case Wrapper(Color.Red)  => "unreachable" // warn
  case Wrapper(Color.Blue) => "blue"        // not unreachable

def testCombinedReachability(w: Wrapper) = w match
  case Wrapper(c) if c match
    case Color.Red   => "red"
    case Color.Green => "green"
  case Wrapper(c) if c match
    case Color.Blue  => "blue"
  case Wrapper(_) => "unreachable" // warn
