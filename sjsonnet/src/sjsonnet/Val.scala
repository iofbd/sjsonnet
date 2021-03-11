package sjsonnet

import java.util

import sjsonnet.Expr.Member.Visibility
import sjsonnet.Expr.Params

import scala.annotation.tailrec
import scala.collection.mutable
import scala.reflect.ClassTag

/**
  * [[Val]]s represented Jsonnet values that are the result of evaluating
  * a Jsonnet program. The [[Val]] data structure is essentially a JSON tree,
  * except evaluation of object attributes and array contents are lazy, and
  * the tree can contain functions.
  */
sealed trait Val{
  def prettyName: String
  def cast[T: ClassTag: PrettyNamed] =
    if (implicitly[ClassTag[T]].runtimeClass.isInstance(this)) this.asInstanceOf[T]
    else throw new Error.Delegate(
      "Expected " + implicitly[PrettyNamed[T]].s + ", found " + prettyName
    )
  def pos: Position
}
class PrettyNamed[T](val s: String)
object PrettyNamed{
  implicit def strName: PrettyNamed[Val.Str] = new PrettyNamed("string")
  implicit def numName: PrettyNamed[Val.Num] = new PrettyNamed("number")
  implicit def arrName: PrettyNamed[Val.Arr] = new PrettyNamed("array")
  implicit def objName: PrettyNamed[Val.Obj] = new PrettyNamed("object")
  implicit def funName: PrettyNamed[Val.Func] = new PrettyNamed("function")
}
case class Position(currentFile: Path, offset: Int)
object Position{
  def apply(offset: Int)(implicit fileScope: FileScope): Position = Position(fileScope.currentFile, offset)
}
object Val{

  /**
    * [[Lazy]] models lazy evaluation within a Jsonnet program. Lazily
    * evaluated dictionary values, array contents, or function parameters
    * are all wrapped in [[Lazy]] and only truly evaluated on-demand
    */
  class Lazy(calc0: => Val){
    lazy val force = calc0
  }
  object Lazy{
    def apply(calc0: => Val) = new Lazy(calc0)
  }

  def bool(pos: Position, b: Boolean) = if (b) True(pos) else False(pos)
  sealed trait Bool extends Val{
  }
  case class True(pos: Position) extends Bool{
    def prettyName = "boolean"
  }
  case class False(pos: Position) extends Bool{
    def prettyName = "boolean"
  }
  case class Null(pos: Position) extends Val{
    def prettyName = "null"
  }
  case class Str(pos: Position, value: String) extends Val{
    def prettyName = "string"
  }
  case class Num(pos: Position, value: Double) extends Val{
    def prettyName = "number"
  }
  case class Arr(pos: Position, value: Seq[Lazy]) extends Val{
    def prettyName = "array"
  }
  object Obj{

