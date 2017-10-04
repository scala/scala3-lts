package dotty.tools.dotc
package transform

import ast.Trees._, ast.tpd, core._
import Contexts.Context, Types._, Decorators._, Symbols._, DenotTransformers._
import SymDenotations._, Scopes._, StdNames._, NameOps._, Names._
import MegaPhase.MiniPhase

/** This phase synthesizes specialized methods for FunctionN, this is done
 *  since there are no scala signatures in the bytecode for the specialized
 *  methods.
 *
 *  We know which specializations exist for the different arities, therefore we
 *  can hardcode them. This should, however be removed once we're using a
 *  different standard library.
 */
class SpecializedApplyMethods extends MiniPhase with InfoTransformer {
  import ast.tpd._

  val phaseName = "specializedApplyMethods"

  private[this] var func0Applys: collection.Set[Symbol] = _
  private[this] var func1Applys: collection.Set[Symbol] = _
  private[this] var func2Applys: collection.Set[Symbol] = _
  private[this] var func0: Symbol = _
  private[this] var func1: Symbol = _
  private[this] var func2: Symbol = _

  private def init()(implicit ctx: Context): Unit = if (func0Applys eq null) {
    val definitions = ctx.definitions
    import definitions._

    def specApply(sym: Symbol, args: List[Type], ret: Type)(implicit ctx: Context): Symbol = {
      val name = nme.apply.specializedFunction(ret, args)
      ctx.newSymbol(sym, name, Flags.Method, MethodType(args, ret))
    }

    func0 = FunctionClass(0)
    func0Applys = for (r <- defn.Function0SpecializedReturns) yield specApply(func0, Nil, r)

    func1 = FunctionClass(1)
    func1Applys = for {
      r  <- defn.Function1SpecializedReturns
      t1 <- defn.Function1SpecializedParams
    } yield specApply(func1, List(t1), r)

    func2 = FunctionClass(2)
    func2Applys = for {
      r  <- Function2SpecializedReturns
      t1 <- Function2SpecializedParams
      t2 <- Function2SpecializedReturns
    } yield specApply(func2, List(t1, t2), r)
  }

  /** Add symbols for specialized methods to FunctionN */
  override def transformInfo(tp: Type, sym: Symbol)(implicit ctx: Context) = tp match {
    case tp: ClassInfo if defn.isPlainFunctionClass(sym) => {
      init()
      val newDecls = sym.name.functionArity match {
        case 0 => func0Applys.foldLeft(tp.decls.cloneScope) {
          (decls, sym) => decls.enter(sym); decls
        }
        case 1 => func1Applys.foldLeft(tp.decls.cloneScope) {
          (decls, sym) => decls.enter(sym); decls
        }
        case 2 => func2Applys.foldLeft(tp.decls.cloneScope) {
          (decls, sym) => decls.enter(sym); decls
        }
        case _ => tp.decls
      }

      tp.derivedClassInfo(decls = newDecls)
    }
    case _ => tp
  }

  /** Create bridge methods for FunctionN with specialized applys */
  override def transformTemplate(tree: Template)(implicit ctx: Context) = {
    val owner = tree.symbol.owner
    val additionalSymbols =
      if (owner eq func0) func0Applys
      else if (owner eq func1) func1Applys
      else if (owner eq func2) func2Applys
      else Nil

    if (additionalSymbols eq Nil) tree
    else cpy.Template(tree)(body = tree.body ++ additionalSymbols.map { apply =>
      DefDef(apply.asTerm, { vparamss =>
        This(owner.asClass)
          .select(nme.apply)
          .appliedToArgss(vparamss)
          .ensureConforms(apply.info.finalResultType)
      })
    })
  }
}
