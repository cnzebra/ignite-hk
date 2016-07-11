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

package org.apache.ignite.internal.processors.cache.database.freelist;

import java.nio.ByteBuffer;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.internal.pagemem.Page;
import org.apache.ignite.internal.pagemem.PageIdAllocator;
import org.apache.ignite.internal.pagemem.PageIdUtils;
import org.apache.ignite.internal.pagemem.PageMemory;
import org.apache.ignite.internal.pagemem.wal.IgniteWriteAheadLogManager;
import org.apache.ignite.internal.pagemem.wal.record.delta.DataPageInsertRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.DataPageRemoveRecord;
import org.apache.ignite.internal.processors.cache.CacheObjectContext;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.database.CacheDataRow;
import org.apache.ignite.internal.processors.cache.database.RootPage;
import org.apache.ignite.internal.processors.cache.database.tree.BPlusTree;
import org.apache.ignite.internal.processors.cache.database.tree.io.DataPageIO;
import org.apache.ignite.internal.processors.cache.database.tree.reuse.ReuseList;
import org.apache.ignite.internal.processors.cache.database.tree.util.PageHandler;
import org.apache.ignite.internal.util.future.GridFutureAdapter;
import org.jsr166.ConcurrentHashMap8;

import static org.apache.ignite.internal.processors.cache.database.tree.util.PageHandler.writePage;

/**
 * Free data page list.
 */
public class FreeList {
    /** */
    private final GridCacheContext<?,?> cctx;

    /** */
    private final PageMemory pageMem;

    /** */
    private final ReuseList reuseList;

    /** */
    private final IgniteWriteAheadLogManager wal;

    /** */
    private final ConcurrentHashMap8<Integer,GridFutureAdapter<FreeTree>> trees = new ConcurrentHashMap8<>();

    /** */
    private final PageHandler<CacheDataRow, Void> writeRow = new PageHandler<CacheDataRow, Void>() {
        @Override public Void run(long pageId, Page page, ByteBuffer buf, CacheDataRow row, int entrySize)
            throws IgniteCheckedException {
            DataPageIO io = DataPageIO.VERSIONS.forPage(buf);

            int idx = io.addRow(cctx.cacheObjectContext(), buf, row.key(), row.value(), row.version(), entrySize);

            assert idx >= 0 : idx;

            FreeTree tree = tree(row.partition());

            if (tree.needWalDeltaRecord(page))
                wal.log(new DataPageInsertRecord(cctx.cacheId(), page.id(), row.key(), row.value(),
                    row.version(), idx, entrySize));

            row.link(PageIdUtils.linkFromDwordOffset(pageId, idx));

            final int freeSlots = io.getFreeItemSlots(buf);

            // If no free slots available then assume that page is full
            int freeSpace = freeSlots == 0 ? 0 : io.getFreeSpace(buf);

            // Put our free item.
            tree.put(new FreeItem(freeSpace, pageId, cctx.cacheId()));

            return null;
        }
    };

    /** */
    private final PageHandler<DataPageIO.FragmentCtx, Void> writeFragmentRow = new PageHandler<DataPageIO.FragmentCtx, Void>() {
        @Override public Void run(long pageId, Page page, ByteBuffer buf, DataPageIO.FragmentCtx fctx, int entrySize)
            throws IgniteCheckedException {
            final CacheDataRow row = fctx.row;

            fctx.buf = buf;

            DataPageIO io = DataPageIO.VERSIONS.forPage(buf);

            io.writeRowFragment(fctx);

            assert fctx.lastIdx >= 0 : fctx.lastIdx;

            fctx.lastLink = PageIdUtils.linkFromDwordOffset(pageId, fctx.lastIdx);

            final int freeSlots = io.getFreeItemSlots(buf);

            // If no free slots available then assume that page is full
            int freeSpace = freeSlots == 0 ? 0 : io.getFreeSpace(buf);

            // Put our free item.
            tree(row.partition()).put(new FreeItem(freeSpace, pageId, cctx.cacheId()));

            return null;
        }
    };

    /** */
    private final PageHandler<FreeTree, Long> removeRow = new PageHandler<FreeTree, Long>() {
        @Override public Long run(long pageId, Page page, ByteBuffer buf, FreeTree tree, int itemId) throws IgniteCheckedException {
            assert tree != null;

            DataPageIO io = DataPageIO.VERSIONS.forPage(buf);

            assert DataPageIO.check(itemId): itemId;

            final int dataOff = io.getDataOffset(buf, itemId);

            final long nextLink = DataPageIO.getNextFragmentLink(buf, dataOff);

            int oldFreeSpace = io.getFreeSpace(buf);

            io.removeRow(buf, (byte)itemId);

            if (tree.needWalDeltaRecord(page))
                wal.log(new DataPageRemoveRecord(cctx.cacheId(), page.id(), itemId));

            int newFreeSpace = io.getFreeSpace(buf);

            // Move page to the new position with respect to the new free space.
            FreeItem item = tree.remove(new FreeItem(oldFreeSpace, pageId, cctx.cacheId()));

            // If item is null, then it was removed concurrently by insertRow, because
            // in removeRow we own the write lock on this page. Thus we can be sure that
            // insertRow will update position correctly after us.
            if (item != null) {
                FreeItem old = tree.put(new FreeItem(newFreeSpace, pageId, cctx.cacheId()));

                assert old == null;
            }

            return nextLink;
        }
    };

    /**
     * @param reuseList Reuse list.
     * @param cctx Cache context.
     */
    public FreeList(GridCacheContext<?,?> cctx, ReuseList reuseList) {
        assert cctx != null;

        this.cctx = cctx;

        wal = cctx.shared().wal();

        pageMem = cctx.shared().database().pageMemory();

        assert pageMem != null;

        this.reuseList = reuseList;
    }