    case class Member(add: Boolean,
                      visibility: Visibility,
                      invoke: (Obj, Option[Obj], FileScope, EvalScope) => Val,
                      cached: Boolean = true)


  }
  final class Obj(val pos: Position,
                  value0: mutable.Map[String, Obj.Member],
                  triggerAsserts: Val.Obj => Unit,
                  `super`: Option[Obj]) extends Val{

    def getSuper = `super`

    @tailrec def triggerAllAsserts(obj: Val.Obj): Unit = {
      triggerAsserts(obj)
      `super` match {
        case Some(s) => s.triggerAllAsserts(obj)
        case None => ()
      }
    }

    def addSuper(pos: Position, lhs: Val.Obj): Val.Obj = {
      `super` match{
        case None => new Val.Obj(pos, value0, _ => (), Some(lhs))
        case Some(x) => new Val.Obj(pos, value0, _ => (), Some(x.addSuper(pos, lhs)))
      }
    }

    def prettyName = "object"

    def foreachVisibleKey(output: (String, Visibility) => Unit): Unit = {
      for(s <- this.`super`) s.foreachVisibleKey(output)
      for(t <- value0) output(t._1, t._2.visibility)
    }

    def getVisibleKeys() = {
      val mapping = mutable.LinkedHashMap.empty[String, Boolean]
      foreachVisibleKey{ (k, sep) =>
        (mapping.get(k), sep) match{
          case (None, Visibility.Hidden) => mapping(k) = true
          case (None, _)    => mapping(k) = false

          case (Some(false), Visibility.Hidden) => mapping(k) = true
          case (Some(true), Visibility.Unhide) => mapping(k) = false
          case (Some(x), _) => mapping(k) = x
        }
      }
      mapping
    }
    private[this] val valueCache = collection.mutable.Map.empty[Any, Val]
    def value(k: String,
              offset: Int,
              self: Obj = this)
             (implicit fileScope: FileScope, evaluator: EvalScope): Val = {

      val cacheKey = if(self eq this) k else (k, self)

      valueCache.get(cacheKey) match{
        case Some(res) => res
        case None =>
          valueRaw(k, self, offset) match{
            case Some((x, cached)) =>
              if (cached) valueCache(cacheKey) = x
              x
            case None =>
              Error.fail("Field does not exist: " + k, offset)
          }
      }
    }

    def mergeMember(l: Val,
                    r: Val,
                    offset: Int)
                   (implicit fileScope: FileScope, evaluator: EvalScope) = (l, r) match{
      case (Val.Str(_, l), Val.Str(_, r)) => Val.Str(Position(fileScope.currentFile, offset), l + r)
      case (Val.Num(_, l), Val.Num(_, r)) => Val.Num(Position(fileScope.currentFile, offset), l + r)
      case (Val.Arr(_, l), Val.Arr(_, r)) => Val.Arr(Position(fileScope.currentFile, offset), l ++ r)
      case (l: Val.Obj, r: Val.Obj) => r.addSuper(Position(fileScope.currentFile, offset), l)
      case (Val.Str(_, l), r) =>
        try Val.Str(Position(fileScope.currentFile, offset), l + evaluator.materialize(r).transform(new Renderer()).toString)
        catch Error.tryCatchWrap(offset)
      case (l, Val.Str(_, r)) =>
        try Val.Str(Position(fileScope.currentFile, offset), evaluator.materialize(l).transform(new Renderer()).toString + r)
        catch Error.tryCatchWrap(offset)
    }

    @tailrec def valueCached(k: String): Option[Boolean] = this.value0.get(k) match{
      case Some(m) => Some(m.cached)

      case None => this.`super` match{
        case None => None
        case Some(s) => s.valueCached(k)
      }
    }

    def valueRaw(k: String,
                 self: Obj,
                 offset: Int)
                (implicit fileScope: FileScope, evaluator: EvalScope): Option[(Val, Boolean)] = {
      this.value0.get(k) match{
        case Some(m) =>
          this.`super` match{
            case Some(s) if m.add =>
              val merged = s.valueRaw(k, self, offset) match{
                case None => m.invoke(self, this.`super`, fileScope, evaluator)
                case Some((supValue, supCached)) =>
                  mergeMember(supValue, m.invoke(self, this.`super`, fileScope, evaluator), offset)
              }

              Some(merged -> m.cached)

            case _ =>
              Some(m.invoke(self, this.`super`, fileScope, evaluator) -> m.cached)
          }

        case None => this.`super` match{
          case None => None
          case Some(s) => s.valueRaw(k, self, offset)
        }
      }
    }

    @tailrec def containsKey(k: String): Boolean = {
      this.value0.contains(k) || {
        this.`super` match {
          case None => false
          case Some(s) => s.containsKey(k)
        }
      }
    }
  }

