/**
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the
 * names of its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sirix.access;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.sirix.access.conf.ResourceConfiguration.Indexes;
import org.sirix.api.PageReadTrx;
import org.sirix.api.PageWriteTrx;
import org.sirix.cache.Cache;
import org.sirix.cache.RecordPageContainer;
import org.sirix.cache.TransactionLogCache;
import org.sirix.cache.TransactionLogPageCache;
import org.sirix.exception.SirixException;
import org.sirix.exception.SirixIOException;
import org.sirix.io.Writer;
import org.sirix.node.DeletedNode;
import org.sirix.node.Kind;
import org.sirix.node.SirixDeweyID;
import org.sirix.node.delegates.NodeDelegate;
import org.sirix.node.interfaces.Node;
import org.sirix.node.interfaces.Record;
import org.sirix.page.IndirectPage;
import org.sirix.page.NamePage;
import org.sirix.page.PageKind;
import org.sirix.page.PageReference;
import org.sirix.page.RevisionRootPage;
import org.sirix.page.UberPage;
import org.sirix.page.UnorderedKeyValuePage;
import org.sirix.page.interfaces.KeyValuePage;
import org.sirix.page.interfaces.Page;
import org.sirix.settings.Constants;
import org.sirix.settings.Fixed;
import org.sirix.settings.Revisioning;
import org.sirix.utils.NamePageHash;

import com.google.common.base.Optional;

/**
 * <h1>PageWriteTrx</h1>
 * 
 * <p>
 * Implements the {@link PageWriteTrx} interface to provide write capabilities
 * to the persistent storage layer.
 * </p>
 * 
 * @author Marc Kramis, Seabix AG
 * @author Sebastian Graf, University of Konstanz
 * @author Johannes Lichtenberger
 */
