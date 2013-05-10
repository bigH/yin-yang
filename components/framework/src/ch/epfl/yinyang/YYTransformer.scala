package ch.epfl
package yinyang

import ch.epfl.yinyang.api._
import scala.collection.mutable
import mutable.{ ListBuffer, HashMap }
import scala.collection.immutable.Map
import scala.reflect.macros.Context
import language.experimental.macros
import java.util.concurrent.atomic.AtomicLong
import yinyang.typetransformers.TypeTransformer

class YYConfig(val t: Map[String, Any]) {
  val shallow: Boolean = t("shallow").asInstanceOf[Boolean]
  val debug: Boolean = t("debug").asInstanceOf[Boolean]
  val mainMethod: String = t("mainMethod").asInstanceOf[String]
}

object YYTransformer {
  val defaults = Map[String, Any](
    ("shallow" -> true),
    ("debug" -> false),
    ("mainMethod" -> "main"))

  // TODO configurable rewireing for objects
  def apply[C <: Context, T](c: C)(
    dslName: String,
    tpeTransformer: TypeTransformer[c.type],
    config: Map[String, Any] = Map()) =
    new YYTransformer[c.type, T](c, dslName, new YYConfig(config withDefault (defaults))) {
      val typeTransformer = tpeTransformer
      typeTransformer.className = className
    }

  protected[yinyang] val uID = new AtomicLong(0)
}