    /**
     * @param tree Tree.
     * @param lookupItem Lookup item.
     * @return Free item or {@code null} if it was impossible to find one.
     * @throws IgniteCheckedException If failed.
     */
    private FreeItem take(FreeTree tree, FreeItem lookupItem) throws IgniteCheckedException {
        FreeItem res = tree.removeCeil(lookupItem, null);

        assert res == null || (res.pageId() != 0 && res.cacheId() == cctx.cacheId()): res;

        return res;
    }

    /**
     * @param partId Partition.
     * @return Tree.
     * @throws IgniteCheckedException If failed.
     */
    private FreeTree tree(Integer partId) throws IgniteCheckedException {
        assert partId >= 0 && partId < Short.MAX_VALUE: partId;

        GridFutureAdapter<FreeTree> fut = trees.get(partId);

        if (fut == null) {
            fut = new GridFutureAdapter<>();

            if (trees.putIfAbsent(partId, fut) != null)
                fut = trees.get(partId);
            else {
                // Index name will be the same across restarts.
                String idxName = BPlusTree.treeName("p" + partId, cctx.cacheId(), "Free");

                final RootPage rootPage = cctx.shared().database().meta()
                    .getOrAllocateForTree(cctx.cacheId(), idxName);

                fut.onDone(new FreeTree(idxName, reuseList, cctx.cacheId(), partId, pageMem, wal,
                    rootPage.pageId(), rootPage.isAllocated()));
            }
        }

        return fut.get();
    }

    /**
     * @param link Row link.
     * @throws IgniteCheckedException
     */
    public void removeRow(long link) throws IgniteCheckedException {
        assert link != 0;

        long pageId = PageIdUtils.pageId(link);
        int partId = PageIdUtils.partId(pageId);
        int itemId = PageIdUtils.dwordsOffset(link);

        FreeTree tree = tree(partId);

        long nextLink;

        try (Page page = pageMem.page(cctx.cacheId(), pageId)) {
            nextLink = writePage(pageId, page, removeRow, tree, itemId);
        }

        while (nextLink != 0) {
            itemId = PageIdUtils.dwordsOffset(nextLink);
            pageId = PageIdUtils.pageId(nextLink);

            try (Page page = pageMem.page(cctx.cacheId(), pageId)) {
                nextLink = writePage(pageId, page, removeRow, tree, itemId);
            }
        }
    }

    /**
     * @param row Row.
     * @throws IgniteCheckedException If failed.
     */
    public void insertRow(CacheDataRow row) throws IgniteCheckedException {
        assert row.link() == 0: row.link();

        final CacheObjectContext coctx = cctx.cacheObjectContext();

        int entrySize = DataPageIO.getEntrySize(coctx, row.key(), row.value());

        assert entrySize > 0 : entrySize;

        final int availablePageSize = DataPageIO.getAvailablePageSize(coctx);

        FreeTree tree = tree(row.partition());

        if (availablePageSize < entrySize) {
            // write fragmented entry
            final int chunks = DataPageIO.getChunksNum(availablePageSize, entrySize);

            assert chunks > 1 : chunks;

            final DataPageIO.FragmentCtx fctx =
                new DataPageIO.FragmentCtx(entrySize, chunks, availablePageSize, row, coctx);

            final int freeLast = fctx.totalEntrySize - fctx.chunkSize * (fctx.chunks - 1);

            for (int i = 0; i < chunks; i++) {
                FreeItem item = take(tree,
                    new FreeItem(i == 0 ? freeLast : availablePageSize, 0, cctx.cacheId()));

                try (Page page = item == null ?
                    allocateDataPage(row.partition()) :
                    pageMem.page(item.cacheId(), item.pageId())
                ) {
                    if (item == null) {
                        DataPageIO io = DataPageIO.VERSIONS.latest();

                        ByteBuffer buf = page.getForWrite();

                        fctx.buf = buf;

                        try {
                            io.initNewPage(buf, page.id());

                            // It is a newly allocated page and we will not write record to WAL here.
                            assert !page.isDirty();

                            writeFragmentRow.run(page.id(), page, buf, fctx, 0);
                        }
                        finally {
                            page.releaseWrite(true);
                        }
                    }
                    else
                        writePage(page.id(), page, writeFragmentRow, fctx, 0);

                    fctx.lastLink = PageIdUtils.linkFromDwordOffset(page.id(), fctx.lastIdx);
                }
            }

            row.link(fctx.lastLink);
        }
        else {
            // TODO add random pageIndex here for lower contention?
            FreeItem item = take(tree, new FreeItem(entrySize, 0, cctx.cacheId()));

            try (Page page = item == null ?
                allocateDataPage(row.partition()) :
                pageMem.page(item.cacheId(), item.pageId())
            ) {
                if (item == null) {
                    DataPageIO io = DataPageIO.VERSIONS.latest();

                    ByteBuffer buf = page.getForWrite(); // Initial write.

                    try {
                        io.initNewPage(buf, page.id());

                        // It is a newly allocated page and we will not write record to WAL here.
                        assert !page.isDirty();

                        writeRow.run(page.id(), page, buf, row, entrySize);
                    }
                    finally {
                        page.releaseWrite(true);
                    }
                }
                else
                    writePage(page.id(), page, writeRow, row, entrySize);
            }
        }
    }

    /**
     * @param part Partition.
     * @return Page.
     * @throws IgniteCheckedException If failed.
     */
    private Page allocateDataPage(int part) throws IgniteCheckedException {
        long pageId = pageMem.allocatePage(cctx.cacheId(), part, PageIdAllocator.FLAG_DATA);

        return pageMem.page(cctx.cacheId(), pageId);
    }
}
