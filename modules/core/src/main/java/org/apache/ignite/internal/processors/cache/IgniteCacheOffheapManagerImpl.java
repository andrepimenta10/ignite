/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import javax.cache.Cache;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.pagemem.FullPageId;
import org.apache.ignite.internal.pagemem.Page;
import org.apache.ignite.internal.pagemem.PageIdUtils;
import org.apache.ignite.internal.pagemem.PageMemory;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.database.CacheDataRow;
import org.apache.ignite.internal.processors.cache.database.IgniteCacheDatabaseSharedManager;
import org.apache.ignite.internal.processors.cache.database.RootPage;
import org.apache.ignite.internal.processors.cache.database.RowStore;
import org.apache.ignite.internal.processors.cache.database.freelist.FreeList;
import org.apache.ignite.internal.processors.cache.database.tree.BPlusTree;
import org.apache.ignite.internal.processors.cache.database.tree.io.BPlusIO;
import org.apache.ignite.internal.processors.cache.database.tree.io.BPlusInnerIO;
import org.apache.ignite.internal.processors.cache.database.tree.io.BPlusLeafIO;
import org.apache.ignite.internal.processors.cache.database.tree.io.DataPageIO;
import org.apache.ignite.internal.processors.cache.database.tree.io.IOVersions;
import org.apache.ignite.internal.processors.cache.database.tree.reuse.ReuseList;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtInvalidPartitionException;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtLocalPartition;
import org.apache.ignite.internal.processors.cache.local.GridLocalCache;
import org.apache.ignite.internal.processors.cache.query.GridCacheQueryManager;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersion;
import org.apache.ignite.internal.processors.query.GridQueryProcessor;
import org.apache.ignite.internal.util.GridCloseableIteratorAdapter;
import org.apache.ignite.internal.util.GridEmptyCloseableIterator;
import org.apache.ignite.internal.util.lang.GridCloseableIterator;
import org.apache.ignite.internal.util.lang.GridCursor;
import org.apache.ignite.internal.util.lang.GridIterator;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteClosure;
import org.apache.ignite.lang.IgnitePredicate;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.internal.IgniteComponentType.INDEXING;
import static org.apache.ignite.internal.pagemem.PageIdUtils.dwordsOffset;
import static org.apache.ignite.internal.pagemem.PageIdUtils.pageId;

/**
 *
 */
public class IgniteCacheOffheapManagerImpl extends GridCacheManagerAdapter implements IgniteCacheOffheapManager {
    /** */
    private boolean indexingEnabled;

    /** */
    private FreeList freeList;

    /** */
    private ReuseList reuseList;

    /** */
    private CacheDataStore locCacheDataStore;

    /** */
    private PendingEntriesImpl pendingEntries;

    /** {@inheritDoc} */
    @Override protected void start0() throws IgniteCheckedException {
        super.start0();

        indexingEnabled = INDEXING.inClassPath() && GridQueryProcessor.isEnabled(cctx.config());

        if (cctx.affinityNode()) {
            IgniteCacheDatabaseSharedManager dbMgr = cctx.shared().database();

            int cpus = Runtime.getRuntime().availableProcessors();

            cctx.shared().database().checkpointReadLock();

            try {
                reuseList = new ReuseList(cctx.cacheId(), dbMgr.pageMemory(), cctx.shared().wal(),
                    cpus * 2, dbMgr.meta());
                freeList = new FreeList(cctx, reuseList);

                if (cctx.isLocal()) {
                    assert cctx.cache() instanceof GridLocalCache : cctx.cache();

                    locCacheDataStore = createCacheDataStore(0, (GridLocalCache)cctx.cache());
                }
            }
            finally {
                cctx.shared().database().checkpointReadUnlock();
            }
        }
    }

    /**
     * @param part Partition.
     * @return Data store for given entry.
     */
    private CacheDataStore dataStore(GridDhtLocalPartition part) {
        if (cctx.isLocal())
            return locCacheDataStore;
        else {
            assert part != null;

            return part.dataStore();
        }
    }

    /**
     * @param p Partition.
     * @return Partition data.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable private CacheDataStore partitionData(int p) throws IgniteCheckedException {
        if (cctx.isLocal())
            return locCacheDataStore;
        else {
            GridDhtLocalPartition part = cctx.topology().localPartition(p, AffinityTopologyVersion.NONE, false);

            return part != null ? part.dataStore() : null;
        }
    }

    /** {@inheritDoc} */
    @Override public ReuseList reuseList() {
        return reuseList;
    }

    /** {@inheritDoc} */
    @Override public long entriesCount(boolean primary, boolean backup, AffinityTopologyVersion topVer) throws IgniteCheckedException {
        if (cctx.isLocal())
            return 0; // TODO: GG-11208.
        else {
            ClusterNode locNode = cctx.localNode();

            long cnt = 0;

            for (GridDhtLocalPartition locPart : cctx.topology().currentLocalPartitions()) {
                if (primary) {
                    if (cctx.affinity().primary(locNode, locPart.id(), topVer)) {
                        cnt += locPart.size();

                        continue;
                    }
                }

                if (backup) {
                    if (cctx.affinity().backup(locNode, locPart.id(), topVer))
                        cnt += locPart.size();
                }
            }

            return cnt;
        }
    }

