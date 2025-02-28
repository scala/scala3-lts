package scala.quoted
package runtime.impl

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Mode.GadtConstraintInference
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.util.optional

/** Matches a quoted tree against a quoted pattern tree.
 *  A quoted pattern tree may have type and term holes in addition to normal terms.
 *
 *
 *  Semantics:
 *
 *  We use `'{..}` for expression, `'[..]` for types and `⟨..⟩` for patterns nested in expressions.
 *  The semantics are defined as a list of reduction rules that are tried one by one until one matches.
 *
 *   Operations:
 *   - `s =?= p` checks if a scrutinee `s` matches the pattern `p` while accumulating extracted parts of the code.
 *   - `isClosedUnder(x1, .., xn)('{e})` returns true if and only if all the references in `e` to names defined in the pattern are contained in the set `{x1, ... xn}`.
 *   - `lift(x1, .., xn)('{e})` returns `(y1, ..., yn) => [xi = $yi]'{e}` where `yi` is an `Expr` of the type of `xi`.
 *   - `withEnv(x1 -> y1, ..., xn -> yn)(matching)` evaluates matching recording that `xi` is equivalent to `yi`.
 *   - `matched` denotes that the match succeeded and `matched('{e})` denotes that a match succeeded and extracts `'{e}`
 *   - `&&&` matches if both sides match. Concatenates the extracted expressions of both sides.
 *
 *   Note: that not all quoted terms bellow are valid expressions
 *
 *   ```scala
 *   /* Term hole */
 *   '{ e } =?= '{ hole[T] }  &&  typeOf('{e}) <:< T && isClosedUnder()('{e})  ===>   matched('{e})
 *
 *   /* Higher order term hole */
 *   '{ e } =?= '{ hole[(T1, ..., Tn) => T](x1, ..., xn) }  &&  isClosedUnder(x1, ... xn)('{e})  ===>   matched(lift(x1, ..., xn)('{e}))
 *
 *   /* Match literal */
 *   '{ lit } =?= '{ lit }   ===>   matched
 *
 *   /* Match type ascription (a) */
 *   '{ e: T } =?= '{ p }   ===>   '{e} =?= '{p}
 *
 *   /* Match type ascription (b) */
 *   '{ e } =?= '{ p: P }   ===>   '{e} =?= '{p}
 *
 *   /* Match selection */
 *   '{ e.x } =?= '{ p.x }   ===>   '{e} =?= '{p}
 *
 *   /* Match reference */
 *   '{ x } =?= '{ x }   ===>   matched
 *
 *   /* Match application */
 *   '{e0(e1, ..., en)} =?= '{p0(p1, ..., p2)}   ===>   '{e0} =?= '{p0} &&& '{e1} =?= '{p1} &&& ... %% '{en} =?= '{pn}
 *
 *   /* Match type application */
 *   '{e[T1, ..., Tn]} =?= '{p[P1, ..., Pn]}   ===>   '{e} =?= '{p} &&& '[T1] =?= '{P1} &&& ... %% '[Tn] =?= '[Pn]
 *
 *   /* Match block flattening */
 *   '{ {e0; e1; ...; en}; em } =?= '{ {p0; p1; ...; pm}; em }   ===>   '{ e0; {e1; ...; en; em} } =?= '{ p0; {p1; ...; pm; em} }
 *
 *   /* Match block */
 *   '{ e1; e2 } =?= '{ p1; p2 }   ===>   '{e1} =?= '{p1} &&& '{e2} =?= '{p2}
 *
 *   /* Match def block */
 *   '{ e1; e2 } =?= '{ p1; p2 }   ===>   withEnv(symOf(e1) -> symOf(p1))('{e1} =?= '{p1} &&& '{e2} =?= '{p2})
 *
 *   /* Match if */
 *   '{ if e0 then e1 else e2 } =?= '{ if p0 then p1 else p2 }   ===>  '{e0} =?= '{p0} &&& '{e1} =?= '{p1} &&& '{e2} =?= '{p2}
 *
 *   /* Match while */
 *   '{ while e0 do e1 } =?= '{ while p0 do p1 }   ===>  '{e0} =?= '{p0} &&& '{e1} =?= '{p1}
 *
 *   /* Match assign */
 *   '{ e0 = e1 } =?= '{ p0 = p1 }   ==>   '{e0} =?= '{p0} &&& '{e1} =?= '{p1}
 *
 *   /* Match new */
 *   '{ new T } =?= '{ new T }   ===>   matched
 *
 *   /* Match this */
 *   '{ C.this } =?= '{ C.this }   ===>   matched
 *
 *   /* Match super */
 *   '{ e.super } =?= '{ p.super }   ===>   '{e} =?= '{p}
 *
 *   /* Match varargs */
 *   '{ e: _* } =?= '{ p: _* }   ===>   '{e} =?= '{p}
 *
 *   /* Match val */
 *   '{ val x: T = e1; e2 } =?= '{ val y: P = p1; p2 }   ===>   withEnv(x -> y)('[T] =?= '[P] &&& '{e1} =?= '{p1} &&& '{e2} =?= '{p2})
 *
 *   /* Match def */
 *   '{ def x0(x1: T1, ..., xn: Tn): T0 = e1; e2 } =?= '{ def y0(y1: P1, ..., yn: Pn): P0 = p1; p2 }   ===>   withEnv(x0 -> y0, ..., xn -> yn)('[T0] =?= '[P0] &&& ... &&& '[Tn] =?= '[Pn] &&& '{e1} =?= '{p1} &&& '{e2} =?= '{p2})
 *
 *   // Types
 *
 *   /* Match type */
 *   '[T] =?= '[P] && T <:< P   ===>   matched
 *
 *   ```
 */
class QuoteMatcher(debug: Boolean) {
  import tpd.*

  /** Sequence of matched expressions.
   *  These expressions are part of the scrutinee and will be bound to the quote pattern term splices.
   */
  type MatchingExprs = Seq[MatchResult]

  /** A map relating equivalent symbols from the scrutinee and the pattern
    *  For example in
    *  ```
    *  '{val a = 4; a * a} match case '{ val x = 4; x * x }
    *  ```
    *  when matching `a * a` with `x * x` the environment will contain `Map(a -> x)`.
    */
  private type Env = Map[Symbol, Symbol]

  private def withEnv[T](env: Env)(body: Env ?=> T): T = body(using env)

  /** Evaluate the result of pattern matching against a quote pattern.
   *  Implementation of the runtime of `QuoteMatching.{ExprMatch, TypeMatch}.unapply`.
   */
  def treeMatch(scrutinee: Tree, pattern: Tree)(using Context): Option[Tuple] = {
    val (pat1, typeHoles, ctx1) = instrumentTypeHoles(pattern)
    inContext(ctx1) {
      optional {
        given Env = Map.empty
        scrutinee =?= pat1
      }.map { matchings =>
        lazy val spliceScope = SpliceScope.getCurrent
        // After matching and doing all subtype checks, we have to approximate all the type bindings
        // that we have found, seal them in a quoted.Type and add them to the result
        val typeHoleApproximations = typeHoles.map(typeHoleApproximation)
        val matchedTypes = typeHoleApproximations.map { tpe =>
          new TypeImpl(TypeTree(tpe).withSpan(scrutinee.span), spliceScope)
        }
        val matchedExprs =
          val typeHoleMap: Type => Type =
            if typeHoles.isEmpty then identity
            else new TypeMap {
              private val typeHoleMapping = Map(typeHoles.zip(typeHoleApproximations)*)
              def apply(tp: Type): Type = tp match
                case TypeRef(NoPrefix, _) => typeHoleMapping.getOrElse(tp.typeSymbol, tp)
                case _ => mapOver(tp)
            }
          if matchings.isEmpty then Nil
          else matchings.map(_.toExpr(typeHoleMap, spliceScope))
        val results = matchedTypes ++ matchedExprs
        Tuple.fromIArray(IArray.unsafeFromArray(results.toArray))
      }
    }
  }

  def instrumentTypeHoles(pat: Tree)(using Context): (Tree, List[Symbol], Context) =
    def isTypeHoleDef(tree: Tree): Boolean = tree match
      case tree: TypeDef => tree.symbol.hasAnnotation(defn.QuotedRuntimePatterns_patternTypeAnnot)
      case _ => false
    pat match
      case tpd.Inlined(_, Nil, pat2) => instrumentTypeHoles(pat2)
      case tpd.Block(stats @ ((typeHole: TypeDef) :: _), expr) if isTypeHoleDef(typeHole) =>
        val (holeDefs, otherStats) = stats.span(isTypeHoleDef)
        val holeSyms = holeDefs.map(_.symbol)
        val ctx1 = ctx.fresh.setFreshGADTBounds.addMode(GadtConstraintInference)
        ctx1.gadtState.addToConstraint(holeSyms)
        (tpd.cpy.Block(pat)(otherStats, expr), holeSyms, ctx1)
      case _ =>
        (pat, Nil, ctx)

  /** Type approximation of a quote pattern type variable.
   *  Should only be approximated after matching the tree.
   */
  def typeHoleApproximation(sym: Symbol)(using Context): Type =
    val fromAboveAnnot = sym.hasAnnotation(defn.QuotedRuntimePatterns_fromAboveAnnot)
    val fullBounds = ctx.gadt.fullBounds(sym)
    if fromAboveAnnot then fullBounds.nn.hi else fullBounds.nn.lo

  /** Check that all trees match with `mtch` and concatenate the results with &&& */
  private def matchLists[T](l1: List[T], l2: List[T])(mtch: (T, T) => MatchingExprs): optional[MatchingExprs] = (l1, l2) match {
    case (x :: xs, y :: ys) => mtch(x, y) &&& matchLists(xs, ys)(mtch)
    case (Nil, Nil) => matched
    case _ => notMatched
  }

  extension (scrutinees: List[Tree])
    private def =?= (patterns: List[Tree])(using Env, Context): optional[MatchingExprs] =
      matchLists(scrutinees, patterns)(_ =?= _)

  extension (scrutinee0: Tree)

    /** Check that the trees match and return the contents from the pattern holes.
      *  Return a sequence containing all the contents in the holes.
      *  If it does not match, continues to the `optional` with `None`.
      *
      *  @param scrutinee The tree being matched
      *  @param pattern The pattern tree that the scrutinee should match. Contains `patternHole` holes.
      *  @param `summon[Env]` Set of tuples containing pairs of symbols (s, p) where s defines a symbol in `scrutinee` which corresponds to symbol p in `pattern`.
      *  @return The sequence with the contents of the holes of the matched expression.
      */
    private def =?= (pattern0: Tree)(using Env, Context): optional[MatchingExprs] =

      /* Match block flattening */ // TODO move to cases
      /** Normalize the tree */
      def normalize(tree: Tree): Tree = tree match {
        case Block(Nil, expr) => normalize(expr)
        case Block(stats1, Block(stats2, expr)) =>
          expr match
            case _: Closure => tree
            case _ => normalize(Block(stats1 ::: stats2, expr))
        case Inlined(_, Nil, expr) => normalize(expr)
        case _ => tree
      }

      val scrutinee = normalize(scrutinee0)
      val pattern = normalize(pattern0)

      /** Check that both are `val` or both are `lazy val` or both are `var` **/
      def checkValFlags(): Boolean = {
        val sFlags = scrutinee.symbol.flags
        val pFlags = pattern.symbol.flags
        sFlags.is(Lazy) == pFlags.is(Lazy) && sFlags.is(Mutable) == pFlags.is(Mutable)
      }

      // TODO remove
      object TypeTreeTypeTest:
        def unapply(x: Tree): Option[Tree & x.type] = x match
          case x: (TypeBoundsTree & x.type) => None
          case x: (Tree & x.type) if x.isType => Some(x)
          case _ => None
      end TypeTreeTypeTest

      def runMatch(): optional[MatchingExprs] = pattern match

        /* Term hole */
        // Match a scala.internal.Quoted.patternHole typed as a repeated argument and return the scrutinee tree
        case Typed(TypeApply(patternHole, tpt :: Nil), tpt2)
            if patternHole.symbol.eq(defn.QuotedRuntimePatterns_patternHole) &&
               tpt2.tpe.derivesFrom(defn.RepeatedParamClass) =>
          scrutinee match
            case Typed(s, tpt1) if s.tpe <:< tpt.tpe => matched(scrutinee)
            case _ => notMatched

        /* Term hole */
        // Match a scala.internal.Quoted.patternHole and return the scrutinee tree
        case TypeApply(patternHole, tpt :: Nil)
            if patternHole.symbol.eq(defn.QuotedRuntimePatterns_patternHole) &&
                scrutinee.tpe <:< tpt.tpe =>
          scrutinee match
            case ClosedPatternTerm(scrutinee) => matched(scrutinee)
            case _ => notMatched


        /* Higher order term hole */
        // Matches an open term and wraps it into a lambda that provides the free variables
        case Apply(TypeApply(Ident(_), List(TypeTree())), SeqLiteral(args, _) :: Nil)
            if pattern.symbol.eq(defn.QuotedRuntimePatterns_higherOrderHole) =>
          val env = summon[Env]
          val capturedArgs = args.map(_.symbol)
          val captureEnv = env.filter((k, v) => !capturedArgs.contains(v))
          withEnv(captureEnv) {
            scrutinee match
              case ClosedPatternTerm(scrutinee) => matchedOpen(scrutinee, pattern.tpe, args, env)
              case _ => notMatched
          }

        /* Match type ascription (b) */
        case Typed(expr2, _) =>
          scrutinee =?= expr2

        case _ =>
          scrutinee match
            /* Match type ascription (a) */
            case Typed(expr1, _) =>
              expr1 =?= pattern

            /* Match literal */
            case Literal(constant1) =>
              pattern match
                case Literal(constant2) if constant1 == constant2 => matched
                case _ => notMatched

            case ref: RefTree =>
              pattern match
                /* Match selection */
                case Select(qual2, _) if symbolMatch(scrutinee, pattern) =>
                  ref match
                    case Select(qual1, _) => qual1 =?= qual2
                    case ref: Ident =>
                      if qual2.existsSubTree(_.symbol == defn.QuotedRuntimePatterns_patternHole) then
                        // Prefix has a hole, so we need to match the prefix to extract the value of the hole
                        tpd.desugarIdentPrefix(ref) =?= qual2
                      else
                        matched

                /* Match reference */
                case _: Ident if symbolMatch(scrutinee, pattern) => matched
                /* Match type */
                case TypeTreeTypeTest(pattern) if scrutinee.tpe <:< pattern.tpe => matched
                case _ => notMatched

            /* Match application */
            case Apply(fn1, args1) =>
              pattern match
                case Apply(fn2, args2) =>
                  fn1 =?= fn2 &&& args1 =?= args2
                case _ => notMatched

            /* Match type application */
            case TypeApply(fn1, args1) =>
              pattern match
                case TypeApply(fn2, args2) =>
                  fn1 =?= fn2 &&& args1 =?= args2
                case _ => notMatched

            /* Match block */
            case Block(stat1 :: stats1, expr1) =>
              pattern match
                case Block(stat2 :: stats2, expr2) =>
                  val newEnv = (stat1, stat2) match {
                    case (stat1: MemberDef, stat2: MemberDef) =>
                      summon[Env] + (stat1.symbol -> stat2.symbol)
                    case _ =>
                      summon[Env]
                  }
                  withEnv(newEnv) {
                    stat1 =?= stat2 &&& Block(stats1, expr1) =?= Block(stats2, expr2)
                  }
                case _ => notMatched

            /* Match if */
            case If(cond1, thenp1, elsep1) =>
              pattern match
                case If(cond2, thenp2, elsep2) =>
                  cond1 =?= cond2 &&& thenp1 =?= thenp2 &&& elsep1 =?= elsep2
                case _ => notMatched

            /* Match while */
            case WhileDo(cond1, body1) =>
              pattern match
                case WhileDo(cond2, body2) => cond1 =?= cond2 &&& body1 =?= body2
                case _ => notMatched

            /* Match assign */
            case Assign(lhs1, rhs1) =>
              pattern match
                case Assign(lhs2, rhs2) => lhs1 =?= lhs2 &&& rhs1 =?= rhs2
                case _ => notMatched

            /* Match new */
            case New(tpt1) =>
              pattern match
                case New(tpt2) if tpt1.tpe.dealias.typeSymbol == tpt2.tpe.dealias.typeSymbol => matched
                case _ => notMatched

            /* Match this */
            case This(_) =>
              pattern match
                case This(_) if scrutinee.symbol == pattern.symbol => matched
                case _ => notMatched

            /* Match super */
            case Super(qual1, mix1) =>
              pattern match
                case Super(qual2, mix2) if mix1 == mix2 => qual1 =?= qual2
                case _ => notMatched

            /* Match varargs */
            case SeqLiteral(elems1, _) =>
              pattern match
                case SeqLiteral(elems2, _) if elems1.size == elems2.size => elems1 =?= elems2
                case _ => notMatched

            /* Match type */
            // TODO remove this?
            case TypeTreeTypeTest(scrutinee) =>
              pattern match
                case TypeTreeTypeTest(pattern) if scrutinee.tpe <:< pattern.tpe => matched
                case _ => notMatched

            /* Match val */
            case scrutinee @ ValDef(_, tpt1, _) =>
              pattern match
                case pattern @ ValDef(_, tpt2, _) if checkValFlags() =>
                  def rhsEnv = summon[Env] + (scrutinee.symbol -> pattern.symbol)
                  tpt1 =?= tpt2 &&& withEnv(rhsEnv)(scrutinee.rhs =?= pattern.rhs)
                case _ => notMatched

            /* Match def */
            case scrutinee @ DefDef(_, paramss1, tpt1, _) =>
              pattern match
                case pattern @ DefDef(_, paramss2, tpt2, _) =>
                  def rhsEnv: Env =
                    val paramSyms: List[(Symbol, Symbol)] =
                      for
                        (clause1, clause2) <- paramss1.zip(paramss2)
                        (param1, param2) <- clause1.zip(clause2)
                      yield
                        param1.symbol -> param2.symbol
                    val oldEnv: Env = summon[Env]
                    val newEnv: List[(Symbol, Symbol)] = (scrutinee.symbol -> pattern.symbol) :: paramSyms
                    oldEnv ++ newEnv
                  matchLists(paramss1, paramss2)(_ =?= _)
                    &&& tpt1 =?= tpt2
                    &&& withEnv(rhsEnv)(scrutinee.rhs =?= pattern.rhs)
                case _ => notMatched

            case Closure(_, _, tpt1) =>
              pattern match
                case Closure(_, _, tpt2) => matched // TODO match tpt1 with tpt2?
                case _ => notMatched

            case NamedArg(name1, arg1) =>
              pattern match
                case NamedArg(name2, arg2) if name1 == name2 => arg1 =?= arg2
                case _ => notMatched

            case EmptyTree =>
              if pattern.isEmpty then matched
              else notMatched

            // No Match
            case _ =>
              notMatched
      end runMatch

      if debug then
        try {
          runMatch()
        } catch {
          case e: util.boundary.Break[?] =>
            val quotes = QuotesImpl()
            println(
              s""">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
                  |Scrutinee
                  |  ${scrutinee.show}
                  |did not match pattern
                  |  ${pattern.show}
                  |
                  |with environment: ${summon[Env]}
                  |
                  |Scrutinee: ${quotes.reflect.Printer.TreeStructure.show(scrutinee.asInstanceOf)}
                  |Pattern: ${quotes.reflect.Printer.TreeStructure.show(pattern.asInstanceOf)}
                  |
                  |""".stripMargin)
            throw e
        }
      else
        runMatch()

    end =?=

  end extension

  /** Does the scrutinee symbol match the pattern symbol? It matches if:
    *   - They are the same symbol
    *   - The scrutinee has is in the environment and they are equivalent
    *   - The scrutinee overrides the symbol of the pattern
    */
  private def symbolMatch(scrutineeTree: Tree, patternTree: Tree)(using Env, Context): Boolean =
    val scrutinee = scrutineeTree.symbol

    def overridingSymbol(ofclazz: Symbol): Symbol =
      if ofclazz.isClass then scrutinee.denot.overridingSymbol(ofclazz.asClass)
      else NoSymbol

    val devirtualizedScrutinee = scrutineeTree match
      case Select(qual, _) =>
        val sym = overridingSymbol(qual.tpe.typeSymbol)
        if sym.exists then sym
        else scrutinee
      case _ => scrutinee
    val pattern = patternTree.symbol

    devirtualizedScrutinee == pattern
    || summon[Env].get(devirtualizedScrutinee).contains(pattern)
    || devirtualizedScrutinee.allOverriddenSymbols.contains(pattern)

  private object ClosedPatternTerm {
    /** Matches a term that does not contain free variables defined in the pattern (i.e. not defined in `Env`) */
    def unapply(term: Tree)(using Env, Context): Option[term.type] =
      if freePatternVars(term).isEmpty then Some(term) else None

    /** Return all free variables of the term defined in the pattern (i.e. defined in `Env`) */
    def freePatternVars(term: Tree)(using Env, Context): Set[Symbol] =
      val accumulator = new TreeAccumulator[Set[Symbol]] {
        def apply(x: Set[Symbol], tree: Tree)(using Context): Set[Symbol] =
          tree match
            case tree: Ident if summon[Env].contains(tree.symbol) => foldOver(x + tree.symbol, tree)
            case _ => foldOver(x, tree)
      }
      accumulator.apply(Set.empty, term)
  }

  enum MatchResult:
    /** Closed pattern extracted value
     *  @param tree Scrutinee sub-tree that matched
     */
    case ClosedTree(tree: Tree)
    /** HOAS pattern extracted value
     *
     *  @param tree Scrutinee sub-tree that matched
     *  @param patternTpe Type of the pattern hole (from the pattern)
     *  @param args HOAS arguments (from the pattern)
     *  @param env Mapping between scrutinee and pattern variables
     */
    case OpenTree(tree: Tree, patternTpe: Type, args: List[Tree], env: Env)

    /** Return the expression that was extracted from a hole.
     *
     *  If it was a closed expression it returns that expression. Otherwise,
     *  if it is a HOAS pattern, the surrounding lambda is generated using
     *  `mapTypeHoles` to create the signature of the lambda.
     *
     *  This expression is assumed to be a valid expression in the given splice scope.
     */
    def toExpr(mapTypeHoles: Type => Type, spliceScope: Scope)(using Context): Expr[Any] = this match
      case MatchResult.ClosedTree(tree) =>
        new ExprImpl(tree, spliceScope)
      case MatchResult.OpenTree(tree, patternTpe, args, env) =>
        val names: List[TermName] = args.map {
          case Block(List(DefDef(nme.ANON_FUN, _, _, Apply(Ident(name), _))), _) => name.asTermName
          case arg => arg.symbol.name.asTermName
        }
        val paramTypes = args.map(x => mapTypeHoles(x.tpe.widenTermRefExpr))
        val methTpe = MethodType(names)(_ => paramTypes, _ => mapTypeHoles(patternTpe))
        val meth = newAnonFun(ctx.owner, methTpe)
        def bodyFn(lambdaArgss: List[List[Tree]]): Tree = {
          val argsMap = args.view.map(_.symbol).zip(lambdaArgss.head).toMap
          val body = new TreeMap {
            override def transform(tree: Tree)(using Context): Tree =
              tree match
                case tree: Ident => env.get(tree.symbol).flatMap(argsMap.get).getOrElse(tree)
                case tree => super.transform(tree)
          }.transform(tree)
          TreeOps(body).changeNonLocalOwners(meth)
        }
        val hoasClosure = Closure(meth, bodyFn)
        new ExprImpl(hoasClosure, spliceScope)

  private inline def notMatched: optional[MatchingExprs] =
    optional.break()

  private inline def matched: MatchingExprs =
    Seq.empty

  private inline def matched(tree: Tree)(using Context): MatchingExprs =
    Seq(MatchResult.ClosedTree(tree))

  private def matchedOpen(tree: Tree, patternTpe: Type, args: List[Tree], env: Env)(using Context): MatchingExprs =
    Seq(MatchResult.OpenTree(tree, patternTpe, args, env))

  extension (self: MatchingExprs)
      /** Concatenates the contents of two successful matchings */
      def &&& (that: MatchingExprs): MatchingExprs = self ++ that
  end extension

}
