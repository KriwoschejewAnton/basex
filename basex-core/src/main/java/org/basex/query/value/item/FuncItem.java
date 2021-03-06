package org.basex.query.value.item;

import static org.basex.query.QueryError.*;
import static org.basex.query.QueryText.*;

import java.util.*;

import org.basex.query.*;
import org.basex.query.ann.*;
import org.basex.query.expr.*;
import org.basex.query.expr.gflwor.*;
import org.basex.query.func.*;
import org.basex.query.scope.*;
import org.basex.query.util.*;
import org.basex.query.util.collation.*;
import org.basex.query.util.list.*;
import org.basex.query.value.*;
import org.basex.query.value.type.*;
import org.basex.query.var.*;
import org.basex.util.*;
import org.basex.util.hash.*;

/**
 * Function item.
 *
 * @author BaseX Team 2005-20, BSD License
 * @author Leo Woerteler
 */
public final class FuncItem extends FItem implements Scope {
  /** Static context. */
  public final StaticContext sc;
  /** Function expression. */
  private final Expr expr;
  /** Function name (may be {@code null}). */
  private final QNm name;
  /** Formal parameters. */
  private final Var[] params;
  /** Query focus. */
  private final QueryFocus focus;
  /** Size of the stack frame needed for this function. */
  private final int stackSize;
  /** Input information. */
  private final InputInfo info;

  /**
   * Constructor.
   * @param sc static context
   * @param anns function annotations
   * @param name function name (may be {@code null})
   * @param params formal parameters
   * @param type function type
   * @param expr function body
   * @param stackSize stack-frame size
   * @param info input info
   */
  public FuncItem(final StaticContext sc, final AnnList anns, final QNm name, final Var[] params,
      final FuncType type, final Expr expr, final int stackSize, final InputInfo info) {
    this(sc, anns, name, params, type, expr, new QueryFocus(), stackSize, info);
  }

  /**
   * Constructor.
   * @param sc static context
   * @param anns function annotations
   * @param name function name (may be {@code null})
   * @param params formal parameters
   * @param type function type
   * @param expr function body
   * @param focus query focus
   * @param stackSize stack-frame size
   * @param info input info
   */
  public FuncItem(final StaticContext sc, final AnnList anns, final QNm name, final Var[] params,
      final FuncType type, final Expr expr, final QueryFocus focus, final int stackSize,
      final InputInfo info) {

    super(type, anns);
    this.name = name;
    this.params = params;
    this.expr = expr;
    this.stackSize = stackSize;
    this.sc = sc;
    this.focus = focus;
    this.info = info;
  }

  @Override
  public int arity() {
    return params.length;
  }

  @Override
  public QNm funcName() {
    return name;
  }

  @Override
  public QNm paramName(final int ps) {
    return params[ps].name;
  }

  @Override
  public int stackFrameSize() {
    return stackSize;
  }

  @Override
  public Value invValue(final QueryContext qc, final InputInfo ii, final Value... args)
      throws QueryException {

    // bind variables and cache context
    final QueryFocus qf = qc.focus;
    qc.focus = focus;
    try {
      final int pl = params.length;
      for(int p = 0; p < pl; p++) qc.set(params[p], args[p]);
      return expr.value(qc);
    } finally {
      qc.focus = qf;
    }
  }

  @Override
  public Item invItem(final QueryContext qc, final InputInfo ii, final Value... args)
      throws QueryException {
    // bind variables and cache context
    final QueryFocus qf = qc.focus;
    qc.focus = focus;
    try {
      final int pl = params.length;
      for(int p = 0; p < pl; p++) qc.set(params[p], args[p]);
      return expr.item(qc, ii);
    } finally {
      qc.focus = qf;
    }
  }

  @Override
  public Value invokeValue(final QueryContext qc, final InputInfo ii, final Value... args)
      throws QueryException {
    return FuncCall.invoke(this, args, false, qc, info);
  }

  @Override
  public Item invokeItem(final QueryContext qc, final InputInfo ii, final Value... args)
      throws QueryException {
    return (Item) FuncCall.invoke(this, args, true, qc, info);
  }

