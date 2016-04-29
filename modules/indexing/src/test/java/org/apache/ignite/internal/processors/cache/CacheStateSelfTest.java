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
 *
 */

package org.apache.ignite.internal.processors.cache;

import java.util.concurrent.locks.Lock;
import javax.cache.CacheException;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DatabaseConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.binary.BinaryMarshaller;
import org.apache.ignite.internal.util.lang.GridAbsPredicate;
import org.apache.ignite.internal.util.typedef.G;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.apache.ignite.transactions.Transaction;
import org.apache.ignite.transactions.TransactionRollbackException;

public class CacheStateSelfTest extends GridCommonAbstractTest {

    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        cfg.setCacheConfiguration(cacheConfiguration(null));
        cfg.setMarshaller(new BinaryMarshaller());

        DatabaseConfiguration dbCfg = new DatabaseConfiguration();

        dbCfg.setConcurrencyLevel(Runtime.getRuntime().availableProcessors() * 4);
        dbCfg.setPageSize(8192);
        dbCfg.setPageCacheSize(100 * 1024 * 1024);
        //        dbCfg.setPersistenceEnabled(true);
        //        dbCfg.setFileCacheAllocationPath("D:/db/");

//        cfg.setDatabaseConfiguration(dbCfg);

        return cfg;
    }

    protected static CacheConfiguration cacheConfiguration(String cacheName) {
        CacheConfiguration ccfg = new CacheConfiguration(cacheName);

        ccfg.setAtomicityMode(CacheAtomicityMode.TRANSACTIONAL);
        ccfg.setCacheMode(CacheMode.REPLICATED);
        ccfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

        return ccfg;
    }

    public void testStatePropagation() throws Exception {
        IgniteEx ignite1 = (IgniteEx)G.start(getConfiguration("test1"));
        IgniteEx ignite2 = (IgniteEx)G.start(getConfiguration("test2"));

        final IgniteCache cache1 = ignite1.cache(null);
        final IgniteCache cache2 = ignite2.cache(null);

        assert cache1.active();
        assert cache2.active();

        cache1.active(false);

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return !cache1.active() && !cache2.active();
            }
        }, 5000);

        IgniteEx ignite3 = (IgniteEx)G.start(getConfiguration("test3"));

        final IgniteCache cache3 = ignite3.cache(null);

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return !cache1.active() && !cache2.active() && !cache3.active();
            }
        }, 5000);

        cache3.active(true);

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return cache1.active() && cache2.active() && cache3.active();
            }
        }, 5000);
    }

    public void testDeactivationWithPendingLock() throws Exception {
        IgniteEx ignite1 = (IgniteEx)G.start(getConfiguration("test1"));
        IgniteEx ignite2 = (IgniteEx)G.start(getConfiguration("test2"));

        final IgniteCache cache1 = ignite1.cache(null);
        final IgniteCache cache2 = ignite2.cache(null);

        assert cache1.active() && cache2.active();

        Lock lock = cache1.lock(1);

        lock.lock();

        try {
            IgniteInternalFuture<?> fut = multithreadedAsync(new Runnable() {
                @Override public void run() {
                    cache1.active(false).get();
                }
            }, 1);

            U.sleep(5000);

            assert !fut.isDone();
            assert cache1.active() && cache2.active();
        }
        finally {
            lock.unlock();
        }

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return !cache1.active() && !cache2.active();
            }
        }, 5000);

        boolean ex = false;

        try {
            cache1.lock(2).lock();
        }
        catch (CacheException e) {
            ex = true;
            assert e.getMessage().equals("Cache is inactive");
        }

        assert ex;
    }

    public void testDeactivationWithPendingTransaction() throws Exception {
        IgniteEx ignite1 = (IgniteEx)G.start(getConfiguration("test1"));
        IgniteEx ignite2 = (IgniteEx)G.start(getConfiguration("test2"));

        final IgniteCache cache1 = ignite1.cache(null);
        final IgniteCache cache2 = ignite2.cache(null);

        assert cache1.active() && cache2.active();

        Transaction tx = ignite1.transactions().txStart();

        cache1.put(1, 1);

        IgniteInternalFuture<?> fut = multithreadedAsync(new Runnable() {
            @Override public void run() {
                cache1.active(false).get();
            }
        }, 1);

        U.sleep(5000);

        assert !fut.isDone();
        assert cache1.active() && cache2.active();

        cache1.put(2, 2);

        tx.commit();

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return !cache1.active() && !cache2.active();
            }
        }, 5000);

        boolean ex = false;

        try {
            cache1.put(3, 3);
        }
        catch (CacheException e) {
            ex = true;

            TransactionRollbackException tre = (TransactionRollbackException)e.getCause();

            assert tre.getCause(CacheInvalidStateException.class).getMessage().equals("Cache is inactive");
        }

        assert ex;

        cache1.active(true);

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return cache1.active() && cache2.active();
            }
        }, 5000);

        assert cache1.get(1).equals(1);
        assert cache1.get(2).equals(2);
    }
}
