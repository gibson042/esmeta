package esmeta.analyzer

import esmeta.{ANALYZE_LOG_DIR, LINE_SEP}
import esmeta.analyzer.domain.*
import esmeta.cfg.*
import esmeta.error.*
import esmeta.ir.Param
import esmeta.util.Appender
import esmeta.util.BaseUtils.*
import esmeta.util.SystemUtils.*

/** specification type analyzer for ECMA-262 */
private class TypeAnalyzer(
  cfg: CFG,
  targets: List[Func],
  ignorePath: Option[String],
  ignoreSet: Set[String],
  log: Boolean = false,
  silent: Boolean = false,
) {

  // initilize analysis with CFG and logging path
  lazy val result: TypeSemantics = withCFG(cfg) {
    withSem(sem) {
      sem.fixpoint
      if (log) logging
      var unusedSet = ignoreSet
      val mismatches = sem.getMismatches.filter {
        case mismatch =>
          val name = mismatch.name
          unusedSet -= name
          !ignoreSet.contains(name)
      }
      if (!mismatches.isEmpty || !unusedSet.isEmpty)
        val app = new Appender
        // show detected type mismatches
        if (!mismatches.isEmpty)
          app :> "* " >> mismatches.size
          app >> " type mismatches are detected."
        // show unused names
        if (!unusedSet.isEmpty)
          app :> "* " >> unusedSet.size
          app >> " names are not used to ignore mismatches."
        mismatches.toList.map(_.toString).sorted.map(app :> _)
        // show help message about how to use the ignorance system
        ignorePath.map(path =>
          app :> "=" * 80
          app :> "To suppress this error message, "
          app >> "add/remove the following names to `" >> path >> "`:"
          mismatches.map(_.name).toList.sorted.map(app :> "  + " >> _)
          unusedSet.toList.sorted.map(app :> "  - " >> _)
          app :> "=" * 80,
        )
        throw TypeCheckFail(if (silent) None else Some(app.toString))
      sem
    }
  }

  // type semantics
  lazy val sem: TypeSemantics = TypeSemantics(initNpMap)

  // all entry node points
  lazy val nps: List[NodePoint[Node]] = for {
    func <- targets
    entry = func.entry
    view = getView(func)
  } yield NodePoint(func, entry, view)

  // get initial abstract states in each node point
  lazy val initNpMap: Map[NodePoint[Node], AbsState] = (for {
    np @ NodePoint(func, _, _) <- nps
    st = getState(func)
  } yield np -> st).toMap

  // get view from a function
  def getView(func: Func): View = View()

  // get return point
  def getState(func: Func): AbsState = func.params.foldLeft(AbsState.Empty) {
    case (st, Param(x, ty, opt, _)) =>
      var v = AbsValue(ty.ty)
      if (opt) v ⊔= AbsValue.absentTop
      st.update(x, v)
  }

  // logging mode
  def logging: Unit = {
    mkdir(ANALYZE_LOG_DIR)
    dumpFile(
      name = "type analysis result",
      data = sem.typesString,
      filename = s"$ANALYZE_LOG_DIR/types",
    )
    dumpFile(
      name = "visiting counter for control points",
      data = sem.getCounter.toList
        .sortBy(_._2)
        .map { case (cp, k) => s"[$k] $cp" }
        .mkString(LINE_SEP),
      filename = s"$ANALYZE_LOG_DIR/counter",
    )
    dumpFile(
      name = "detected type mismatches",
      data = sem.getMismatches.toList
        .map(_.toString)
        .sorted
        .mkString(LINE_SEP),
      filename = s"$ANALYZE_LOG_DIR/mismatches",
    )
  }
}
object TypeAnalyzer:
  // set type domains
  initDomain(
    stateDomain = state.TypeDomain,
    valueDomain = value.TypeDomain,
    retDomain = ret.TypeDomain,
  )

  // no sensitivity
  IR_SENS = false

  // use type refinement
  USE_REFINE = true

  /** perform type analysis for given target functions */
  def apply(
    cfg: CFG,
    target: Option[String] = None,
    ignore: Option[String] = None,
    log: Boolean = false,
    silent: Boolean = false,
  ): TypeSemantics =
    val targets = TypeAnalyzer.getInitTargets(cfg, target, silent)
    if (!silent) println(s"- ${targets.size} functions are initial targets.")
    val ignoreSet = optional {
      readJson[Set[String]](ignore.get)
    }.getOrElse(Set())
    new TypeAnalyzer(
      cfg,
      targets,
      ignore,
      ignoreSet,
      log,
      silent,
    ).result

  /** find initial analysis targets based on a given regex pattern */
  def getInitTargets(
    cfg: CFG,
    target: Option[String],
    silent: Boolean = false,
  ): List[Func] =
    // find all possible initial analysis target functions
    val allFuncs = cfg.funcs.filter(_.isParamTysDefined)
    target.fold(allFuncs)(pattern => {
      val funcs = allFuncs.filter(f => pattern.r.matches(f.name))
      if (!silent && funcs.isEmpty)
        warn(s"failed to find functions matched with the pattern `$pattern`.")
      funcs
    })