    /** {@inheritDoc} */
    @Override public long entriesCount(int part) {
        if (cctx.isLocal())
            return 0; // TODO: GG-11208.
        else {
            GridDhtLocalPartition locPart = cctx.topology().localPartition(part, AffinityTopologyVersion.NONE, false);

            return locPart == null ? 0 : locPart.size();
        }
    }

    /**
     * @param primary Primary data flag.
     * @param backup Primary data flag.
     * @param topVer Topology version.
     * @return Data stores iterator.
     */
    private Iterator<CacheDataStore> cacheData(boolean primary, boolean backup, AffinityTopologyVersion topVer) {
        assert primary || backup;

        if (cctx.isLocal())
            return Collections.singleton(locCacheDataStore).iterator();
        else {
            final Iterator<GridDhtLocalPartition> it = cctx.topology().currentLocalPartitions().iterator();

            if (primary && backup) {
                return F.iterator(it, new IgniteClosure<GridDhtLocalPartition, CacheDataStore>() {
                    @Override public CacheDataStore apply(GridDhtLocalPartition part) {
                        return part.dataStore();
                    }
                }, true);
            }

            final Set<Integer> parts = primary ? cctx.affinity().primaryPartitions(cctx.localNodeId(), topVer) :
                    cctx.affinity().backupPartitions(cctx.localNodeId(), topVer);

            return F.iterator(it, new IgniteClosure<GridDhtLocalPartition, CacheDataStore>() {
                        @Override public CacheDataStore apply(GridDhtLocalPartition part) {
                            return part.dataStore();
                        }
                    }, true,
                    new IgnitePredicate<GridDhtLocalPartition>() {
                        @Override public boolean apply(GridDhtLocalPartition part) {
                            return parts.contains(part.id());
                        }
                    });
        }
    }

    /** {@inheritDoc} */
    @Override public void update(
            KeyCacheObject key,
            CacheObject val,
            GridCacheVersion ver,
            long expireTime,
            int partId,
            GridDhtLocalPartition part
    ) throws IgniteCheckedException {
        dataStore(part).update(key, partId, val, ver, expireTime);
    }

