package io.sirix.query.function.xml.index.find;

import io.sirix.query.function.xml.XMLFun;
import io.sirix.query.node.XmlDBNode;
import io.brackit.query.QueryContext;
import io.brackit.query.QueryException;
import io.brackit.query.atomic.Int32;
import io.brackit.query.atomic.QNm;
import io.brackit.query.atomic.Str;
import io.brackit.query.function.AbstractFunction;
import io.brackit.query.jdm.Sequence;
import io.brackit.query.jdm.Signature;
import io.brackit.query.module.StaticContext;
import io.brackit.query.util.path.Path;
import io.sirix.access.trx.node.xml.XmlIndexController;
import io.sirix.api.xml.XmlNodeReadOnlyTrx;
import io.sirix.index.IndexDef;

import java.util.Optional;

/**
 * <p>
 * Function for finding a path index. If successful, this function returns the path-index number.
 * Otherwise it returns -1.
 * </p>
 * <p>
 * Supported signatures are:
 * </p>
 * <ul>
 * <li><code>xml:find-path-index($doc as node(), $path as xs:string) as xs:int</code></li>
 * </ul>
 *
 * @author Johannes Lichtenberger
 *
 */
public final class FindPathIndex extends AbstractFunction {

  /** CAS index function name. */
  public final static QNm FIND_PATH_INDEX = new QNm(XMLFun.XML_NSURI, XMLFun.XML_PREFIX, "find-path-index");

  /**
   * Constructor.
   *
   * @param name the name of the function
   * @param signature the signature of the function
   */
  public FindPathIndex(QNm name, Signature signature) {
    super(name, signature, true);
  }

  @Override
  public Sequence execute(StaticContext sctx, QueryContext ctx, Sequence[] args) {
    final XmlDBNode doc = (XmlDBNode) args[0];
    final XmlNodeReadOnlyTrx rtx = doc.getTrx();
    final XmlIndexController controller = rtx.getResourceSession().getRtxIndexController(rtx.getRevisionNumber());

    if (controller == null) {
      throw new QueryException(new QNm("Document not found: " + ((Str) args[1]).stringValue()));
    }

    final Path<QNm> path = Path.parse(((Str) args[1]).stringValue());
    final Optional<IndexDef> indexDef = controller.getIndexes().findPathIndex(path);

    return indexDef.map(def -> new Int32(def.getID())).orElseGet(() -> new Int32(-1));
  }
}