// for now configuration goes as a named parameter list
abstract class YYTransformer[C <: Context, T](val c: C,
                                              dslName: String,
                                              config: YYConfig) {
  import c.universe._
  import config._
  val typeTransformer: TypeTransformer[c.type]
  import typeTransformer._

  /*
  * Configuration parameters.
  */
  def interpretMethod = "interpret"
  val holeMethod = "hole"
  val className =
    s"generated$$${dslName.filter(_ != '.') + YYTransformer.uID.incrementAndGet}"
  val dslType = c.mirror.staticClass(dslName).toType

  val symbolIds: mutable.HashMap[Int, Symbol] = new mutable.HashMap()
  def symbolById(id: Int) = symbolIds(id)

  /**
   * Main YinYang method. Transforms the body of the DSL, makes the DSL cake out
   * of the body and then executes the DSL code. If the DSL supports static
   * analysis of the DSL code this method will perform it during compilation.
   * The errors will be reported to the compiler error console.
   *
   * Depending on the configuration and the analysis of static values this DSL
   * will be compiled either at compile time, if all required values are present,
   * or at runtime.
   */
  def apply[T](block: c.Expr[T]): c.Expr[T] = {
    log("Body: " + showRaw(block.tree))
    // shallow or detect a non-existing feature => return the original block.
    if (!FeatureAnalyzer(block.tree) || shallow)
      block
    else {
      // mark captured variables as holes
      val allCaptured = freeVariables(block.tree)
      def transform(holes: List[Int], idents: List[Symbol])(block: Tree): Tree =
        (AscriptionTransformer andThen
          LiftLiteralTransformer(idents) andThen
          ScopeInjectionTransformer andThen
          HoleTransformer(holes) andThen
          composeDSL)(block)

      val dslPre = transform(allCaptured map symbolId, Nil)(block.tree)

      // if the DSL inherits the StaticallyChecked trait reflectively do the static analysis
      if (dslType <:< typeOf[StaticallyChecked])
        reflInstance[StaticallyChecked](dslPre).staticallyCheck(new Reporter(c))

      // DSL returns what holes it needs
      val reqVars = dslType match {
        case tpe if tpe <:< typeOf[FullyStaged] =>
          allCaptured
        case tpe =>
          reflInstance[BaseYinYang](dslPre).requiredHoles.map(symbolById)
      }

      val holes = allCaptured diff reqVars

      // re-transform the tree with new holes if there are required vars
      val dsl = if (reqVars.isEmpty) dslPre
      else transform(holes map symbolId, reqVars)(block.tree)

      def args(holes: List[Symbol]): String =
        holes.map({ y: Symbol => y.name.decoded }).mkString("", ",", "")

      val dslTree = dslType match {
        case tpe if tpe <:< typeOf[CodeGenerator] && reqVars.isEmpty =>
          /*
           * If DSL does not require run-time data it can be completely
           * generated at compile time and wired for execution.
           */
          c parse s"""
            ${reflInstance[CodeGenerator](dsl) generateCode className}
            new $className().apply(${args(allCaptured)})
          """
        case _ =>
          /*
           * If DSL need run-time info send it to run-time and install recompilation guard.
           */
          val programId = new scala.util.Random().nextLong
          val retType = block.tree.tpe.toString
          val functionType = s"""${(0 until args(holes).length).map(y => "scala.Any").mkString("(", ", ", ")")} => ${retType}"""
          val refs = reqVars filterNot (isPrimitive)
          val dslInit = s"""
            val dslInstance = ch.epfl.yinyang.runtime.YYStorage.lookup(${programId}L, new $className())
            val values: Seq[Any] = Seq(${(reqVars diff refs) map (_.name.decoded) mkString ", "})
            val refs: Seq[Any] = Seq(${refs map (_.name.decoded) mkString ", "})
          """

          val guardedExecute = c parse dslInit + (dslType match {
            case t if t <:< typeOf[CodeGenerator] => s"""
              def recompile(): () => Any = dslInstance.compile[$retType, $functionType]
              val program = ch.epfl.yinyang.runtime.YYStorage.$guardType[$functionType](
                ${programId}L, values, refs, recompile
              )
              program.apply(${args(holes)})
            """
            case t if t <:< typeOf[Interpreted] => s"""
              def invalidate(): () => Any = () => dslInstance.reset
              ch.epfl.yinyang.runtime.YYStorage.$guardType[Any](
                ${programId}L, values, refs, invalidate
              )
              dslInstance.interpret[${retType}](${args(holes)})
            """
          })

          Block(dsl, guardedExecute)
      }

      log(s"""Final untyped: ${show(c.resetAllAttrs(dslTree))}
        Final typed: ${show(c.typeCheck(c.resetAllAttrs(dslTree)))}""")
      c.Expr[T](c.resetAllAttrs(dslTree))
    }
  }

  def freeVariables(tree: Tree): List[Symbol] =
    new FreeVariableCollector().collect(tree)

  object FeatureAnalyzer extends ((Tree, Seq[DSLFeature]) => Boolean) {
    def apply(tree: Tree, lifted: Seq[DSLFeature] = Seq()): Boolean = {
      val (virtualized, lifted) = VirtualizationTransformer(tree)
      val st = System.currentTimeMillis()
      val res = new FeatureAnalyzer(lifted).analyze(virtualized)
      log(s"Feature checking time: ${(System.currentTimeMillis() - st)}")
      res
    }

  }

  private final class FeatureAnalyzer(val lifted: Seq[DSLFeature]) extends Traverser {

    var methods = mutable.LinkedHashSet[DSLFeature]()

    var parameterLists: List[List[Type]] = Nil

    def addIfNotLifted(m: DSLFeature) =
      if (!lifted.exists(x => x.name == m.name)) {
        log(s"adding ${m}")
        methods += m
      }

    def getObjType(obj: Tree): Type = obj.tpe

    override def traverse(tree: Tree) = tree match {
      case Apply(ap @ Apply(_, _), args) =>
        val argTypes = args.map(_.tpe)
        parameterLists = argTypes :: parameterLists
        traverse(ap)
        parameterLists = Nil
        for (x <- args) traverse(x)
      case Apply(Select(obj, name), args) =>
        parameterLists = args.map(_.tpe) :: parameterLists
        addIfNotLifted(DSLFeature(Some(getObjType(obj)), name.toString, Nil, parameterLists))
        parameterLists = Nil
        for (x <- args) traverse(x)
      case Apply(TypeApply(Select(obj, name), targs), args) =>
        parameterLists = args.map(_.tpe) :: parameterLists
        addIfNotLifted(DSLFeature(Some(getObjType(obj)), name.toString, targs, parameterLists))
        parameterLists = Nil
        for (x <- args) traverse(x)
      case tr @ Select(obj, name) =>
        log(s"Select obj.tpe = ${obj.tpe}")
        addIfNotLifted(DSLFeature(Some(getObjType(obj)), name.toString, Nil, Nil))
      case tr =>
        log(s"Not Checking: ${showRaw(tr)}")
        super.traverse(tree)
    }

    def analyze(tree: Tree): Boolean = {
      traverse(tree)
      log(s"To analyze: " + (methods ++ lifted))
      //Finds the first element of the sequence satisfying a predicate, if any.
      (methods ++ lifted).toSeq.find(!methodsExist(_)) match {
        case Some(methodError) if lifted.contains(methodError) =>
          c.error(tree.pos, s"Language feature not supported.")
          false
        case Some(methodError) =>
          // missing method
          c.error(tree.pos, s"Method $methodError not found.")
          false
        case None =>
          true
      }
    }

  }

  case class DSLFeature(
    tpe: Option[Type], name: String, targs: List[Tree], args: List[List[Type]])

  def methodsExist(methods: DSLFeature*): Boolean = {
    val methodSet = methods.toSet
    def application(meth: DSLFeature): Tree = {

      def dummyTree(tpe: Type) = tpe match {
        case typeTag @ ThisType(_) => This(newTypeName(className))
        case _ =>
          log(s"tpe = $tpe typeTree = ${constructTypeTree(typeTransformer.OtherCtx, tpe)}")
          TypeApply(Select(Literal(Constant(())),
            newTermName("asInstanceOf")),
            List(constructTypeTree(typeTransformer.OtherCtx, tpe)))
      }

      def app(tree: Tree) = meth.targs match {
        case Nil => tree
        case tparams: List[Tree] =>
          TypeApply(tree, meth.targs map { x => constructTypeTree(typeTransformer.TypeApplyCtx, x.tpe) })
      }
      log(s"Args ${meth.args}")
      val res = meth.tpe.map(dummyTree(_)).orElse(Some(This(className)))
        .map(x => app(Select(x, newTermName(meth.name))))
        .map(f => meth.args.foldLeft(f)((x, y) => Apply(x, y.map(dummyTree)))).get
      log(s"${showRaw(res)}")
      res
    }

    val res = try {
      // block containing only dummy methods that were applied.
      val block = Block(composeDSL(Block(
        methodSet.map(x => application(x)).toSeq: _*)), Literal(Constant(())))
      log(s"Block: ${show(block)})")
      log(s"Block raw: ${showRaw(block)})")
      c.typeCheck(block)
      true
    } catch {
      case e: Throwable =>
        log("Feature not working!!!" + e)
        false
    }

    res
  }

  private final class FreeVariableCollector extends Traverser {

    private[this] val collected = ListBuffer[Symbol]()
    private[this] var defined = List[Symbol]()

    private[this] final def isFree(id: Symbol) = !defined.contains(id)

    override def traverse(tree: Tree) = tree match {
      case i @ Ident(s) => {
        val sym = i.symbol
        //store info about idents
        symbolIds.put(symbolId(sym), sym)
        if (sym.isTerm &&
          !(sym.isMethod || sym.isPackage || sym.isModule) &&
          isFree(sym)) collected append sym
      }
      case _ => super.traverse(tree)
    }

    def collect(tree: Tree): List[Symbol] = {
      collected.clear()
      defined = new LocalDefCollector().definedSymbols(tree)
      log(s"Defined: $defined")
      traverse(tree)
      collected.toList.distinct
    }

  }

  private final class LocalDefCollector extends Traverser {

    private[this] val definedValues, definedMethods = ListBuffer[Symbol]()

    override def traverse(tree: Tree) = tree match {
      case vd @ ValDef(mods, name, tpt, rhs) =>
        definedValues += vd.symbol
        traverse(rhs)
      case dd @ DefDef(mods, name, tparams, vparamss, tpt, rhs) =>
        definedMethods += dd.symbol
        vparamss.flatten.foreach(traverse)
        traverse(rhs)
      case _ =>
        super.traverse(tree)
    }

    def definedSymbols(tree: Tree): List[Symbol] = {
      definedValues.clear()
      definedMethods.clear()
      traverse(tree)
      (definedValues ++ definedMethods).toList
    }

  }

  def constructor(classname: String, arguments: List[Tree]): Tree =
    invoke(newClass(classname), nme.CONSTRUCTOR, arguments)

  def copy(orig: Tree)(nev: Tree): Tree = {
    nev.setSymbol(orig.symbol)
    nev.setPos(orig.pos)
    nev
  }

  object AscriptionTransformer extends (Tree => Tree) {
    def apply(tree: Tree) = new AscriptionTransformer().transform(tree)
  }

  private final class AscriptionTransformer extends Transformer {
    var ident = 0
    var externalApplyFound = false

    override def transform(tree: Tree): Tree = {
      log(" " * ident + " ==> " + tree)
      ident += 1

      val result = tree match {
        case vd @ ValDef(m, n, t, rhs) =>
          copy(vd)(ValDef(m, n, t, Typed(transform(rhs), TypeTree(t.tpe))))

        case dd @ DefDef(m, n, tp, p, rt, rhs) =>
          copy(dd)(DefDef(m, n, tp, p, rt, Typed(transform(rhs), TypeTree(rt.tpe))))

        case ap @ Apply(fun, args) =>
          val ascrArgs = args map {
            x => // TODO cleanup. This can be done easier.
              val auniverse = c.universe.asInstanceOf[scala.reflect.internal.Types]
              log(s"isConstantType(x.tpe) = " +
                auniverse.isConstantType(tree.tpe.asInstanceOf[auniverse.Type]))
              Typed(transform(x), TypeTree(
                if (x.tpe != null &&
                  auniverse.isConstantType(x.tpe.asInstanceOf[auniverse.Type]))
                  x.tpe.erasure
                else
                  x.tpe))
          }
          if (externalApplyFound) {
            Apply(transform(fun), ascrArgs)
          } else {
            externalApplyFound = true
            val baseTree = Apply(transform(fun), ascrArgs)
            externalApplyFound = false
            Typed(baseTree, TypeTree(ap.tpe))
          }
        case _ =>
          super.transform(tree)
      }

      ident -= 1
      log(" " * ident + " <== " + result)

      result
    }
  }

  private object LiftLiteralTransformer {
    def apply(idents: List[Symbol] = Nil)(tree: Tree) =
      new LiftLiteralTransformer(idents).transform(tree)
  }

  private final class LiftLiteralTransformer(val idents: List[Symbol])
    extends Transformer {

    def lift(t: Tree) =
      Apply(Select(This(newTypeName(className)), newTermName("lift")), List(t))

    override def transform(tree: Tree): Tree = {
      tree match {
        case t @ Literal(Constant(_)) =>
          lift(t)
        case t @ Ident(_) if idents.contains(t.symbol) =>
          lift(t)
        case _ =>
          super.transform(tree)
      }
    }
  }

  private object VirtualizationTransformer {
    def apply(tree: Tree) = new VirtualizationTransformer()(tree)
  }

  private final class VirtualizationTransformer extends Transformer {

    val lifted = mutable.ArrayBuffer[DSLFeature]()
    override def transform(tree: Tree): Tree = {
      tree match {
        case t @ If(cond, then, elze) =>
          lifted += DSLFeature(None, "__ifThenElse", Nil, List(List(cond.tpe, then.tpe, elze.tpe)))
          method("__ifThenElse", List(transform(cond), transform(then), transform(elze)))

        case ValDef(mods, sym, tpt, rhs) if mods.hasFlag(Flag.MUTABLE) =>
          lifted += DSLFeature(None, "__newVar", Nil, List(List(rhs.tpe)))
          ValDef(mods, sym, transform(tpt), method("__newVar", List(transform(rhs))))

        case Return(e) =>
          lifted += DSLFeature(None, "__return", Nil, List(List(e.tpe)))
          method("__return", List(transform(e)))

        case Assign(lhs, rhs) =>
          lifted += DSLFeature(None, "__assign", Nil, List(List(lhs.tpe, rhs.tpe)))
          method("__assign", List(transform(lhs), transform(rhs)))

        case LabelDef(sym, List(), If(cond, Block(body :: Nil, Apply(Ident(label),
          List())), Literal(Constant()))) if label == sym => // While
          lifted += DSLFeature(None, "__whileDo", Nil, List(List(cond.tpe, body.tpe)))
          method("__whileDo", List(transform(cond), transform(body)))

        case LabelDef(sym, List(), Block(body :: Nil, If(cond, Apply(Ident(label),
          List()), Literal(Constant())))) if label == sym => // DoWhile
          lifted += DSLFeature(None, "__doWhile", Nil, List(List(cond.tpe, body.tpe)))
          method("__doWhile", List(transform(body), transform(cond)))

        case Apply(Select(qualifier, name), List(arg)) if name.toString equals "$eq$eq" =>
          lifted += DSLFeature(Some(qualifier.tpe), "__==", Nil, List(List(qualifier.tpe, arg.tpe)))
          method("__==", List(transform(qualifier), transform(arg)))

        case Apply(Select(qualifier, name), List(arg)) if name.toString equals "$bang$eq" =>
          lifted += DSLFeature(Some(qualifier.tpe), "__!=", Nil, List(List(qualifier.tpe, arg.tpe)))
          method("__!=", List(transform(qualifier), transform(arg)))

        case Apply(lhs @ Select(qualifier, name), List(arg)) if name.toString equals "eq" =>
          lifted += DSLFeature(Some(qualifier.tpe), "__eq", Nil, List(List(qualifier.tpe, arg.tpe)))
          method("__eq", List(transform(qualifier), transform(arg)))

        case Apply(lhs @ Select(qualifier, name), List(arg)) if name.toString equals "ne" =>
          lifted += DSLFeature(Some(qualifier.tpe), "__ne", Nil, List(List(qualifier.tpe, arg.tpe)))
          method("__ne", List(transform(qualifier), transform(arg)))

        case Apply(lhs @ Select(qualifier, name), List()) if name.toString equals "hashCode" =>
          lifted += DSLFeature(Some(qualifier.tpe), "__hashCode", Nil, List(List(qualifier.tpe)))
          method("__hashCode", List(transform(qualifier)))

        case Apply(lhs @ Select(qualifier, name), List()) if name.toString equals "$hash$hash" =>
          lifted += DSLFeature(Some(qualifier.tpe), "__##", Nil, List(List(qualifier.tpe)))
          method("__##", List(transform(qualifier)))

        case TypeApply(Select(qualifier, "asInstanceOf"), targs) =>
          lifted += DSLFeature(Some(qualifier.tpe), "__asInstanceOf", targs, List(List(qualifier.tpe)))
          method("__asInstanceOf", List(transform(qualifier)), targs)

        case TypeApply(Select(qualifier, "isInstanceOf"), targs) =>
          lifted += DSLFeature(Some(qualifier.tpe), "__isInstanceOf", targs, List(List(qualifier.tpe)))
          method("__isInstanceOf", List(transform(qualifier)), targs)
        // TODO Synchronization stuff

        case _ =>
          super.transform(tree)
      }
    }

    def apply(tree: Tree) = (transform(tree), lifted.toSeq)
  }

  object HoleTransformer {
    def apply(toMark: List[Int] = Nil)(tree: Tree) =
      new HoleTransformer(toMark).transform(tree)
  }
  /**
   * Replace all variables in `toMark` with `hole[T](classTag[T], symbolId)`
   */
  private final class HoleTransformer(toMark: List[Int]) extends Transformer {

    override def transform(tree: Tree): Tree = tree match {
      case i @ Ident(s) if toMark contains symbolId(i.symbol) =>
        Apply(
          Select(This(newTypeName(className)), newTermName(holeMethod)),
          List(
            TypeApply(
              Select(Select(Select(Select(Select(Ident(newTermName("scala")), newTermName("reflect")),
                newTermName("runtime")), nme.PACKAGE), newTermName("universe")),
                newTermName("typeTag")), List(TypeTree(i.tpe))),
            Literal(Constant(symbolId(i.symbol)))))
      case _ =>
        super.transform(tree)
    }
  }

  object ScopeInjectionTransformer extends (Tree => Tree) {
    def apply(tree: Tree) =
      new ScopeInjectionTransformer().transform(VirtualizationTransformer(tree)._1)
  }

  private final class ScopeInjectionTransformer extends Transformer {

    private[this] final def isHole(tree: Tree): Boolean =
      tree match {
        case Apply(TypeApply(Select(This(_), methodName), List(TypeTree())),
          List(Literal(Constant(_: Int)))) if methodName.decoded == holeMethod => true
        case _ => false
      }

    var ident = 0
    var typeApply = false

    def typeContext: typeTransformer.TypeContext =
      if (typeApply) typeTransformer.TypeApplyCtx else typeTransformer.OtherCtx

    override def transform(tree: Tree): Tree = {
      log(" " * ident + " ==> " + tree)
      ident += 1

      val result = tree match {
        //provide Def trees with NoSymbol (for correct show(tree)
        case vdDef: ValOrDefDef => {
          val retDef = super.transform(tree)
          retDef.setSymbol(NoSymbol)
          retDef
        }

        case typTree: TypTree if typTree.tpe != null =>
          constructTypeTree(typeContext, typTree.tpe)

        // re-wire objects
        case s @ Select(Select(inn, t: TermName), name) // package object goes to this
        if s.symbol.isMethod && (rewiredToThis(t.toString) || t.toString == "this") =>
          Ident(name)

        case s @ Select(inn, name) if s.symbol.isMethod =>
          Select(transform(inn), name)

        // replaces objects with their cake counterparts
        case s @ Select(inn, name) =>
          Ident(name)

        // Added to rewire inherited methods to this class
        case th @ This(_) =>
          This(newTypeName(className))

        case TypeApply(mth, targs) =>
          // TypeApply params need special treatment
          typeApply = true
          val liftedArgs = targs map (transform(_))
          typeApply = false
          TypeApply(transform(mth), liftedArgs)

        // Removes all import statements (for now).
        case Import(_, _) =>
          EmptyTree

        case _ =>
          super.transform(tree)
      }

      ident -= 1
      log(" " * ident + " <== " + result)

      result
    }
  }

  def constructTypeTree(tctx: TypeContext, inType: Type): Tree =
    typeTransformer transform (tctx, inType)

  def isPrimitive(s: Symbol): Boolean = false
  val guardType = "checkRef"

  /*
   * Utilities.
   */
  private def symbolId(symbol: Symbol): Int =
    symbol.asInstanceOf[scala.reflect.internal.Symbols#Symbol].id

  private def symbolId(tree: Tree): Int = symbolId(tree.symbol)

  def method(methName: String, args: List[Tree], targs: List[Tree] = Nil) =
    (targs, Apply(Select(This(newTypeName(className)), newTermName(methName)), args)) match {
      case (Nil, app) => app
      case (l, app)   => TypeApply(app, l)
    }

  def makeConstructor(classname: String, arguments: List[Tree]): Tree =
    invoke(newClass(classname), nme.CONSTRUCTOR, arguments)

  def invoke(qualifier: Tree, method: TermName, arguments: List[Tree]): Tree =
    Apply(Select(qualifier, method), arguments)

  def newClass(classname: String) =
    New(Ident(newTypeName(classname)))

  private lazy val dslTrait = {
    val names = dslName.split("\\.").toList.reverse
    assert(names.length >= 1,
      s"DSL trait name must be in the valid format. DSL trait name is ${dslName}")

    val tpeName = newTypeName(names.head)
    names.tail.reverse match {
      case head :: tail =>
        Select(tail.foldLeft[Tree](Ident(newTermName(head)))((tree, name) =>
          Select(tree, newTermName(name))), tpeName)
      case Nil =>
        Ident(tpeName)
    }
  }

  private var _reflInstance: Option[Object] = None

  /**
   * Reflectively instantiate and memoize a DSL instance.
   */
  private def reflInstance[T](dslDef: Tree): T = {
    if (_reflInstance == None) {
      val st = System.currentTimeMillis()
      _reflInstance = Some(c.eval(
        c.Expr(c.resetAllAttrs(Block(dslDef, constructor(className, List()))))))
      log(s"Eval time: ${(System.currentTimeMillis() - st)}")
    }
    _reflInstance.get.asInstanceOf[T]
  }

  private def constructor = Apply(Select(New(Ident(newTypeName(className))),
    nme.CONSTRUCTOR), List())

  def composeDSL(transformedBody: Tree) =
    // class MyDSL extends DSL {
    ClassDef(Modifiers(), newTypeName(className), List(),
      Template(List(dslTrait), emptyValDef,
        List(
          DefDef(Modifiers(), nme.CONSTRUCTOR, List(), List(List()), TypeTree(),
            Block(List(Apply(Select(Super(This(tpnme.EMPTY), tpnme.EMPTY),
              nme.CONSTRUCTOR), List())), Literal(Constant(())))),
          // def main = {
          DefDef(Modifiers(), newTermName(mainMethod), List(), List(List()),
            Ident(newTypeName("Any")), transformedBody))))
  //     }
  // }

  def log(s: => String) = if (debug) println(s)

}