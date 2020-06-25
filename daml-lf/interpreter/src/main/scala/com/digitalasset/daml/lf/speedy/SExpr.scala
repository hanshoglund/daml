// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.speedy

/**
  * The simplified AST for the speedy interpreter.
  *
  * This reduces the number of binding forms by moving update and scenario
  * expressions into builtins.
  */
import java.util

import com.daml.lf.language.Ast._
import com.daml.lf.data.Ref._
import com.daml.lf.value.{Value => V}
import com.daml.lf.speedy.SValue._
import com.daml.lf.speedy.Speedy._
import com.daml.lf.speedy.SError._
import com.daml.lf.speedy.SBuiltin._

/** The speedy expression:
  * - de Bruijn indexed.
  * - closure converted.
  * - multi-argument applications and abstractions.
  * - all update and scenario operations converted to builtin functions.
  */
sealed abstract class SExpr extends Product with Serializable {
  def execute(machine: Machine): Unit
  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  override def toString: String =
    productPrefix + productIterator.map(SExpr.prettyPrint).mkString("(", ",", ")")
}

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object SExpr {

  sealed abstract class SExprAtomic extends SExpr {
    def evaluate(machine: Machine): SValue

    def execute(machine: Machine): Unit = {
      machine.returnValue = evaluate(machine)
    }

  }

  /** Reference to a variable. 'index' is the 1-based de Bruijn index,
    * that is, SEVar(1) points to the nearest enclosing variable binder.
    * which could be an SELam, SELet, or a binding variant of SECasePat.
    * https://en.wikipedia.org/wiki/De_Bruijn_index
    * This expression form is only allowed prior to closure conversion
    */
  final case class SEVar(index: Int) extends SExpr {
    def execute(machine: Machine): Unit = {
      crash("unexpected SEVar, expected SELoc(S/A/F)")
    }
  }

  /** Reference to a value. On first lookup the evaluated expression is
    * stored in 'cached'.
    */
  final case class SEVal(
      ref: SDefinitionRef,
  ) extends SExpr {

    // The variable `_cached` is used to cache the evaluation of the
    // LF value defined by `ref` once it has been computed.  Hence we
    // avoid both the lookup in the package definition HashMap and the
    // full reevaluation of the body of the definition.
    // Here we take advantage of the Java memory model
    // (https://docs.oracle.com/javase/specs/jls/se8/html/jls-17.html#jls-17.7)
    // that guarantees the write of `_cache` (in the method
    // `setCached`) is done atomically.
    // This is similar how hashcode evaluation is cached in String
    // http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/tip/src/share/classes/java/lang/String.java
    private var _cached: Option[(SValue, List[Location])] = None

    def cached: Option[(SValue, List[Location])] = _cached

    def setCached(sValue: SValue, stack_trace: List[Location]): Unit =
      _cached = Some((sValue, stack_trace))

    def execute(machine: Machine): Unit =
      machine.lookupVal(this)
  }

  /** Reference to a builtin function */
  final case class SEBuiltin(b: SBuiltinMaybeHungry) extends SExprAtomic {
    def evaluate(machine: Machine): SValue = {
      /* special case for nullary record constructors */
      b match {
        case SBRecCon(id, fields) if b.arity == 0 =>
          SRecord(id, fields, new util.ArrayList())
        case _ =>
          SPAP(PBuiltin(b), new util.ArrayList(), b.arity)
      }
    }
  }

  /** A pre-computed value, usually primitive literal, e.g. integer, text, boolean etc. */
  final case class SEValue(v: SValue) extends SExprAtomic {
    def evaluate(machine: Machine): SValue = {
      v
    }
  }

  object SEValue extends SValueContainer[SEValue]

  /** Function application: General case: 'fun' and 'args' are any kind of expression */
  final case class SEAppGeneral(fun: SExpr, args: Array[SExpr]) extends SExpr with SomeArrayEquals {
    def execute(machine: Machine): Unit = {
      //machine.pushKont(KArg(args, machine.frame, machine.actuals, machine.env.size))
      //machine.ctrl = fun
      throw SErrorCrash(s"execute: unexpected SEAppGeneral")
    }
  }

  object SEApp {
    def apply(fun: SExpr, args: Array[SExpr]): SExpr = {
      SEAppGeneral(fun, args)
    }
  }

  /** Function application: ANF case: 'fun' and 'args' are atomic expressions */
  final case class SEAppAtomicGeneral(fun: SExprAtomic, args: Array[SExprAtomic])
      extends SExpr
      with SomeArrayEquals {
    def execute(machine: Machine): Unit = {
      val vfun = fun.evaluate(machine)
      //TODO: evaluate args to SValue here
      enterApplication(machine, vfun, args)
    }
  }

  /** Function application: ANF case: 'fun' is builtin; 'args' are atomic expressions.  Size
    * of `args' matches the builtin arity. */
  final case class SEAppAtomicSaturatedBuiltin(
      builtin: SBuiltinMaybeHungry,
      args: Array[SExprAtomic])
      extends SExpr
      with SomeArrayEquals {
    def execute(machine: Machine): Unit = {
      val arity = builtin.arity
      val actuals = new util.ArrayList[SValue](arity)
      for (i <- 0 to arity - 1) {
        val arg = args(i)
        val v = arg.evaluate(machine)
        actuals.add(v)
      }
      builtin.execute(actuals, machine)
    }
  }

  object SEAppAtomic {
    // smart constructor: detect special case of saturated builtin application
    def apply(func: SExprAtomic, args: Array[SExprAtomic]): SExpr = {
      func match {
        case SEBuiltin(builtin) if builtin.arity == args.length =>
          SEAppAtomicSaturatedBuiltin(builtin, args)
        case _ =>
          SEAppAtomicGeneral(func, args) // general case
      }
    }
  }

  /** Lambda abstraction. Transformed into SEMakeClo in lambda lifting.
    * NOTE(JM): Compilation done in two passes so that closure conversion
    * can be written against this simplified expression type.
    */
  final case class SEAbs(arity: Int, body: SExpr) extends SExpr {
    def execute(machine: Machine): Unit =
      crash("unexpected SEAbs, expected SEMakeClo")
  }

  object SEAbs {
    // Helper for constructing abstraction expressions:
    // SEAbs(1) { ... }
    def apply(arity: Int)(body: SExpr): SExpr = SEAbs(arity, body)

    val identity: SEAbs = SEAbs(1, SEVar(1))
  }

  /** Closure creation. Create a new closure object storing the free variables
    * in 'body'.
    */
  final case class SEMakeClo(fvs: Array[SELoc], arity: Int, body: SExpr)
      extends SExpr
      with SomeArrayEquals {

    def execute(machine: Machine): Unit = {
      val sValues = Array.ofDim[SValue](fvs.length)
      var i = 0
      while (i < fvs.length) {
        sValues(i) = fvs(i).lookup(machine)
        i += 1
      }
      machine.returnValue =
        SPAP(PClosure(Profile.LabelUnset, body, sValues), new util.ArrayList[SValue](), arity)
    }
  }

  /** SELoc -- Reference to the runtime location of a variable.

    This is the closure-converted form of SEVar. There are three sub-forms, with sufffix:
    S/A/F, indicating [S]tack, [A]argument, or [F]ree variable captured by a closure.
    */
  sealed abstract class SELoc extends SExprAtomic {
    def lookup(machine: Machine): SValue
    def evaluate(machine: Machine): SValue = {
      lookup(machine)
    }
  }

  // SELocS -- variable is located on the stack (SELet & binding forms of SECasePat)
  final case class SELocS(n: Int) extends SELoc {
    def lookup(machine: Machine): SValue = {
      machine.getEnvStack(n)
    }
  }

  // SELocS -- variable is located in the args array of the application
  final case class SELocA(n: Int) extends SELoc {
    def lookup(machine: Machine): SValue = {
      machine.getEnvArg(n)
    }
  }

  // SELocF -- variable is located in the free-vars array of the closure being applied
  final case class SELocF(n: Int) extends SELoc {
    def lookup(machine: Machine): SValue = {
      machine.getEnvFree(n)
    }
  }

  /** Pattern match. */
  final case class SECase(scrut: SExpr, alts: Array[SCaseAlt]) extends SExpr with SomeArrayEquals {
    def execute(machine: Machine): Unit = {
      throw SErrorCrash(s"execute: unexpected SEcase")
    }
    override def toString: String = s"SECase($scrut, ${alts.mkString("[", ",", "]")})"
  }

  final case class SECaseAtomic(scrut: SExprAtomic, alts: Array[SCaseAlt])
      extends SExpr
      with SomeArrayEquals {
    def execute(machine: Machine): Unit = {
      val vscrut = scrut.evaluate(machine)
      executeMatchAlts(machine, alts, vscrut)
    }
  }

  /** A let-expression with a single RHS */
  final case class SELet1General(rhs: SExpr, body: SExpr) extends SExpr with SomeArrayEquals {
    def execute(machine: Machine): Unit = {
      machine.pushKont(KPushTo(machine.env, body, machine.frame, machine.actuals, machine.env.size))
      machine.ctrl = rhs
    }
  }

  /** A (single) let-expression with an unhungry,saturated builtin-application as RHS */
  final case class SELet1Builtin(builtin: SBuiltin, args: Array[SExprAtomic], body: SExpr)
      extends SExpr
      with SomeArrayEquals {
    def execute(machine: Machine): Unit = {
      val arity = builtin.arity
      val actuals = new util.ArrayList[SValue](arity)
      for (i <- 0 to arity - 1) {
        val arg = args(i)
        val v = arg.evaluate(machine)
        actuals.add(v)
      }
      // TODO: define/call builtin.evaluate() to get the SValue directly
      // Every non-hungry SBuiltin assigns an SValue to returnValue when we call execute()
      builtin.execute(actuals, machine)
      if (machine.returnValue == null) {
        crash("SELet1Builtin, called SBuiltin.execute(), but didn't get returnValue")
      }
      val v = machine.returnValue
      machine.returnValue = null

      machine.env.add(v)
      machine.ctrl = body
    }
  }

  object SELet1 {
    def apply(rhs: SExpr, body: SExpr): SExpr = {
      rhs match {
        case SEAppAtomicSaturatedBuiltin(builtin: SBuiltin, args) =>
          SELet1Builtin(builtin, args, body)
        case _ => SELet1General(rhs, body)
      }
    }
  }

  /** A non-recursive, non-parallel let block. Each bound expression
    * is evaluated in turn and pushed into the environment one by one,
    * with later expressions possibly referring to earlier.
    */
  final case class SELet(bounds: Array[SExpr], body: SExpr) extends SExpr with SomeArrayEquals {
    def execute(machine: Machine): Unit = {

      // Evaluate the body after we've evaluated the binders
      machine.pushKont(
        KPushTo(
          machine.env,
          body,
          machine.frame,
          machine.actuals,
          machine.env.size + bounds.size - 1))

      // Start evaluating the let binders
      for (i <- 1 until bounds.size) {
        val b = bounds(bounds.size - i)
        val expectedEnvSize = machine.env.size + bounds.size - i - 1
        machine.pushKont(KPushTo(machine.env, b, machine.frame, machine.actuals, expectedEnvSize))
      }
      machine.ctrl = bounds.head
    }
  }

  object SELet {

    // Helpers for constructing let expressions:
    // Instead of
    //   SELet(Array(SEVar(4), SEVar(1)), SEVar(1))
    // you can write:
    //   SELet(
    //     SEVar(4),
    //     SEVar(1)
    //   ) in SEVar(1)
    case class PartialSELet(bounds: Array[SExpr]) extends SomeArrayEquals {
      def in(body: => SExpr): SExpr = SELet(bounds, body)
    }

    def apply(bounds: SExpr*) = PartialSELet(bounds.toArray)

  }

  /** Location annotation. When encountered the location is stored in the 'lastLocation'
    * variable of the machine. When commit is begun the location is stored in 'commitLocation'.
    */
  final case class SELocation(loc: Location, expr: SExpr) extends SExpr {
    def execute(machine: Machine): Unit = {
      machine.pushLocation(loc)
      machine.ctrl = expr
    }
  }

  /** A catch expression. This is used internally solely for the purpose of implementing
    * mustFailAt. If the evaluation of 'body' causes an exception of type 'DamlException'
    * (see SError), then the environment and continuation stacks are reset and 'handler'
    * is executed. If the evaluation is successful, then the 'fin' expression is evaluated.
    * This is on purpose very limited, with no mechanism to inspect the exception, nor a way
    * to access the value returned from 'body'.
    */
  final case class SECatch(body: SExpr, handler: SExpr, fin: SExpr) extends SExpr {
    def execute(machine: Machine): Unit = {
      machine.pushKont(KCatch(handler, fin, machine.frame, machine.actuals, machine.env.size))
      machine.ctrl = body
    }
  }

  /** This is used only during profiling. When a package is compiled with
    * profiling enabled, the right hand sides of top-level and let bindings,
    * lambdas and some builtins are wrapped into [[SELabelClosure]]. During
    * runtime, if the value resulting from evaluating [[expr]] is a
    * (partially applied) closure, the label of the closure is set to the
    * [[label]] given here.
    * See [[com.daml.lf.speedy.Profile]] for an explanation why we use
    * [[AnyRef]] for the label.
    */
  final case class SELabelClosure(label: Profile.Label, expr: SExpr) extends SExpr {
    def execute(machine: Machine): Unit = {
      machine.pushKont(KLabelClosure(label))
      machine.ctrl = expr
    }
  }

  /** When we fetch a contract id from upstream we cannot crash in the upstream
    * calls. Rather, we set the control to this expression and then crash when executing.
    */
  final case class SEWronglyTypeContractId(
      acoid: V.ContractId,
      expected: TypeConName,
      actual: TypeConName,
  ) extends SExpr {
    def execute(machine: Machine): Unit = {
      throw DamlEWronglyTypedContract(acoid, expected, actual)
    }
  }

  final case class SEImportValue(value: V[V.ContractId]) extends SExpr {
    def execute(machine: Machine): Unit = {
      machine.importValue(value)
    }
  }

  /** Case patterns */
  sealed trait SCasePat

  /** Match on a variant. On match the value is unboxed and pushed to stack. */
  final case class SCPVariant(id: Identifier, variant: Name, constructorRank: Int) extends SCasePat

  /** Match on a variant. On match the value is unboxed and pushed to stack. */
  final case class SCPEnum(id: Identifier, constructor: Name, constructorRank: Int) extends SCasePat

  /** Match on a primitive constructor, that is on true, false or unit. */
  final case class SCPPrimCon(pc: PrimCon) extends SCasePat

  /** Match on an empty list. */
  final case object SCPNil extends SCasePat

  /** Match on a list. On match, the head and tail of the list is pushed to the stack. */
  final case object SCPCons extends SCasePat

  /** Default match case. Always matches. */
  final case object SCPDefault extends SCasePat

  final case object SCPNone extends SCasePat

  final case object SCPSome extends SCasePat

  /** Case alternative. If the 'pattern' matches, then the environment is accordingly
    * extended and 'body' is evaluated. */
  final case class SCaseAlt(pattern: SCasePat, body: SExpr)

  sealed abstract class SDefinitionRef {
    def ref: DefinitionRef
    def packageId: PackageId = ref.packageId
    def modName: ModuleName = ref.qualifiedName.module
  }

  // references to definitions that come from the archive
  final case class LfDefRef(ref: DefinitionRef) extends SDefinitionRef
  // references to definitions generated by the Speedy compiler
  final case class ChoiceDefRef(ref: DefinitionRef, choiceName: ChoiceName) extends SDefinitionRef

  //
  // List builtins (foldl, foldr, equalList) are implemented as recursive
  // definition to save java stack
  //

  final case class SEBuiltinRecursiveDefinition(
      ref: SEBuiltinRecursiveDefinition.Reference,
  ) extends SExprAtomic {

    import SEBuiltinRecursiveDefinition._

    private val frame = Array.ofDim[SValue](0) // no free vars
    private val arity = 3

    private def body: SExpr = ref match {
      case Reference.FoldL => foldLBody
      case Reference.FoldR => foldRBody
      case Reference.EqualList => equalListBody
    }

    private def closure: SValue =
      SPAP(PClosure(Profile.LabelUnset, body, frame), new util.ArrayList[SValue](), arity)

    def evaluate(machine: Machine): SValue = closure

  }

  final object SEBuiltinRecursiveDefinition {

    sealed abstract class Reference

    final object Reference {
      final case object FoldL extends Reference
      final case object FoldR extends Reference
      final case object EqualList extends Reference
    }

    val FoldL = SEBuiltinRecursiveDefinition(Reference.FoldL)
    val FoldR = SEBuiltinRecursiveDefinition(Reference.FoldR)
    val EqualList = SEBuiltinRecursiveDefinition(Reference.EqualList)

    private def foldLBody: SExpr =
      SECaseAtomic( // case xs of
        SELocA(2),
        Array(
          SCaseAlt(SCPNil, SELocA(1)), // nil -> z
          SCaseAlt( // cons y ys ->
            SCPCons,
            SELet1( // let sub = (f z y) in
              SEAppAtomicGeneral(
                SELocA(0), // f
                Array(
                  SELocA(1), // z
                  SELocS(2) // y
                )
              ),
              SEAppAtomicGeneral(
                FoldL,
                Array(
                  SELocA(0), // f
                  SELocS(1), // (f z y)
                  SELocS(2) // ys
                ))
            )
          )
        )
      )

    private def foldRBody: SExpr =
      SECaseAtomic( // case xs of
        SELocA(2),
        Array( // nil -> z
          SCaseAlt(SCPNil, SELocA(1)),
          SCaseAlt( // cons y ys ->
            SCPCons,
            SELet1General( // let sub = (foldr f z ys) in
              SEAppAtomicGeneral(
                FoldR,
                Array(
                  SELocA(0), // f
                  SELocA(1), // z
                  SELocS(1) // ys
                )),
              SEAppAtomicGeneral(
                SELocA(0), // f
                Array(
                  SELocS(3), // y
                  SELocS(1) // (foldr f z ys)
                )
              )
            )
          )
        )
      )

    private def equalListBody: SExpr =
      SECaseAtomic( // case xs of
        SELocA(1),
        Array(
          SCaseAlt(
            SCPNil, // nil ->
            SECaseAtomic( // case ys of
              SELocA(2),
              Array(
                SCaseAlt(SCPNil, SEValue.True), // nil -> True
                SCaseAlt(SCPDefault, SEValue.False))) // default -> False
          ),
          SCaseAlt( // cons x xss ->
            SCPCons,
            SECaseAtomic( // case ys of
              SELocA(2),
              Array(
                SCaseAlt(SCPNil, SEValue.False), // nil -> False
                SCaseAlt( // cons y yss ->
                  SCPCons,
                  SELet1( // let sub = (f y x) in
                    SEAppAtomicGeneral(
                      SELocA(0), // f
                      Array(
                        SELocS(2), // y
                        SELocS(4))), // x
                    SECaseAtomic( // case (f y x) of
                      SELocS(1),
                      Array(
                        SCaseAlt(
                          SCPPrimCon(PCTrue), // True ->
                          SEAppAtomicGeneral(
                            EqualList,
                            Array(
                              SELocA(0), // f
                              SELocS(2), // yss
                              SELocS(4))) // xss
                        ),
                        SCaseAlt(SCPPrimCon(PCFalse), SEValue.False) // False -> False
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
  }

  final case object AnonymousClosure

  private def prettyPrint(x: Any): String =
    x match {
      case i: Array[Any] => i.mkString("[", ",", "]")
      case i: Array[Int] => i.mkString("[", ",", "]")
      case i: java.util.ArrayList[_] => i.toArray().mkString("[", ",", "]")
      case other: Any => other.toString
    }

}
