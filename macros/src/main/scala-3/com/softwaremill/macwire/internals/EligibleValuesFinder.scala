package com.softwaremill.macwire.internals

import scala.annotation.tailrec
import scala.quoted.*

private[macwire] class EligibleValuesFinder[Q <: Quotes](log: Logger)(using val q: Q) {

  import q.reflect.*
  import EligibleValuesFinder.*

    private val typeCheckUtil = new TypeCheckUtil[q.type](log)
  import typeCheckUtil._

  def find(): EligibleValues = {
    val wiredDef = Symbol.spliceOwner.owner
    val wiredOwner = wiredDef.owner
    
    /** TODO
    * support for multilevel enclosing class and parameters - diamondInheritance.success,implicitDepsWiredWithImplicitDefs.success, implicitDepsWiredWithImplicitVals.success
    * support for method params - implicitDepsWiredWithImplicitValsFromMethodScope.success, functionApplication.success,anonFuncAndMethodsArgsWiredOk.success, anonFuncArgsWiredOk.success,
    *    methodMixedOk.success, methodParamsInApplyOk.success, methodParamsOk.success, methodSingleParamOk.success, methodWithSingleImplicitParamOk.success, nestedAnonFuncsWired.success
    *    nestedMethodsWired.success
    * search in blocks - methodContainingValDef.success, methodWithWiredWithinIfThenElse.success, methodWithWiredWithinPatternMatch.success, 
    **/

    //1 - enclosing class    
    println(s"WO: [$wiredOwner]")
    val classScopeValues = (wiredOwner.declaredMethods ::: wiredOwner.declaredFields)
        .filter(m => !m.fullName.startsWith("java.lang.Object") && !m.fullName.startsWith("scala.Any"))
        .map(_.tree).collect {
            case m: ValDef => EligibleValue(m.rhs.map(_.tpe).getOrElse(m.tpt.tpe), m)
            case m: DefDef if m.termParamss.flatMap(_.params).isEmpty =>
                EligibleValue(m.rhs.map(_.tpe).getOrElse(m.returnTpt.tpe), m)
    }

    //2 - imported instances 
    //TODO
    //it seems that import statement is missed in the tree obtained from Symbol.spliceOwner
    //https://github.com/lampepfl/dotty/issues/12965
    //Tests: import*.success (7)
    val importScopeValues = List.empty[EligibleValue]

    //3 - params
    def findParamsRec(s: Symbol): List[EligibleValue] = s.tree match {
      case DefDef(_, lpc, _, _) => lpc.flatMap(_.params).collect {
            case m: ValDef => EligibleValue(m.rhs.map(_.tpe).getOrElse(m.tpt.tpe), m)
            case m: DefDef if m.termParamss.flatMap(_.params).isEmpty =>
                EligibleValue(m.rhs.map(_.tpe).getOrElse(m.returnTpt.tpe), m)
      } ::: findParamsRec(s.owner)
      case _ => List.empty[EligibleValue]
    }

    val methodParamsScopeValues = findParamsRec(wiredDef)

    // val params = List.empty[EligibleValue]
    //4 - parent types
    // val parentScopValues = (wiredOwner.memberMethods ::: wiredOwner.memberFields).filterNot((wiredOwner.declaredMethods ::: wiredOwner.declaredFields).toSet)
    //     .filter(m => !m.fullName.startsWith("java.lang.Object") && !m.fullName.startsWith("scala.Any"))
    //     .map(_.tree).collect {
    //         case m: ValDef => EligibleValue(m.rhs.map(_.tpe).getOrElse(m.tpt.tpe), m)
    //         case m: DefDef if m.termParamss.flatMap(_.params).isEmpty =>
    //             EligibleValue(m.rhs.map(_.tpe).getOrElse(m.returnTpt.tpe), m)
    // }
    // https://github.com/lampepfl/dotty/discussions/12966
    //Tests: implicitDepsWiredWithImplicitValsFromParentsScope.success, inheritance*.success(9), selfType.success, selfTypeHKT.success
    val parentScopValues = List.empty[EligibleValue]

    val a = EligibleValues(Map(
        Scope.init -> classScopeValues,
        Scope(2) -> (methodParamsScopeValues ++ importScopeValues),
        Scope(3) -> parentScopValues
    ))

    println(s"EV: [${a.values.map(b => (b._1, b._2.mkString(", "))).mkString(", ")}]")
    a
  }

  case class EligibleValue(tpe: TypeRepr, expr: Tree) {
    // equal trees should have equal hash codes; if trees are equal structurally they should have the same toString?
    override def hashCode() = expr.toString().hashCode

    override def equals(obj: scala.Any) = obj match {
      case EligibleValue(_, e) => expr == e//FIXME not sure if `equalsStructure` -> `==` 
      case _ => false
    }
  }

  class EligibleValues(val values: Map[Scope, List[EligibleValue]]) {

    private def doFindInScope(tpe: TypeRepr, scope: Scope): List[Tree] = {
      for( scopedValue <- values.getOrElse(scope, Nil) if checkCandidate(target = tpe, tpt = scopedValue.tpe)) yield {
        scopedValue.expr
      }
    }

    private def uniqueTrees(trees: List[Tree]): Iterable[Tree] = {
      // the only reliable method to compare trees is using structural equality, but there shouldn't be a lot of
      // trees with a given type, so the n^2 complexity shouldn't hurt
      def addIfUnique(addTo: List[Tree], t: Tree): List[Tree] = {
        addTo.find(_ == t).fold(t :: addTo)(_ => addTo)
      }

      trees.foldLeft(List.empty[Tree])(addIfUnique)
    }

    def findInScope(tpe: TypeRepr, scope: Scope): Iterable[Tree] = {
      uniqueTrees(doFindInScope(tpe, scope))
    }

    def findInFirstScope(tpe: TypeRepr, startingWith: Scope = Scope.init): Iterable[Tree] = {
      @tailrec
      def forScope(scope: Scope): Iterable[Tree] = {
        findInScope(tpe, scope) match {
          case coll if coll.isEmpty && !scope.isMax => forScope(scope.widen)
          case coll if coll.isEmpty => log(s"Could not find $tpe in any scope"); Nil
          case exprs =>
            log(s"Found [${exprs.mkString(", ")}] of type [$tpe] in scope $scope")
            exprs
        }
      }
      forScope(startingWith)
    }

    def findInAllScope(tpe: TypeRepr): Iterable[Tree] = {
      @tailrec
      def accInScope(scope: Scope, acc: List[Tree]): List[Tree] = {
        val newAcc = doFindInScope(tpe, scope) ++ acc
        if( !scope.isMax ) accInScope(scope.widen, newAcc) else newAcc
      }
      uniqueTrees(accInScope(Scope.init, Nil))
    }
  }

  object EligibleValues {
    val empty: EligibleValues = new EligibleValues(Map.empty)
  }

}

object EligibleValuesFinder {
  case class Scope(val value: Int) extends Ordered[Scope] {
    /** @return the next Scope until Max */
    def widen: Scope = copy(value = this.value + 1)

    def isMax: Boolean = widen == this
    override def compare(that: Scope): Int = this.value.compare(that.value)
    override def equals(other: Any): Boolean = other match {
      case otherScope: Scope => this.value == otherScope.value
      case _ => false
    }
    override def hashCode = value.hashCode
  }

  object Scope extends Ordering[Scope] {

    /** The smallest Scope */
    val init = Scope(1) 

    override def compare(a: Scope, b: Scope): Int = a.compare(b)
  }

}
