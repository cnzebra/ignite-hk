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

package org.apache.ignite.internal.sql.optimizer.affinity;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.internal.util.tostring.GridToStringExclude;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.S;

/**
 * Node with partition(s) which should be extracted from argument.
 *
 * HK-PATCHED: add support to ARRAY arguments (compute multiple partitions per argument)
 */
public class PartitionParameterNode extends PartitionSingleNode {
    /** Indexing. */
    @GridToStringExclude
    private final PartitionResolver partRslvr;

    /** Index. */
    private final int idx;

    /** Parameter data type. */
    private final int type;

    /** Client parameter type. */
    private final PartitionParameterType clientType;

    /**
     * Constructor.
     *
     * @param tbl Table descriptor.
     * @param partRslvr Partition resolver.
     * @param idx Parameter index.
     * @param type Parameter data type.
     * @param clientType Mapped parameter type to be used by thin clients.
     */
    public PartitionParameterNode(PartitionTable tbl, PartitionResolver partRslvr, int idx, int type,
        PartitionParameterType clientType) {
        super(tbl);

        this.partRslvr = partRslvr;
        this.idx = idx;
        this.type = type;
        this.clientType = clientType;
    }

    /** {@inheritDoc} */
    @Override public Set<Integer> applySingle(PartitionClientContext cliCtx, Object... args) throws IgniteCheckedException {
        assert args != null;
        assert idx < args.length;

        Object arg = args[idx];

        if (cliCtx != null) {
            Integer part = cliCtx.partition(arg, clientType);
            if (part == null) {
                return null;
            }
            return Collections.singleton(part);
        } else {
            assert partRslvr != null;
            int[] partitions =  partRslvr.partitions(
                arg,
                type,
                tbl.cacheName()
            );
            if (F.isEmpty(partitions)){
                return Collections.emptySet();
            }
            return Arrays.stream(partitions).mapToObj(i->i).collect(Collectors.toSet());
        }
    }

    /** {@inheritDoc} */
    @Override public boolean constant() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public int value() {
        return idx;
    }

    /**
     * @return Parameter data type.
     */
    public int type() {
        return type;
    }

    /**
     * @return Client parameter type.
     */
    public PartitionParameterType clientType() {
        return clientType;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(PartitionParameterNode.class, this);
    }
}