  case class Func(pos: Position,
                  defSiteScopes: Option[(ValScope, FileScope)],
                  params: Params,
                  evalRhs: (ValScope, String, EvalScope, FileScope, Int) => Val,
                  evalDefault: (Expr, ValScope, EvalScope) => Val = null) extends Val{
    def prettyName = "function"
    def apply(args: Seq[(Option[String], Lazy)],
              thisFile: String,
              outerOffset: Int)
             (implicit fileScope: FileScope, evaluator: EvalScope) = {

      lazy val defaultArgsBindings = {
        var idx = 0
        val arr = new Array[(Int, Lazy)](params.defaults.size)
        while (idx < params.defaults.size) {
          val (index, default) = params.defaults(idx)
          arr(idx) = (index, Lazy(evalDefault(default, newScope, evaluator)))
          idx += 1
        }
        arr
      }

      lazy val passedArgsBindings: Seq[(Int, sjsonnet.Val.Lazy)] = try {
        var i = 0
        var argsSize = args.size
        val arr: Array[(Int, sjsonnet.Val.Lazy)] = new Array(argsSize)
        while (i < argsSize) {
          args(i) match {
            case (Some(name), v) =>
              val argIndex = params.argIndices.getOrElse(
                name,
                Error.fail(s"Function has no parameter $name", outerOffset)
              )
              arr(i) = (argIndex, v)
            case (None, v) =>
              arr(i) = (params.args(i)._3, v)
          }
          i += 1
        }
        arr
        /*
        args.zipWithIndex.map{
          case ((Some(name), v), _) =>
            val argIndex = params.argIndices.getOrElse(
              name,
              Error.fail(s"Function has no parameter $name", outerOffset)
            )
            (argIndex, v)
          case ((None, v), i) => (params.args(i)._3, v)
        }
        */
      } catch { case e: IndexOutOfBoundsException =>
        Error.fail(
          "Too many args, function has " + params.args.length + " parameter(s)",
          outerOffset
        )
      }

      lazy val newScope: ValScope = {
        var max = -1
        val builder = new Array[(Int, (Option[Val.Obj], Option[Val.Obj]) => Lazy)](defaultArgsBindings.size + passedArgsBindings.size)
        var idx = 0

        var defaultBindingsIdx = 0
        while (defaultBindingsIdx < defaultArgsBindings.size) {
          val t = defaultArgsBindings(defaultBindingsIdx)
          val (i, v) = t
          if (i > max) max = i
          builder(idx) = (i, (self: Option[Val.Obj], sup: Option[Val.Obj]) => v)
          idx += 1
          defaultBindingsIdx += 1
        }

        var passedArgsBindingsIdx = 0
        while(passedArgsBindingsIdx < passedArgsBindings.size) {
          val t = passedArgsBindings(passedArgsBindingsIdx)
          val (i, v) = t
          if (i > max) max = i
          builder(idx) = (i, (self: Option[Val.Obj], sup: Option[Val.Obj]) => v)
          idx += 1
          passedArgsBindingsIdx += 1
        }

        defSiteScopes match{
          case None => new ValScope(
            None,
            None,
            None,
            {
              val arr = new Array[Lazy](max + 1)
              for((i, v) <- builder) arr(i) = v(null, None)
              arr
            }
          )
          case Some((s, fs)) => s.extend(builder)
        }
      }

      val funDefFileScope: FileScope = defSiteScopes match {case None => fileScope case Some((s, fs)) => fs}
      validateFunctionCall(passedArgsBindings, params, outerOffset, funDefFileScope)

      evalRhs(
        newScope,
        thisFile,
        evaluator,
        funDefFileScope,
        outerOffset
      )
    }

    def validateFunctionCall(passedArgsBindings: Seq[(Int, Lazy)],
                             params: Params,
                             outerOffset: Int,
                             defSiteFileScope: FileScope)
                            (implicit fileScope: FileScope, eval: EvalScope): Unit = {

      val argListSize = passedArgsBindings.size
      val seen = new util.BitSet(argListSize)
      val repeats = new util.BitSet(argListSize)

      var idx = 0
      while (idx < passedArgsBindings.size) {
        val t = passedArgsBindings(idx)
        if (!seen.get(t._1)) seen.set(t._1)
        else repeats.set(t._1)
        idx += 1
      }

      Error.failIfNonEmpty(
        repeats,
        outerOffset,
        (plural, names) => s"binding parameter a second time: $names",
        Some(defSiteFileScope)
      )

      val b = params.noDefaultIndices.clone().asInstanceOf[util.BitSet]
      b.andNot(seen)
      Error.failIfNonEmpty(
        // params.noDefaultIndices.filter(!seen.get(_)),
        b,
        outerOffset,
        (plural, names) => s"Function parameter$plural $names not bound in call"
      )(defSiteFileScope, eval) // pass the definition site for the correct error message/names to be resolved

      seen.andNot(params.allIndices)

      Error.failIfNonEmpty(
        // seen.filter(!params.allIndices(_)),
        seen,
        outerOffset,
        (plural, names) => s"Function has no parameter$plural $names"
      )
    }
  }
}

/**
  * [[EvalScope]] models the per-evaluator context that is propagated
  * throughout the Jsonnet evaluation.
  */
trait EvalScope extends EvalErrorScope{
  def visitExpr(expr: Expr)
               (implicit scope: ValScope, fileScope: FileScope): Val

  def materialize(v: Val): ujson.Value

  val emptyMaterializeFileScope = new FileScope(wd / "(materialize)", Map())

  val preserveOrder: Boolean = false
}
object ValScope{
  def empty(size: Int) = new ValScope(None, None, None, new Array(size))
}

/**
  * [[ValScope]]s which model the lexical scopes within
  * a Jsonnet file that bind variable names to [[Val]]s, as well as other
  * contextual information like `self` `this` or `super`.
  *
  * Note that scopes are standalone, and nested scopes are done by copying
  * and updating the array of bindings rather than using a linked list. This
  * is because the bindings array is typically pretty small and the constant
  * factor overhead from a cleverer data structure dominates any algorithmic
  * improvements
  *
  * The bindings array is private and only copy-on-write, so for nested scopes
  * which do not change it (e.g. those just updating `dollar0` or `self0`) the
  * bindings array can be shared cheaply.
  */
class ValScope(val dollar0: Option[Val.Obj],
               val self0: Option[Val.Obj],
               val super0: Option[Val.Obj],
               bindings0: Array[Val.Lazy]) {

  def bindings(k: Int): Option[Val.Lazy] = bindings0(k) match{
    case null => None
    case v => Some(v)
  }

  def extend(newBindings: TraversableOnce[(Int, (Option[Val.Obj], Option[Val.Obj]) => Val.Lazy)] = Nil,
             newDollar: Option[Val.Obj] = null,
             newSelf: Option[Val.Obj] = null,
             newSuper: Option[Val.Obj] = null) = {
    val dollar = if (newDollar != null) newDollar else dollar0
    val self = if (newSelf != null) newSelf else self0
    val sup = if (newSuper != null) newSuper else super0
    new ValScope(
      dollar,
      self,
      sup,
      if (newBindings.isEmpty) bindings0
      else{
        val newArr = java.util.Arrays.copyOf(bindings0, bindings0.length)
        for((i, v) <- newBindings) newArr(i) = v.apply(self, sup)
        newArr
      }
    )
  }
}
