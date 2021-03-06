package org.basex.query.func.session;

import org.basex.query.*;
import org.basex.query.value.item.*;
import org.basex.util.*;

/**
 * Function implementation.
 *
 * @author BaseX Team 2005-20, BSD License
 * @author Christian Gruen
 */
public final class SessionCreated extends SessionFn {
  @Override
  public Item item(final QueryContext qc, final InputInfo ii) throws QueryException {
    return session(qc).created();
  }
}