    /** {@inheritDoc} */
    @Override public void remove(
            KeyCacheObject key,
            CacheObject prevVal,
            GridCacheVersion prevVer,
            int partId,
            GridDhtLocalPartition part
    ) throws IgniteCheckedException {
        dataStore(part).remove(key, prevVal, prevVer, partId);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Nullable public CacheDataRow read(GridCacheMapEntry entry)
        throws IgniteCheckedException {
        try {
            KeyCacheObject key = entry.key();

            assert cctx.isLocal() || entry.localPartition() != null : entry;

            return dataStore(entry.localPartition()).find(key);
        }
        catch (IgniteCheckedException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IgniteCheckedException("Failed to read entry: " + entry.key(), e);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean containsKey(GridCacheMapEntry entry) {
        try {
            return read(entry) != null;
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to read value", e);

            return false;
        }
    }

    /** {@inheritDoc} */
    @Override public void onPartitionCounterUpdated(int part, long cntr) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public long lastUpdatedPartitionCounter(int part) {
        return 0;
    }

    /**
     * Clears offheap entries.
     *
     * @param readers {@code True} to clear readers.
     */
    @SuppressWarnings("unchecked")
    @Override public void clear(boolean readers) {
        GridCacheVersion obsoleteVer = null;

        GridIterator<CacheDataRow>  it = rowsIterator(true, true, null);

        while (it.hasNext()) {
            KeyCacheObject key = it.next().key();

            try {
                if (obsoleteVer == null)
                    obsoleteVer = cctx.versions().next();

                GridCacheEntryEx entry = cctx.cache().entryEx(key);

                entry.clear(obsoleteVer, readers);
            }
            catch (GridDhtInvalidPartitionException ignore) {
                // Ignore.
            }
            catch (IgniteCheckedException e) {
                U.error(log, "Failed to clear cache entry: " + key, e);
            }
        }
    }

    /** {@inheritDoc} */
    @Override public int onUndeploy(ClassLoader ldr) {
        // TODO: GG-11141.
        return 0;
    }

    /** {@inheritDoc} */
    @Override public long offHeapAllocatedSize() {
        // TODO GG-10884.
        return 0;
    }

    /** {@inheritDoc} */
    @Override public void writeAll(Iterable<GridCacheBatchSwapEntry> swapped) throws IgniteCheckedException {
        // No-op.
    }

    /**
     * @param primary {@code True} if need return primary entries.
     * @param backup {@code True} if need return backup entries.
     * @param topVer Topology version to use.
     * @return Entries iterator.
     * @throws IgniteCheckedException If failed.
     */
    @SuppressWarnings("unchecked")
    @Override  public <K, V> GridCloseableIterator<Cache.Entry<K, V>> entriesIterator(final boolean primary,
        final boolean backup,
        final AffinityTopologyVersion topVer,
        final boolean keepBinary) throws IgniteCheckedException {
        final Iterator<CacheDataRow> it = rowsIterator(primary, backup, topVer);

        return new GridCloseableIteratorAdapter<Cache.Entry<K, V>>() {
            /** */
            private CacheEntryImplEx next;

            @Override protected Cache.Entry<K, V> onNext() throws IgniteCheckedException {
                CacheEntryImplEx ret = next;

                next = null;

                return ret;
            }

            @Override protected boolean onHasNext() throws IgniteCheckedException {
                if (next != null)
                    return true;

                CacheDataRow nextRow = null;

                if (it.hasNext())
                    nextRow = it.next();

                if (nextRow != null) {
                    KeyCacheObject key = nextRow.key();
                    CacheObject val = nextRow.value();

                    Object key0 = cctx.unwrapBinaryIfNeeded(key, keepBinary, false);
                    Object val0 = cctx.unwrapBinaryIfNeeded(val, keepBinary, false);

                    next = new CacheEntryImplEx(key0, val0, nextRow.version());

                    return true;
                }

                return false;
            }
        };
    }

    /** {@inheritDoc} */
    @Override public GridCloseableIterator<KeyCacheObject> keysIterator(final int part) throws IgniteCheckedException {
        CacheDataStore data = partitionData(part);

        if (data == null)
            return new GridEmptyCloseableIterator<>();

        final GridCursor<? extends CacheDataRow> cur = data.cursor();

        return new GridCloseableIteratorAdapter<KeyCacheObject>() {
            /** */
            private KeyCacheObject next;

            @Override protected KeyCacheObject onNext() throws IgniteCheckedException {
                KeyCacheObject res = next;

                next = null;

                return res;
            }

            @Override protected boolean onHasNext() throws IgniteCheckedException {
                if (next != null)
                    return true;

                if (cur.next()) {
                    CacheDataRow row = cur.get();

                    next = row.key();
                }

                return next != null;
            }
        };
    }

    /** {@inheritDoc} */
    @Override public GridIterator<CacheDataRow> iterator(boolean primary, boolean backups, final AffinityTopologyVersion topVer)
        throws IgniteCheckedException {
        return rowsIterator(primary, backups, topVer);
    }

    /**
     * @param primary Primary entries flag.
     * @param backups Backup entries flag.
     * @param topVer Topology version.
     * @return Iterator.
     */
    private GridIterator<CacheDataRow> rowsIterator(boolean primary, boolean backups, AffinityTopologyVersion topVer) {
        final Iterator<CacheDataStore> dataIt = cacheData(primary, backups, topVer);

        return new GridCloseableIteratorAdapter<CacheDataRow>() {
            /** */
            private GridCursor<? extends CacheDataRow> cur;

            /** */
            private CacheDataRow next;

            @Override protected CacheDataRow onNext() throws IgniteCheckedException {
                CacheDataRow res = next;

                next = null;

                return res;
            }

            @Override protected boolean onHasNext() throws IgniteCheckedException {
                if (next != null)
                    return true;

                while (true) {
                    if (cur == null) {
                        if (dataIt.hasNext())
                            cur = dataIt.next().cursor();
                        else
                            break;
                    }

                    if (cur.next()) {
                        next = cur.get();

                        break;
                    }
                    else
                        cur = null;
                }

                return next != null;
            }
        };
    }

    /** {@inheritDoc} */
    @Override public GridIterator<CacheDataRow> iterator(int part) throws IgniteCheckedException {
        CacheDataStore data = partitionData(part);

        if (data == null)
            return new GridEmptyCloseableIterator<>();

        final GridCursor<? extends CacheDataRow> cur = data.cursor();

        return new GridCloseableIteratorAdapter<CacheDataRow>() {
            /** */
            private CacheDataRow next;

            @Override protected CacheDataRow onNext() throws IgniteCheckedException {
                CacheDataRow res = next;

                next = null;

                return res;
            }

            @Override protected boolean onHasNext() throws IgniteCheckedException {
                if (next != null)
                    return true;

                if (cur.next())
                    next = cur.get();

                return next != null;
            }
        };
    }

    /**
     * @param pageId Page ID.
     * @return Page.
     * @throws IgniteCheckedException If failed.
     */
    private Page page(long pageId) throws IgniteCheckedException {
        return cctx.shared().database().pageMemory().page(cctx.cacheId(), pageId);
    }

    /** {@inheritDoc} */
    @Override public final CacheDataStore createCacheDataStore(int p, CacheDataStore.Listener lsnr) throws IgniteCheckedException {
        IgniteCacheDatabaseSharedManager dbMgr = cctx.shared().database();

        String idxName = treeName(p);

        // TODO: GG-11220 cleanup when cache/partition is destroyed.
        final RootPage rootPage = dbMgr.meta().getOrAllocateForTree(cctx.cacheId(), idxName);

        CacheDataRowStore rowStore = new CacheDataRowStore(cctx, freeList);

        CacheDataTree dataTree = new CacheDataTree(idxName,
                reuseList,
                rowStore,
                cctx,
                dbMgr.pageMemory(),
                rootPage.pageId(),
                rootPage.isAllocated());

        return new CacheDataStoreImpl(rowStore, dataTree, lsnr);
    }

    /**
     * @param p Partition.
     * @return Tree name for given partition.
     */
    private String treeName(int p) {
        return BPlusTree.treeName("p-" + p, cctx.cacheId(), "CacheData");
    }

    /** {@inheritDoc} */
    @Override public PendingEntries createPendingEntries() throws IgniteCheckedException {
        assert pendingEntries == null;

        IgniteCacheDatabaseSharedManager dbMgr = cctx.shared().database();
        String btname = BPlusTree.treeName("pending", cctx.cacheId(), "PendingEntries");
        final RootPage rootPage = dbMgr.meta().getOrAllocateForTree(cctx.cacheId(), btname);

        pendingEntries = new PendingEntriesImpl(
            btname,
            cctx.cacheId(),
            dbMgr.pageMemory(),
            rootPage.pageId(),
            reuseList,
            rootPage.isAllocated());

        return pendingEntries;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(IgniteCacheOffheapManagerImpl.class, this);
    }

    /**
     *
     */
    private class CacheDataStoreImpl implements CacheDataStore {
        /** */
        private final CacheDataRowStore rowStore;

        /** */
        private final CacheDataTree dataTree;

        /** */
        private final Listener lsnr;

        /**
         * @param rowStore Row store.
         * @param dataTree Data tree.
         * @param lsnr Listener.
         */
        public CacheDataStoreImpl(CacheDataRowStore rowStore,
            CacheDataTree dataTree,
            Listener lsnr) {
            this.rowStore = rowStore;
            this.dataTree = dataTree;
            this.lsnr = lsnr;
        }

        /** {@inheritDoc} */
        @Override public void update(KeyCacheObject key,
            int p,
            CacheObject val,
            GridCacheVersion ver,
            long expireTime) throws IgniteCheckedException {
            DataRow dataRow = new DataRow(key.hashCode(), key, val, ver, p, expireTime);

            rowStore.addRow(dataRow);

            assert dataRow.link != 0 : dataRow;

            DataRow old = dataTree.put(dataRow);

            if (indexingEnabled) {
                GridCacheQueryManager qryMgr = cctx.queries();

                assert qryMgr.enabled();

                qryMgr.store(key, p, val, ver, expireTime, dataRow.link);
            }

            if (old != null) {
                assert old.link != 0 : old;

                if (pendingEntries != null && old.expTime != 0) {
                    PendingRow rmvd = pendingEntries.remove(new PendingRow(old.expTime, old.link, false));

                    assert rmvd != null;
                }

                rowStore.removeRow(old.link);
            }
            else
                lsnr.onInsert();

            if (pendingEntries != null && expireTime != 0)
                pendingEntries.put(new PendingRow(expireTime, dataRow.link, false));
        }

        /** {@inheritDoc} */
        @Override public void remove(KeyCacheObject key,
            CacheObject prevVal,
            GridCacheVersion prevVer,
            int partId) throws IgniteCheckedException {
            DataRow dataRow = dataTree.remove(new KeySearchRow(key.hashCode(), key, 0));

            if (dataRow == null)
                return;

            if (indexingEnabled) {
                GridCacheQueryManager qryMgr = cctx.queries();

                assert qryMgr.enabled();

                qryMgr.remove(key, partId, prevVal, prevVer);
            }

            assert dataRow.link != 0 : dataRow;

            if (pendingEntries != null && dataRow.expTime != 0) {
                PendingRow rmvd = pendingEntries.remove(new PendingRow(dataRow.expTime, dataRow.link, false));

                assert rmvd != null;
            }

            rowStore.removeRow(dataRow.link);

            lsnr.onRemove();
        }

        /** {@inheritDoc} */
        @Override public CacheDataRow find(KeyCacheObject key)
            throws IgniteCheckedException {
            return dataTree.findOne(new KeySearchRow(key.hashCode(), key, 0));
        }

        /** {@inheritDoc} */
        @Override public GridCursor<? extends CacheDataRow> cursor() throws IgniteCheckedException {
            return dataTree.find(null, null);
        }
    }

    /**
     *
     */
    private class KeySearchRow {
        /** */
        int hash;

        /** */
        KeyCacheObject key;

        /** */
        long link;

        /**
         * @param hash Hash code.
         * @param key Key.
         * @param link Link.
         */
        KeySearchRow(int hash, KeyCacheObject key, long link) {
            this.hash = hash;
            this.key = key;
            this.link = link;
        }

        /**
         * @param buf Buffer.
         * @throws IgniteCheckedException If failed.
         */
        protected void doInitData(ByteBuffer buf) throws IgniteCheckedException {
            key = cctx.cacheObjects().toKeyCacheObject(cctx.cacheObjectContext(), buf);
        }

        /**
         * Init data.
         */
        protected final void initData() {
            if (key != null)
                return;

            assert link != 0;

            try (Page page = page(pageId(link))) {
                ByteBuffer buf = page.getForRead();

                try {
                    DataPageIO io = DataPageIO.VERSIONS.forPage(buf);

                    int dataOff = io.getDataOffset(buf, dwordsOffset(link));

                    buf.position(dataOff);

                    // Skip entry size.
                    buf.getShort();

                    doInitData(buf);
                }
                finally {
                    page.releaseRead();
                }
            }
            catch (IgniteCheckedException e) {
                throw new IgniteException(e);
            }
        }

        /**
         * @return Key.
         */
        public KeyCacheObject key() {
            initData();

            return key;
        }

        /** {@inheritDoc} */
        public String toString() {
            return S.toString(KeySearchRow.class, this);
        }
    }

    /**
     *
     */
    class DataRow extends KeySearchRow implements CacheDataRow {
        /** */
        private CacheObject val;

        /** */
        private GridCacheVersion ver;

        /** */
        private int part = -1;

        /** */
        private long expTime;

        /**
         * @param link Link.
         */
        DataRow(long link) {
            this(0, link);
            hash = key.hashCode();
        }

        /**
         * @param hash Hash code.
         * @param link Link.
         */
        DataRow(int hash, long link) {
            super(hash, null, link);

            part = PageIdUtils.partId(link);

            // We can not init data row lazily because underlying buffer can be concurrently cleared.
            initData();
        }

        /**
         * @param hash Hash code.
         * @param key Key.
         * @param val Value.
         * @param ver Version.
         * @param part Partition.
         */
        DataRow(int hash, KeyCacheObject key, CacheObject val, GridCacheVersion ver, int part, long expTime) {
            super(hash, key, 0);

            this.val = val;
            this.ver = ver;
            this.part = part;
            this.expTime = expTime;
        }

        /** {@inheritDoc} */
        @Override protected void doInitData(ByteBuffer buf) throws IgniteCheckedException {
            key = cctx.cacheObjects().toKeyCacheObject(cctx.cacheObjectContext(), buf);
            val = cctx.cacheObjects().toCacheObject(cctx.cacheObjectContext(), buf);

            int topVer = buf.getInt();
            int nodeOrderDrId = buf.getInt();
            long globalTime = buf.getLong();
            long order = buf.getLong();

            ver = new GridCacheVersion(topVer, nodeOrderDrId, globalTime, order);
            expTime = buf.getLong();
        }

        /** {@inheritDoc} */
        @Override public CacheObject value() {
            assert val != null;

            return val;
        }

        /** {@inheritDoc} */
        @Override public GridCacheVersion version() {
            assert ver != null;

            return ver;
        }

        /** {@inheritDoc} */
        @Override public int partition() {
            assert part != -1;

            return part;
        }

        /** {@inheritDoc} */
        @Override public long link() {
            return link;
        }

        /** {@inheritDoc} */
        @Override public void link(long link) {
            this.link = link;
        }

        /** {@inheritDoc} */
        @Override public long expireTime() {
            initData();

            return expTime;
        }

        /** {@inheritDoc} */
        public String toString() {
            return S.toString(DataRow.class, this);
        }
    }

    /**
     *
     */
    private static class CacheDataTree extends BPlusTree<KeySearchRow, DataRow> {
        /** */
        private final CacheDataRowStore rowStore;

        /** */
        private final GridCacheContext cctx;

        /**
         * @param name Tree name.
         * @param reuseList Reuse list.
         * @param rowStore Row store.
         * @param cctx Context.
         * @param pageMem Page memory.
         * @param metaPageId Meta page ID.
         * @param initNew Initialize new index.
         * @throws IgniteCheckedException If failed.
         */
        CacheDataTree(
            String name,
            ReuseList reuseList,
            CacheDataRowStore rowStore,
            GridCacheContext cctx,
            PageMemory pageMem,
            FullPageId metaPageId,
            boolean initNew
        ) throws IgniteCheckedException {
            super(name, cctx.cacheId(), pageMem, cctx.shared().wal(), metaPageId,
                reuseList, DataInnerIO.VERSIONS, DataLeafIO.VERSIONS);

            assert rowStore != null;

            this.rowStore = rowStore;
            this.cctx = cctx;

            if (initNew)
                initNew();
        }

        /** {@inheritDoc} */
        @Override protected int compare(BPlusIO<KeySearchRow> io, ByteBuffer buf, int idx, KeySearchRow row)
            throws IgniteCheckedException {
            KeySearchRow row0 = io.getLookupRow(this, buf, idx);

            int cmp = Integer.compare(row0.hash, row.hash);

            if (cmp != 0)
                return cmp;

            return compareKeys(row0.key(), row.key());
        }

        /** {@inheritDoc} */
        @Override protected DataRow getRow(BPlusIO<KeySearchRow> io, ByteBuffer buf, int idx)
            throws IgniteCheckedException {
            int hash = ((RowLinkIO)io).getHash(buf, idx);
            long link = ((RowLinkIO)io).getLink(buf, idx);

            return rowStore.dataRow(hash, link);
        }

        /**
         * @param key1 First key.
         * @param key2 Second key.
         * @return Compare result.
         * @throws IgniteCheckedException If failed.
         */
        private int compareKeys(CacheObject key1, CacheObject key2) throws IgniteCheckedException {
            byte[] bytes1 = key1.valueBytes(cctx.cacheObjectContext());
            byte[] bytes2 = key2.valueBytes(cctx.cacheObjectContext());

            int len = Math.min(bytes1.length, bytes2.length);

            for (int i = 0; i < len; i++) {
                byte b1 = bytes1[i];
                byte b2 = bytes2[i];

                if (b1 != b2)
                    return b1 > b2 ? 1 : -1;
            }

            return Integer.compare(bytes1.length, bytes2.length);
        }
    }

    /**
     *
     */
    private class CacheDataRowStore extends RowStore {
        /**
         * @param cctx Cache context.
         * @param freeList Free list.
         */
        CacheDataRowStore(GridCacheContext<?, ?> cctx, FreeList freeList) {
            super(cctx, freeList);
        }

        /**
         * @param hash Hash code.
         * @param link Link.
         * @return Search row.
         * @throws IgniteCheckedException If failed.
         */
        private KeySearchRow keySearchRow(int hash, long link) throws IgniteCheckedException {
            return new KeySearchRow(hash, null, link);
        }

        /**
         * @param hash Hash code.
         * @param link Link.
         * @return Data row.
         * @throws IgniteCheckedException If failed.
         */
        private DataRow dataRow(int hash, long link) throws IgniteCheckedException {
            return new DataRow(hash, link);
        }
    }

    /**
     * @param buf Buffer.
     * @param off Offset.
     * @param link Link.
     * @param hash Hash.
     */
    private static void store0(ByteBuffer buf, int off, long link, int hash) {
        buf.putLong(off, link);
        buf.putInt(off + 8, hash);
    }

    /**
     *
     */
    private interface RowLinkIO {
        /**
         * @param buf Buffer.
         * @param idx Index.
         * @return Row link.
         */
        public long getLink(ByteBuffer buf, int idx);

        /**
         * @param buf Buffer.
         * @param idx Index.
         * @return Key hash code.
         */
        public int getHash(ByteBuffer buf, int idx);
    }

    /**
     *
     */
    public static final class DataInnerIO extends BPlusInnerIO<KeySearchRow> implements RowLinkIO {
        /** */
        public static final IOVersions<DataInnerIO> VERSIONS = new IOVersions<>(
            new DataInnerIO(1)
        );

        /**
         * @param ver Page format version.
         */
        DataInnerIO(int ver) {
            super(T_DATA_REF_INNER, ver, true, 12);
        }

        /** {@inheritDoc} */
        @Override public void storeByOffset(ByteBuffer buf, int off, KeySearchRow row) {
            assert row.link != 0;

            store0(buf, off, row.link, row.hash);
        }

        /** {@inheritDoc} */
        @Override public KeySearchRow getLookupRow(BPlusTree<KeySearchRow,?> tree, ByteBuffer buf, int idx)
            throws IgniteCheckedException {
            int hash = getHash(buf, idx);
            long link = getLink(buf, idx);

            return ((CacheDataTree)tree).rowStore.keySearchRow(hash, link);
        }

        /** {@inheritDoc} */
        @Override public void store(ByteBuffer dst, int dstIdx, BPlusIO<KeySearchRow> srcIo, ByteBuffer src, int srcIdx) {
            int hash = ((RowLinkIO)srcIo).getHash(src, srcIdx);
            long link = ((RowLinkIO)srcIo).getLink(src, srcIdx);

            store0(dst, offset(dstIdx), link, hash);
        }

        /** {@inheritDoc} */
        @Override public long getLink(ByteBuffer buf, int idx) {
            assert idx < getCount(buf): idx;

            return buf.getLong(offset(idx));
        }

        /** {@inheritDoc} */
        @Override public int getHash(ByteBuffer buf, int idx) {
            return buf.getInt(offset(idx) + 8);
        }
    }

    /**
     *
     */
    public static final class DataLeafIO extends BPlusLeafIO<KeySearchRow> implements RowLinkIO {
        /** */
        public static final IOVersions<DataLeafIO> VERSIONS = new IOVersions<>(
            new DataLeafIO(1)
        );

        /**
         * @param ver Page format version.
         */
        DataLeafIO(int ver) {
            super(T_DATA_REF_LEAF, ver, 12);
        }

        /** {@inheritDoc} */
        @Override public void storeByOffset(ByteBuffer buf, int off, KeySearchRow row) {
            DataRow row0 = (DataRow)row;

            assert row0.link != 0;

            store0(buf, off, row.link, row.hash);
        }

        /** {@inheritDoc} */
        @Override public void store(ByteBuffer dst, int dstIdx, BPlusIO<KeySearchRow> srcIo, ByteBuffer src, int srcIdx)
            throws IgniteCheckedException {
            store0(dst, offset(dstIdx), getLink(src, srcIdx), getHash(src, srcIdx));
        }

        /** {@inheritDoc} */
        @Override public KeySearchRow getLookupRow(BPlusTree<KeySearchRow,?> tree, ByteBuffer buf, int idx)
            throws IgniteCheckedException {

            int hash = getHash(buf, idx);
            long link = getLink(buf, idx);

            return ((CacheDataTree)tree).rowStore.keySearchRow(hash, link);
        }

        /** {@inheritDoc} */
        @Override public long getLink(ByteBuffer buf, int idx) {
            assert idx < getCount(buf): idx;

            return buf.getLong(offset(idx));
        }

        /** {@inheritDoc} */
        @Override public int getHash(ByteBuffer buf, int idx) {
            return buf.getInt(offset(idx) + 8);
        }
    }


    /**
     * The class to store tracked entry on the paged memory.
     * We have to store only link and expireTime because entry could be loaded from swap
     * by link.
     */
    class PendingRow {
        /** */
        private static final int SIZE = 8 + 8;

        /** Expire time. */
        long expireTime;

        /** Link. */
        long link;

        /** Key  */
        KeyCacheObject key;

        /**
         * @param time Time.
         */
        PendingRow(long time) {
            expireTime = time;
        }

        /**
         * @param expireTime Expire time.
         * @param link Link.
         */
        PendingRow(long expireTime, long link, boolean initKey) {
            this.expireTime = expireTime;
            this.link= link;

            if (initKey)
                key = new DataRow(link).key();
        }

        /**
         *
         */
        GridCacheEntryEx entryEx() {
            assert key != null : this;

            return  cctx.cache().entryEx(key);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(PendingRow.class, this);
        }
    }

    /**
     * Implementation of tracked entries collection based on B+tree.
     */
    class PendingEntriesImpl extends BPlusTree<PendingRow, PendingRow> implements PendingEntries {
        /**
         * @param name Tree name.
         * @param cacheId Cache ID.
         * @param pageMem Page memory.
         * @param metaPageId Meta page ID.
         * @param reuseList Reuse list.
         */
        public PendingEntriesImpl(String name,
            int cacheId,
            PageMemory pageMem,
            FullPageId metaPageId,
            ReuseList reuseList,
            boolean initNew)
            throws IgniteCheckedException {
            super(name,
                cacheId,
                pageMem,
                cctx.shared().wal(),
                metaPageId,
                reuseList,
                PendingEntryInnerIO.VERSIONS,
                PendingEntryLeafIO.VERSIONS);

            if (initNew)
                initNew();
        }

        /** {@inheritDoc} */
        @Override protected int compare(BPlusIO<PendingRow> io, ByteBuffer buf, int idx, PendingRow row)
            throws IgniteCheckedException {
            PendingRow row0 = io.getLookupRow(this, buf, idx);

            int cmp = Long.compare(row0.expireTime, row.expireTime);

            return (cmp != 0) ? cmp : Long.compare(row0.link, row.link);
        }

        /** {@inheritDoc} */
        @Override protected PendingRow getRow(BPlusIO<PendingRow> io, ByteBuffer buf, int idx)
            throws IgniteCheckedException {
            return io.getLookupRow(this, buf, idx);
        }

        /** {@inheritDoc} */
        @Override public ExpiredEntriesCursor expired(final long time) throws IgniteCheckedException {
            final GridCursor<PendingRow> cur = find(new PendingRow(0), new PendingRow(time));

            return new ExpiredEntriesCursor() {
                @Override public boolean next() throws IgniteCheckedException {
                    if (!cur.next())
                        return false;

                    return cur.get().expireTime < time;
                }

                @Override public GridCacheEntryEx get() throws IgniteCheckedException {
                    PendingRow r = cur.get();

                    if ((r == null) || (r.expireTime >= time))
                        return null;

                    return r.entryEx();
                }
            };
        }

        /**
         * @param expireTime Expire time.
         * @param link Link.
         * @return Row.
         */
        PendingRow createPendingRow(long expireTime, long link) {
            return new PendingRow(expireTime, link, true);
        }

        /** {@inheritDoc} */
        @Override public int pendingSize() {
            try {
                return (int)size();
            }
            catch (IgniteCheckedException e) {
                log.error("Unexpected exception", e);
                return -1;
            }
        }

        /** {@inheritDoc} */
        @Override public long firstExpired() {
            try {
                GridCursor<PendingRow> cur = find(null, null);

                if (cur.next())
                    return cur.get().expireTime;
            }
            catch (IgniteCheckedException e) {
                log.error("Unexpected exception", e);
            }

            return 0;
        }
    }

    /**
     *
     */
    private interface PendingRowIO {
        /**
         * @param buf Buffer.
         * @param idx Index.
         */
        long getExpireTime(ByteBuffer buf, int idx);
        /**
         * @param buf Buffer.
         * @param idx Index.
         */
        long getLink(ByteBuffer buf, int idx);
    }

    /**
     *
     */
    private static class PendingEntryInnerIO extends BPlusInnerIO<PendingRow> implements PendingRowIO {
        /** */
        public static final IOVersions<PendingEntryInnerIO> VERSIONS = new IOVersions<>(
            new PendingEntryInnerIO(1)
        );

        /**
         * @param ver Page format version.
         */
        PendingEntryInnerIO(int ver) {
            super(T_PENDING_REF_INNER, ver, true, PendingRow.SIZE);
        }

        /** {@inheritDoc} */
        @Override public void storeByOffset(ByteBuffer buf, int off, PendingRow row) throws IgniteCheckedException {
            buf.putLong(off, row.expireTime);
            buf.putLong(off + 8, row.link);
        }

        /** {@inheritDoc} */
        @Override public void store(ByteBuffer dst, int dstIdx, BPlusIO<PendingRow> srcIo,
            ByteBuffer src, int srcIdx) throws IgniteCheckedException {
            setExpireTime(((PendingRowIO)srcIo).getExpireTime(src, srcIdx), dst, dstIdx);
            setLink(((PendingRowIO)srcIo).getLink(src, srcIdx), dst, dstIdx);
        }

        /** {@inheritDoc} */
        @Override public PendingRow getLookupRow(
            BPlusTree<PendingRow, ?> tree, ByteBuffer buf,
            int idx) throws IgniteCheckedException {
            return ((PendingEntriesImpl)tree).createPendingRow(
                getExpireTime(buf, idx),
                getLink(buf, idx));
        }

        /** {@inheritDoc} */
        @Override public long getExpireTime(ByteBuffer buf, int idx) {
            return buf.getLong(offset(idx));
        }

        /**
         * @param expireTime Expire time.
         * @param buf Buffer.
         * @param idx Index.
         */
        private void setExpireTime(long expireTime, ByteBuffer buf, int idx) {
            buf.putLong(offset(idx), expireTime);
        }

        /** {@inheritDoc} */
        @Override public long getLink(ByteBuffer buf, int idx) {
            return buf.getLong(offset(idx) + 8);
        }

        /**
         * @param link Link.
         * @param buf Buffer.
         * @param idx Index.
         */
        private void setLink(long link, ByteBuffer buf, int idx) {
            buf.putLong(offset(idx) + 8, link);
        }
    }

    /**
     *
     */
    private static class PendingEntryLeafIO extends BPlusLeafIO<PendingRow> implements PendingRowIO {
        /** */
        public static final IOVersions<PendingEntryLeafIO> VERSIONS = new IOVersions<>(
            new PendingEntryLeafIO(1)
        );

        /**
         * @param ver Page format version.
         */
        PendingEntryLeafIO(int ver) {
            super(T_PENDING_REF_LEAF, ver, PendingRow.SIZE);
        }

        /** {@inheritDoc} */
        @Override public void storeByOffset(ByteBuffer buf, int off, PendingRow row) throws IgniteCheckedException {
            buf.putLong(off, row.expireTime);
            buf.putLong(off + 8, row.link);
        }

        /** {@inheritDoc} */
        @Override public void store(ByteBuffer dst, int dstIdx, BPlusIO<PendingRow> srcIo,
            ByteBuffer src, int srcIdx) throws IgniteCheckedException {
            setExpireTime(((PendingRowIO)srcIo).getExpireTime(src, srcIdx), dst, dstIdx);
            setLink(((PendingRowIO)srcIo).getLink(src, srcIdx), dst, dstIdx);
        }

        /** {@inheritDoc} */
        @Override public PendingRow getLookupRow(
            BPlusTree<PendingRow, ?> tree, ByteBuffer buf,
            int idx) throws IgniteCheckedException {
            return ((PendingEntriesImpl)tree).createPendingRow(
                getExpireTime(buf, idx),
                getLink(buf, idx));
        }

        /** {@inheritDoc} */
        @Override public long getExpireTime(ByteBuffer buf, int idx) {
            return buf.getLong(offset(idx));
        }

        /**
         * @param expireTime Expire time.
         * @param buf Buffer.
         * @param idx Index.
         */
        private void setExpireTime(long expireTime, ByteBuffer buf, int idx) {
            buf.putLong(offset(idx), expireTime);
        }

        /** {@inheritDoc} */
        @Override public long getLink(ByteBuffer buf, int idx) {
            return buf.getLong(offset(idx) + 8);
        }

        /**
         * @param link Link.
         * @param buf Buffer.
         * @param idx Index.
         */
        private void setLink(long link, ByteBuffer buf, int idx) {
            buf.putLong(offset(idx) + 8, link);
        }
    }
}