final class PageWriteTrxImpl extends AbstractForwardingPageReadTrx implements
		PageWriteTrx<Long, Record, UnorderedKeyValuePage> {

	/** Page writer to serialize. */
	private final Writer mPageWriter;

	/** Persistent BerkeleyDB page log for all page types != NodePage. */
	private final Cache<Long, Page> mPageLog;

	/** Cache to store the changes in this transaction log. */
	private final Cache<Long, RecordPageContainer<UnorderedKeyValuePage>> mNodeLog;

	/** Cache to store path changes in this transaction log. */
	private final Cache<Long, RecordPageContainer<UnorderedKeyValuePage>> mPathLog;

	/** Cache to store text value changes in this transaction log. */
	private final Cache<Long, RecordPageContainer<UnorderedKeyValuePage>> mTextValueLog;

	/** Cache to store attribute value changes in this transaction log. */
	private final Cache<Long, RecordPageContainer<UnorderedKeyValuePage>> mAttributeValueLog;

	/** Last references to the record page, needed for pre/postcondition check. */
	private RecordPageContainer<UnorderedKeyValuePage> mUnorderedRecordPageCon;

	/** Last reference to the actual revRoot. */
	private final RevisionRootPage mNewRoot;

	/** ID for current transaction. */
	private final long mTransactionID;

	/** {@link PageReadTrxImpl} instance. */
	private final PageReadTrxImpl mPageRtx;

	/**
	 * Determines if multiple {@link SynchNodeWriteTrx} are working or just a
	 * single {@link NodeWriteTrxImpl}.
	 */
	private MultipleWriteTrx mMultipleWriteTrx;

	/** Determines if a log must be replayed or not. */
	private Restore mRestore = Restore.NO;

	/** Determines if transaction is closed. */
	private boolean mIsClosed;

	/** Set of indexes. */
	private final EnumSet<Indexes> mIndexes;

	/**
	 * Standard constructor.
	 * 
	 * @param session
	 *          {@link ISessionConfiguration} this page write trx is bound to
	 * @param uberPage
	 *          root of revision
	 * @param writer
	 *          writer where this transaction should write to
	 * @param id
	 *          ID
	 * @param representRev
	 *          revision represent
	 * @param lastStoredRev
	 *          last store revision
	 * @throws AbsTTException
	 *           if an error occurs
	 */
	PageWriteTrxImpl(final @Nonnull SessionImpl session,
			final @Nonnull UberPage uberPage, final @Nonnull Writer writer,
			final @Nonnegative long id, final @Nonnegative int representRev,
			final @Nonnegative int lastStoredRev,
			final @Nonnegative int lastCommitedRev) throws SirixException {
		// Page read trx.
		mPageRtx = new PageReadTrxImpl(session, uberPage, representRev, writer);

		final int revision = uberPage.isBootstrap() ? 0 : representRev + 1;
		mIndexes = session.mResourceConfig.mIndexes;
		mPageLog = new TransactionLogPageCache(session.mResourceConfig.mPath,
				revision, "page", mPageRtx);
		mNodeLog = new TransactionLogCache<>(session.mResourceConfig.mPath,
				revision, "node", mPageRtx);
		if (mIndexes.contains(Indexes.PATH)) {
			mPathLog = new TransactionLogCache<>(session.mResourceConfig.mPath,
					revision, "path", mPageRtx);
		} else {
			mPathLog = null;
		}
		if (mIndexes.contains(Indexes.TEXT_VALUE)) {
			mTextValueLog = new TransactionLogCache<>(session.mResourceConfig.mPath,
					revision, "textValue", mPageRtx);
		} else {
			mTextValueLog = null;
		}
		if (mIndexes.contains(Indexes.ATTRIBUTE_VALUE)) {
			mAttributeValueLog = new TransactionLogCache<>(
					session.mResourceConfig.mPath, revision, "attributeValue", mPageRtx);
		} else {
			mAttributeValueLog = null;
		}
		mPageWriter = writer;
		mTransactionID = id;

		final RevisionRootPage lastCommitedRoot = preparePreviousRevisionRootPage(
				representRev, lastCommitedRev);
		mNewRoot = preparePreviousRevisionRootPage(representRev, lastStoredRev);
		mNewRoot.setMaxNodeKey(lastCommitedRoot.getMaxNodeKey());

		final Set<Indexes> indexes = session.getResourceConfig().mIndexes;
		if (indexes.contains(Indexes.PATH)) {
			mNewRoot.setMaxPathNodeKey(lastCommitedRoot.getMaxPathNodeKey());
		}
		if (indexes.contains(Indexes.TEXT_VALUE)) {
			mNewRoot
					.setMaxTextValueNodeKey(lastCommitedRoot.getMaxTextValueNodeKey());
		}
		if (indexes.contains(Indexes.ATTRIBUTE_VALUE)) {
			mNewRoot.setMaxAttributeValueNodeKey(lastCommitedRoot
					.getMaxAttributeValueNodeKey());
		}
	}

	@Override
	public void restore(final @Nonnull Restore restore) {
		mRestore = checkNotNull(restore);
	}

	@Override
	public Record prepareEntryForModification(final @Nonnegative Long recordKey,
			final @Nonnull PageKind page,
			final @Nonnull Optional<UnorderedKeyValuePage> keyValuePage)
			throws SirixIOException {
		mPageRtx.assertNotClosed();
		if (recordKey < 0) {
			throw new IllegalArgumentException("recordKey must be >= 0!");
		}
		if (mUnorderedRecordPageCon != null) {
			throw new IllegalStateException(
					"Another record page container is currently in the cache for updates!");
		}

		final long recordPageKey = mPageRtx.pageKey(recordKey);
		prepareRecordPage(recordPageKey, page);

		Record record = mUnorderedRecordPageCon.getModified().getValue(recordKey);
		if (record == null) {
			final Record oldRecord = mUnorderedRecordPageCon.getComplete().getValue(
					recordKey);
			if (oldRecord == null) {
				throw new SirixIOException("Cannot retrieve record from cache!");
			}
			record = oldRecord;
			mUnorderedRecordPageCon.getModified().setEntry(record.getNodeKey(),
					record);
		}

		return record;
	}

	@Override
	public void finishEntryModification(final @Nonnull Long nodeKey,
			final @Nonnull PageKind page) {
		mPageRtx.assertNotClosed();
		final long nodePageKey = mPageRtx.pageKey(nodeKey);
		if (mUnorderedRecordPageCon == null
				|| (mNodeLog.get(nodePageKey)
						.equals(RecordPageContainer.EMPTY_INSTANCE)
						&& mPathLog.get(nodePageKey).equals(
								RecordPageContainer.EMPTY_INSTANCE)
						&& mTextValueLog.get(nodePageKey).equals(
								RecordPageContainer.EMPTY_INSTANCE) && mAttributeValueLog.get(
						nodePageKey).equals(RecordPageContainer.EMPTY_INSTANCE))) {
			throw new IllegalStateException();
		}

		switch (page) {
		case NODEPAGE:
			mNodeLog.put(nodePageKey, mUnorderedRecordPageCon);
			break;
		case PATHSUMMARYPAGE:
			mPathLog.put(nodePageKey, mUnorderedRecordPageCon);
			break;
		case TEXTVALUEPAGE:
			mTextValueLog.put(nodePageKey, mUnorderedRecordPageCon);
			break;
		case ATTRIBUTEVALUEPAGE:
			mAttributeValueLog.put(nodePageKey, mUnorderedRecordPageCon);
			break;
		default:
			throw new IllegalStateException();
		}

		mUnorderedRecordPageCon = null;
	}

	@Override
	public Record createEntry(final @Nonnull Long key,
			final @Nonnull Record record, final @Nonnull PageKind pageKind,
			final @Nonnull Optional<UnorderedKeyValuePage> keyValuePage)
			throws SirixIOException {
		mPageRtx.assertNotClosed();
		// Allocate record key and increment record count.
		long recordKey;
		switch (pageKind) {
		case NODEPAGE:
			mNewRoot.incrementMaxNodeKey();
			recordKey = mNewRoot.getMaxNodeKey();
			break;
		case PATHSUMMARYPAGE:
			mNewRoot.incrementMaxPathNodeKey();
			recordKey = mNewRoot.getMaxPathNodeKey();
			break;
		case TEXTVALUEPAGE:
			mNewRoot.incrementMaxTextValueNodeKey();
			recordKey = mNewRoot.getMaxTextValueNodeKey();
			break;
		case ATTRIBUTEVALUEPAGE:
			mNewRoot.incrementMaxAttributeValueNodeKey();
			recordKey = mNewRoot.getMaxAttributeValueNodeKey();
			break;
		default:
			throw new IllegalStateException();
		}

		final long recordPageKey = mPageRtx.pageKey(recordKey);
		prepareRecordPage(recordPageKey, pageKind);
		final KeyValuePage<Long, Record> modified = mUnorderedRecordPageCon
				.getModified();
		modified.setEntry(record.getNodeKey(), record);
		finishEntryModification(record.getNodeKey(), pageKind);
		return record;
	}

	@Override
	public void removeEntry(@Nonnull final Long recordKey,
			@Nonnull final PageKind pageKind,
			final @Nonnull Optional<UnorderedKeyValuePage> keyValuePage)
			throws SirixIOException {
		mPageRtx.assertNotClosed();
		final long nodePageKey = mPageRtx.pageKey(recordKey);
		prepareRecordPage(nodePageKey, pageKind);
		final Optional<Record> node = getRecord(recordKey, pageKind);
		if (node.isPresent()) {
			final Record nodeToDel = node.get();
			final Node delNode = new DeletedNode(new NodeDelegate(
					nodeToDel.getNodeKey(), -1, -1, -1, Optional.<SirixDeweyID> absent()));
			mUnorderedRecordPageCon.getModified().setEntry(delNode.getNodeKey(),
					delNode);
			mUnorderedRecordPageCon.getComplete().setEntry(delNode.getNodeKey(),
					delNode);
			finishEntryModification(recordKey, pageKind);
		} else {
			throw new IllegalStateException("Node not found!");
		}
	}

	@Override
	public Optional<Record> getRecord(final @Nonnegative long recordKey,
			final @Nonnull PageKind pageKind) throws SirixIOException {
		mPageRtx.assertNotClosed();
		checkArgument(recordKey >= Fixed.NULL_NODE_KEY.getStandardProperty());
		checkNotNull(pageKind);
		// Calculate page.
		final long recordPageKey = mPageRtx.pageKey(recordKey);

		final RecordPageContainer<UnorderedKeyValuePage> pageCont = getUnorderedRecordPageContainer(
				pageKind, recordPageKey);
		if (pageCont.equals(RecordPageContainer.EMPTY_INSTANCE)) {
			return mPageRtx.getRecord(recordKey, pageKind);
		} else {
			Record node = pageCont.getModified().getValue(recordKey);
			if (node == null) {
				node = pageCont.getComplete().getValue(recordKey);
			}
			return mPageRtx.checkItemIfDeleted(node);
		}
	}

	/**
	 * Get the page container.
	 * 
	 * @param pageKind
	 *          the kind of page
	 * @param recordPageKey
	 *          the record page key
	 * @return the {@link RecordPageContainer} instance from the write ahead log
	 */
	private RecordPageContainer<UnorderedKeyValuePage> getUnorderedRecordPageContainer(
			final @Nullable PageKind pageKind, final @Nonnegative long recordPageKey) {
		if (pageKind != null) {
			switch (pageKind) {
			case NODEPAGE:
				return mNodeLog.get(recordPageKey);
			case PATHSUMMARYPAGE:
				return mPathLog.get(recordPageKey);
			case TEXTVALUEPAGE:
				return mTextValueLog.get(recordPageKey);
			case ATTRIBUTEVALUEPAGE:
				return mAttributeValueLog.get(recordPageKey);
			default:
				throw new IllegalStateException();
			}
		}
		return RecordPageContainer.<UnorderedKeyValuePage> emptyInstance();
	}

	// /**
	// * Remove the page container.
	// *
	// * @param page
	// * the kind of page
	// * @param nodePageKey
	// * the node page key
	// */
	// private void removePageContainer(final @Nonnull PageKind page,
	// final @Nonnegative long nodePageKey) {
	// switch (page) {
	// case NODEPAGE:
	// mNodeLog.remove(nodePageKey);
	// break;
	// case PATHSUMMARYPAGE:
	// mPathLog.remove(nodePageKey);
	// break;
	// case VALUEPAGE:
	// mValueLog.remove(nodePageKey);
	// default:
	// mPageLog.remove(nodePageKey);
	// }
	// }

	@Override
	public String getName(final int nameKey, final @Nonnull Kind nodeKind) {
		mPageRtx.assertNotClosed();
		final NamePage currentNamePage = (NamePage) mNewRoot.getNamePageReference()
				.getPage();
		// if currentNamePage == null -> state was commited and no prepareNodepage
		// was invoked yet
		return (currentNamePage == null || currentNamePage.getName(nameKey,
				nodeKind) == null) ? mPageRtx.getName(nameKey, nodeKind)
				: currentNamePage.getName(nameKey, nodeKind);
	}

	@Override
	public int createNameKey(final @Nullable String name,
			final @Nonnull Kind nodeKind) throws SirixIOException {
		mPageRtx.assertNotClosed();
		checkNotNull(nodeKind);
		final String string = (name == null ? "" : name);
		final int nameKey = NamePageHash.generateHashForString(string);
		final NamePage namePage = (NamePage) mNewRoot.getNamePageReference()
				.getPage();
		namePage.setName(nameKey, string, nodeKind);
		return nameKey;
	}

	@Override
	public void commit(final @Nullable PageReference reference)
			throws SirixException {
		Page page = null;

		// If reference is not null, get one from the persistent storage.
		if (reference != null) {
			// First, try to get one from the transaction log.
			final long recordPageKey = reference.getKeyValuePageKey();
			final PageKind pageKind = reference.getPageKind();
			RecordPageContainer<? extends KeyValuePage<?, ?>> cont = null;
			if (recordPageKey != -1) {
				switch (pageKind) {
				case TEXTVALUEPAGE:
				case ATTRIBUTEVALUEPAGE:
				case NODEPAGE:
				case PATHSUMMARYPAGE:
					cont = getUnorderedRecordPageContainer(pageKind, recordPageKey);
				default:

				}
			}
			if (cont != null) {
				page = cont.getModified();
			}

			// If none is in the log.
			if (page == null) {
				// Test if one is instantiated, if so, get
				// the one from the reference.
				page = reference.getPage();

				if (reference.getKeyValuePageKey() != -1 && page == null) {
					switch (pageKind) {
					case TEXTVALUEPAGE:
					case ATTRIBUTEVALUEPAGE:
					case NODEPAGE:
					case PATHSUMMARYPAGE:
						if ((mPageRtx.getRevisionNumber()
								% mPageRtx.mSession.mResourceConfig.mRevisionsToRestore == 0)
								&& mPageRtx.mSession.mResourceConfig.mRevisionKind != Revisioning.FULL) {
							// Revision to commit is a full dump => get the full page and dump
							// it.
							page = mPageRtx
									.<Long, Record, UnorderedKeyValuePage> getRecordPageContainer(
											recordPageKey, pageKind).getComplete();
						}
					default:
					}

					if (page != null && !page.isDirty()) {
						// Only write dirty pages which have been modified since the
						// latest full dump (checked in the versioning algorithms through
						// setting the dirty flag).
						page = null;
					}
				}
				if (page == null) {
					// Then try to get one from the page transaction log (indirect pages
					// forming a tree below revision root pages).
					final long key = reference.getKey();
					if (recordPageKey == -1 && key != Constants.NULL_ID
							&& reference.getPageKind() != PageKind.UBERPAGE) {
						page = mPageLog.get(key);

						if ((mPageRtx.getRevisionNumber()
								% mPageRtx.mSession.mResourceConfig.mRevisionsToRestore == 0)
								&& mPageRtx.mSession.mResourceConfig.mRevisionKind != Revisioning.FULL) {
							/*
							 * Write the whole indirect page tree if it's a full dump,
							 * otherwise record pages which have to be emitted might not be
							 * adressable (the pages from earlier versions would still be
							 * reachable).
							 */
							page = mPageRtx.getFromPageCache(key);

							if (page instanceof IndirectPage) {
								page.setDirty(true);
							} else {
								assert page == null;
							}
						}
					}
					if (page == null) {
						return;
					}
					assert page.isDirty();
				}

				// Revision to commit is not a full dump => return immediately.
				if (!page.isDirty()
						&& ((page.getRevision()
								% mPageRtx.mSession.mResourceConfig.mRevisionsToRestore != 0) || mPageRtx.mSession.mResourceConfig.mRevisionKind == Revisioning.FULL)) {
					return;
				}
			}

			reference.setPage(page);

			// Recursively commit indirectely referenced pages and then
			// write self.
			page.commit(this);
			mPageWriter.write(reference);

			// Remove from transaction log.
			// if (pReference.getPageKind() != null) {
			// removePageContainer(pReference.getPageKind(), nodePageKey);
			// }

			// Remove page reference.
			reference.setPage(null);

			// Afterwards synchronize all logs since the changes must be
			// written to the transaction log as well.
			if (mMultipleWriteTrx == MultipleWriteTrx.YES && cont != null) {
				// mPageRtx.mSession.syncLogs(cont, mTransactionID,
				// reference.getPageKind());
			}
		}
	}

	@Override
	public UberPage commit(final @Nonnull MultipleWriteTrx multipleWriteTrx)
			throws SirixException {
		mPageRtx.assertNotClosed();
		mPageRtx.mSession.mCommitLock.lock();
		mMultipleWriteTrx = checkNotNull(multipleWriteTrx);

		// Forcefully flush write-ahead transaction logs to persistent storage.
		// Make
		// this optional!
		// mNodeLog.toSecondCache();
		// mPathLog.toSecondCache();
		// mValueLog.toSecondCache();
		// mPageLog.toSecondCache();

		final PageReference uberPageReference = new PageReference();
		final UberPage uberPage = getUberPage();
		uberPageReference.setPage(uberPage);
		uberPageReference.setPageKind(PageKind.UBERPAGE);

		// Recursively write indirectely referenced pages.
		uberPage.commit(this);

		uberPageReference.setPage(uberPage);
		mPageWriter.writeFirstReference(uberPageReference);
		uberPageReference.setPage(null);

		mPageRtx.mSession.waitForFinishedSync(mTransactionID);
		mPageRtx.mSession.mCommitLock.unlock();
		return uberPage;
	}

	@Override
	public void close() throws SirixIOException {
		if (!mIsClosed) {
			mPageRtx.assertNotClosed();
			mPageRtx.clearCaches();
			mPageRtx.closeCaches();
			closeCaches();
			mPageWriter.close();
			mIsClosed = true;
		}
	}

	@Override
	public void clearCaches() {
		mPageRtx.assertNotClosed();
		mPageRtx.clearCaches();
		mPageLog.clear();
		mNodeLog.clear();

		if (mPathLog != null) {
			mPathLog.clear();
		}
		if (mTextValueLog != null) {
			mTextValueLog.clear();
		}
		if (mAttributeValueLog != null) {
			mAttributeValueLog.clear();
		}
	}

	@Override
	public void closeCaches() {
		mPageRtx.assertNotClosed();
		mPageRtx.closeCaches();
		mNodeLog.close();
		mPageLog.close();
		if (mPathLog != null) {
			mPathLog.close();
		}
		if (mTextValueLog != null) {
			mTextValueLog.close();
		}
		if (mAttributeValueLog != null) {
			mAttributeValueLog.close();
		}
	}

	/**
	 * Prepare indirect page, that is getting the referenced indirect page or a
	 * new page.
	 * 
	 * @param reference
	 *          {@link PageReference} to get the indirect page from or to create a
	 *          new one
	 * @return {@link IndirectPage} reference
	 * @throws SirixIOException
	 *           if an I/O error occurs
	 */
	private IndirectPage prepareIndirectPage(
			final @Nonnull PageReference reference) throws SirixIOException {
		IndirectPage page = (IndirectPage) reference.getPage();
		if (page == null) {
			if (reference.getKey() == Constants.NULL_ID) {
				page = new IndirectPage(getUberPage().getRevision());
			} else {
				// Should never be null, otherwise
				// dereferenceIndirectPage(PageReference) fails.
				final IndirectPage indirectPage = mPageRtx
						.dereferenceIndirectPage(reference);
				page = new IndirectPage(indirectPage, mNewRoot.getRevision() + 1);
			}
			reference.setPage(page);
		}
		reference.setPageKind(PageKind.INDIRECTPAGE);
		return page;
	}

	/**
	 * Prepare record page.
	 * 
	 * @param recordPageKey
	 *          the key of the record page
	 * @param pageKind
	 *          the kind of page (used to determine the right subtree)
	 * @throws SirixIOException
	 *           if an I/O error occurs
	 */
	private void prepareRecordPage(final @Nonnegative long recordPageKey,
			final @Nonnull PageKind pageKind) throws SirixIOException {
		assert recordPageKey >= 0;
		assert pageKind != null;
		RecordPageContainer<UnorderedKeyValuePage> cont = getUnorderedRecordPageContainer(
				pageKind, recordPageKey);
		if (cont.equals(RecordPageContainer.EMPTY_INSTANCE)) {
			// Indirect reference.
			final PageReference reference = prepareLeafOfTree(
					mPageRtx.getPageReference(mNewRoot, pageKind), recordPageKey,
					pageKind);
			final UnorderedKeyValuePage recordPage = (UnorderedKeyValuePage) reference
					.getPage();
			if (recordPage == null) {
				if (reference.getKey() == Constants.NULL_ID) {
					cont = new RecordPageContainer<>(new UnorderedKeyValuePage(
							recordPageKey, Constants.UBP_ROOT_REVISION_NUMBER, mPageRtx));
				} else {
					cont = dereferenceRecordPageForModification(recordPageKey, pageKind);
				}
			} else {
				cont = new RecordPageContainer<>(recordPage);
			}

			assert cont != null;
			reference.setKeyValuePageKey(recordPageKey);
			reference.setPageKind(pageKind);

			switch (pageKind) {
			case NODEPAGE:
				mNodeLog.put(recordPageKey, cont);
				break;
			case PATHSUMMARYPAGE:
				mPathLog.put(recordPageKey, cont);
				break;
			case TEXTVALUEPAGE:
				mTextValueLog.put(recordPageKey, cont);
				break;
			case ATTRIBUTEVALUEPAGE:
				mAttributeValueLog.put(recordPageKey, cont);
				break;
			default:
				throw new IllegalStateException("Page kind not known!");
			}
		}
		mUnorderedRecordPageCon = cont;
	}

	/**
	 * Prepare the previous revision root page and retrieve the next
	 * {@link RevisionRootPage}.
	 * 
	 * @param baseRevision
	 *          base revision
	 * @param representRevision
	 *          the revision to represent
	 * @return new {@link RevisionRootPage} instance
	 * @throws SirixIOException
	 *           if an I/O error occurs
	 */
	private RevisionRootPage preparePreviousRevisionRootPage(
			final @Nonnegative int baseRevision,
			final @Nonnegative int representRevision) throws SirixIOException {
		if (getUberPage().isBootstrap()) {
			return mPageRtx.loadRevRoot(baseRevision);
		} else {
			// Prepare revision root nodePageReference.
			final RevisionRootPage revisionRootPage = new RevisionRootPage(
					mPageRtx.loadRevRoot(baseRevision), representRevision + 1);

			// Prepare indirect tree to hold reference to prepared revision root
			// nodePageReference.
			final PageReference revisionRootPageReference = prepareLeafOfTree(
					getUberPage().getIndirectPageReference(), getUberPage()
							.getRevisionNumber(), PageKind.UBERPAGE);

			// Link the prepared revision root nodePageReference with the
			// prepared indirect tree.
			revisionRootPageReference.setPage(revisionRootPage);
			revisionRootPageReference.setPageKind(PageKind.REVISIONROOTPAGE);

			// Return prepared revision root nodePageReference.
			return revisionRootPage;
		}
	}

	/**
	 * Prepare the leaf of a tree, namely the reference to a
	 * {@link UnorderedKeyValuePage}.
	 * 
	 * @param startReference
	 *          start reference
	 * @param key
	 *          page key to lookup
	 * @return {@link PageReference} instance pointing to the right
	 *         {@link UnorderedKeyValuePage} with the {@code pKey}
	 * @throws SirixIOException
	 *           if an I/O error occured
	 */
	private PageReference prepareLeafOfTree(
			final @Nonnull PageReference startReference, final @Nonnegative long key,
			final @Nonnull PageKind pageKind) throws SirixIOException {
		// Initial state pointing to the indirect nodePageReference of level 0.
		PageReference reference = startReference;
		int offset = 0;
		long levelKey = key;
		final int[] inpLevelPageCountExp = mPageRtx.getUberPage().getPageCountExp(
				pageKind);

		// Iterate through all levels.
		for (int level = 0, height = inpLevelPageCountExp.length; level < height; level++) {
			offset = (int) (levelKey >> inpLevelPageCountExp[level]);
			levelKey -= offset << inpLevelPageCountExp[level];
			final IndirectPage page = prepareIndirectPage(reference);
			page.setDirty(true);
			mPageLog.put(reference.getKey(), page);
			reference = page.getReference(offset);
		}

		// Return reference to leaf of indirect tree.
		return reference;
	}

	/**
	 * Dereference record page reference.
	 * 
	 * @param recordPageKey
	 *          key of record page
	 * @return dereferenced page
	 * @throws SirixIOException
	 *           if an I/O error occurs
	 */
	private RecordPageContainer<UnorderedKeyValuePage> dereferenceRecordPageForModification(
			final @Nonnegative long recordPageKey, final @Nonnull PageKind page)
			throws SirixIOException {
		final List<UnorderedKeyValuePage> revs = mPageRtx
				.<Long, Record, UnorderedKeyValuePage> getSnapshotPages(recordPageKey,
						page);
		final Revisioning revisioning = mPageRtx.mSession.mResourceConfig.mRevisionKind;
		final int mileStoneRevision = mPageRtx.mSession.mResourceConfig.mRevisionsToRestore;
		return revisioning.combineRecordPagesForModification(revs,
				mileStoneRevision, mPageRtx);
	}

	@Override
	public RevisionRootPage getActualRevisionRootPage() {
		return mNewRoot;
	}

	/**
	 * Updating a container in this {@link PageWriteTrxImpl}.
	 * 
	 * @param pCont
	 *          {@link RecordPageContainer} reference to be updated
	 * @param page
	 *          page for which the
	 */
	@Override
	public void updateDataContainer(
			@Nonnull RecordPageContainer<UnorderedKeyValuePage> container,
			final @Nonnull PageKind page) {
		final long nodePageKey = container.getComplete().getPageKey();
		switch (page) {
		case PATHSUMMARYPAGE:
			container = mPathLog.get(nodePageKey);
			break;
		case TEXTVALUEPAGE:
			container = mTextValueLog.get(nodePageKey);
			break;
		case ATTRIBUTEVALUEPAGE:
			container = mAttributeValueLog.get(nodePageKey);
			break;
		case NODEPAGE:
			container = mNodeLog.get(nodePageKey);
			break;
		default:
			throw new IllegalStateException("page kind not known!");
		}

		// // Merge containers.
		// final RecordPageImpl modified = container.getModified();
		// final RecordPageImpl otherModified = container.getModified();
		// synchronized (modified) {
		// for (final Entry<Long, Record> entry : otherModified.entrySet()) {
		// if (modified.getRecord(entry.getKey()) == null) {
		// modified.setRecord(entry.getValue());
		// }
		// }
		// }
	}

	@Override
	protected PageReadTrx delegate() {
		return mPageRtx;
	}

	@Override
	public PageReadTrx getPageReadTrx() {
		return mPageRtx;
	}
}