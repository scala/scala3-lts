import scala.quoted.*

object Macro:
  inline def make[T]: T = ${ makeImpl[T] }

  def makeImpl[T: Type](using Quotes): Expr[T] =
    import quotes.reflect.*
    val sym = TypeRepr.of[T].typeSymbol.children.head // enum case `Green`
    // `sym.termRef` has a `ThisType` prefix while `Ref(sym).tpe` has a path prefix.
    // The lub of the two must still conform to either of them.
    sym.termRef.asType match
      case '[t] =>
        Typed(
          If(Literal(BooleanConstant(true)), Ref(sym), '{ null.asInstanceOf[t] }.asTerm),
          TypeTree.of[t]
        ).asExprOf[T]