  @Override
  public FuncItem coerceTo(final FuncType ft, final QueryContext qc, final InputInfo ii,
      final boolean optimize) throws QueryException {

    final int pl = params.length, al = ft.argTypes.length;
    if(pl != ft.argTypes.length) throw FUNARITY_X_X.get(info, arguments(pl), al);

    // optimize: continue with coercion if current type is only an instance of new type
    final FuncType tp = funcType();
    if(optimize ? tp.eq(ft) : tp.instanceOf(ft)) return this;

    // create new compilation context and variable scope
    final CompileContext cc = new CompileContext(qc);
    final VarScope vs = new VarScope(sc);
    final Var[] vars = new Var[pl];
    final Expr[] args = new Expr[pl];
    for(int p = pl; p-- > 0;) {
      vars[p] = vs.addNew(params[p].name, ft.argTypes[p], true, qc, ii);
      args[p] = new VarRef(ii, vars[p]).optimize(cc);
    }
    cc.pushScope(vs);

    // create new function call (will immediately be inlined/simplified when being optimized)
    final boolean updating = anns.contains(Annotation.UPDATING) || expr.has(Flag.UPD);
    Expr body = new DynFuncCall(ii, sc, updating, false, this, args);
    if(optimize) body = body.optimize(cc);

    // add type check if return types differ
    if(!tp.declType.instanceOf(ft.declType)) {
      body = new TypeCheck(sc, ii, body, ft.declType, true);
      if(optimize) body = body.optimize(cc);
    }

    // adopt type of optimized body if it is more specific than passed on type
    final SeqType st = body.seqType();
    final FuncType newType = optimize && st.refinable(ft.declType) ?
      FuncType.get(st, ft.argTypes) : ft;

    body.markTailCalls(null);
    return new FuncItem(sc, anns, name, vars, newType, body, vs.stackSize(), ii);
  }

  @Override
  public boolean accept(final ASTVisitor visitor) {
    return visitor.funcItem(this);
  }

  @Override
  public boolean visit(final ASTVisitor visitor) {
    for(final Var param : params) {
      if(!visitor.declared(param)) return false;
    }
    return expr.accept(visitor);
  }

  @Override
  public void comp(final CompileContext cc) {
    // nothing to do here
  }

  @Override
  public boolean compiled() {
    return true;
  }

  @Override
  public Object toJava() {
    return this;
  }

  @Override
  public Expr inline(final Expr[] exprs, final CompileContext cc) throws QueryException {
    if(!StaticFunc.inline(cc, anns, expr) || expr.has(Flag.CTX)) return null;
    cc.info(OPTINLINE_X, this);

    // create let bindings for all variables
    final LinkedList<Clause> clauses = new LinkedList<>();
    final IntObjMap<Var> vm = new IntObjMap<>();
    final int pl = params.length;
    for(int p = 0; p < pl; p++) {
      clauses.add(new Let(cc.copy(params[p], vm), exprs[p]).optimize(cc));
    }

    // create the return clause
    final Expr rtrn = expr.copy(cc, vm).optimize(cc);
    rtrn.accept(new InlineVisitor());
    return clauses.isEmpty() ? rtrn : new GFLWOR(info, clauses, rtrn).optimize(cc);
  }

  @Override
  public Value atomValue(final QueryContext qc, final InputInfo ii) throws QueryException {
    throw FIATOM_X.get(info, type);
  }

  @Override
  public Item atomItem(final QueryContext qc, final InputInfo ii) throws QueryException {
    throw FIATOM_X.get(info, type);
  }

  @Override
  public Item materialize(final QueryContext qc, final boolean copy) {
    return null;
  }

  @Override
  public boolean deep(final Item item, final Collation coll, final InputInfo ii)
      throws QueryException {
    throw FICMP_X.get(info, type);
  }

  @Override
  public boolean isVacuousBody() {
    final SeqType st = expr.seqType();
    return st != null && st.zero() && !expr.has(Flag.UPD);
  }

  @Override
  public boolean equals(final Object obj) {
    return this == obj;
  }

  @Override
  public String description() {
    return FUNCTION + ' ' + ITEM;
  }

  @Override
  public void plan(final QueryPlan plan) {
    plan.add(plan.create(this, NAME, name == null ? null : name.prefixId()), params, expr);
  }

  @Override
  public String toErrorString() {
    return toString(true);
  }

  @Override
  public String toString() {
    return toString(false);
  }

  /**
   * Returns a string representation.
   * @param error error flag
   * @return string
   */
  private String toString(final boolean error) {
    final TokenBuilder tb = new TokenBuilder();
    if(name != null) tb.add("(: ").add(name.prefixId()).add("#").addInt(arity()).add(" :) ");
    tb.add(anns).add(FUNCTION).add('(');
    final int pl = params.length;
    for(int p = 0; p < pl; p++) {
      if(p != 0) tb.add(", ");
      tb.add(error ? params[p].toErrorString() : params[p]);
    }
    tb.add(')').add(" as ").add(funcType().declType).add(" { ").add(expr).add(" }");
    return tb.toString();
  }

  /**
   * A visitor for checking inlined expressions.
   *
   * @author BaseX Team 2005-20, BSD License
   * @author Leo Woerteler
   */
  private class InlineVisitor extends ASTVisitor {
    @Override
    public boolean inlineFunc(final Scope scope) {
      return scope.visit(this);
    }

    @Override
    public boolean dynFuncCall(final DynFuncCall call) {
      call.markInlined(FuncItem.this);
      return true;
    }
  }
}
