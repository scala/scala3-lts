//> using options -Werror
import scala.language.experimental.subCases

case class NotFound(id: String)
enum AcceptError:
  case IsCancelled(id: String)
  case Denial
case object CatastrophicError
type Error = NotFound | AcceptError | CatastrophicError.type

import AcceptError.*

def errorToString: Error => String =
  case NotFound(id) => s"NotFound: $id"
  case CatastrophicError => s"It is all doom"
  case ae if ae match
    case Denial => s"In Denial"
    case IsCancelled(id) => s"IsCancelled: $id"

def errorToString2: Error => String =
  case NotFound(id) => s"NotFound: $id"
  case CatastrophicError => s"It is all doom"
  case ae if ae match
    case Denial => s"In Denial"
  case ea if ea match
    case IsCancelled(id) => s"IsCancelled: $id"

enum Color:
  case Red, Green, Blue

def colorName(c: Color): String = c match
  case c1 if c1 match
    case Color.Red => "red"
    case Color.Green => "green"
    case Color.Blue => "blue"

case class Wrapper(c: Color)

def wrappedColorName(w: Wrapper): String = w match
  case Wrapper(c) if c match
    case Color.Red => "red"
    case Color.Green => "green"
    case Color.Blue => "blue"

def wrappedColorName2(w: Wrapper): String = w match
  case Wrapper(c) if c match
    case Color.Red => "red"
    case Color.Green => "green"
  case Wrapper(c) if c match
    case Color.Blue => "blue"

def colorNameAlt(c: Color): String = c match
  case c1 if c1 match
    case Color.Red | Color.Green => "warm"
    case Color.Blue => "cool"

def wrappedColorBind(w: Wrapper): String = w match
  case x @ Wrapper(c) if c match
    case Color.Red => "red"
    case Color.Green => "green"
    case Color.Blue => "blue"

def nestedSubcases(c: Color): String = c match
  case c1 if c1 match
    case Color.Red => "red"
    case c2 if c2 match
      case Color.Green => "green"
      case Color.Blue => "blue"

enum Version:
  case Legacy
  case Stable(major: Int, minor: Int)

case class Document(title: String, version: Version)

def getVersion(d: Option[Document]): String = d match
  case Some(x) if x.version match
    case Version.Stable(m, n) => s"$m.$n"
    case Version.Legacy => "legacy"
  case None => "none"
