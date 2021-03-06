/**
 * Copyright (C) 2013-2015 VCNC Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kr.co.vcnc.haeinsa;

import java.util.List;
import java.util.Map;
import java.util.NavigableSet;

import kr.co.vcnc.haeinsa.thrift.generated.TRowLock;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

/**
 * Contains Transaction information of single row. This information is only
 * saved in client memory until {@link HaeinsaTransaction#commit()} called.
 */
class HaeinsaRowTransaction {
    // current RowLock saved in HBase. null if there is no lock at all.
    private TRowLock current;
    // mutations will be saved in order of executions.
    // If this rowTransaction is created during recovering failed transaction by other client,
    // following mutations variable is empty.
    private final List<HaeinsaMutation> mutations = Lists.newArrayList();
    private final HaeinsaTableTransaction tableTransaction;

    HaeinsaRowTransaction(HaeinsaTableTransaction tableTransaction) {
        this.tableTransaction = tableTransaction;
    }

    public TRowLock getCurrent() {
        return current;
    }

    public void setCurrent(TRowLock current) {
        this.current = current;
    }

    public List<HaeinsaMutation> getMutations() {
        return mutations;
    }

    public void merge() {
        if (mutations.size() <= 1) {
            return;
        }

        byte[] row = mutations.get(0).getRow();

        MutationMerger merger = new MutationMerger(row);

        for (HaeinsaMutation mutation : mutations) {
            if (mutation instanceof HaeinsaPut) {
                merger.merge((HaeinsaPut) mutation);
            } else if (mutation instanceof HaeinsaDelete) {
                merger.merge((HaeinsaDelete) mutation);
            } else {
                throw new IllegalStateException();
            }
        }

        mutations.clear();
        mutations.addAll(merger.toMutations());
    }

    public int getIterationCount() {
        if (mutations.size() > 0) {
            if (mutations.get(0) instanceof HaeinsaPut) {
                return mutations.size();
            } else {
                return mutations.size() + 1;
            }
        }
        return 1;
    }

    public void addMutation(HaeinsaMutation mutation) {
        if (mutations.size() <= 0) {
            mutations.add(mutation);
        } else {
            HaeinsaMutation lastMutation = mutations.get(mutations.size() - 1);
            if (lastMutation.getClass() != mutation.getClass()) {
                // not going to happen before any other type of HaeinsaMutation added.
                mutations.add(mutation);
            } else {
                lastMutation.add(mutation);
            }
        }
    }

    @VisibleForTesting
    static FilterResult filter(HaeinsaDeleteTracker deleteTracker, HaeinsaPut put) {
        FilterResult filterResult = new FilterResult(put.getRow());
        for (Map.Entry<byte[], NavigableSet<HaeinsaKeyValue>> entry : put.getFamilyMap().entrySet()) {
            for (HaeinsaKeyValue keyValue : entry.getValue()) {
                if (deleteTracker.isDeleted(keyValue)) {
                    filterResult.getDeleted().add(keyValue.getFamily(), keyValue.getQualifier(), keyValue.getValue());
                } else {
                    filterResult.getRemained().add(keyValue.getFamily(), keyValue.getQualifier(), keyValue.getValue());
                }
            }
        }
        return filterResult;
    }

    public HaeinsaTableTransaction getTableTransaction() {
        return tableTransaction;
    }

    /**
     * Return list of {@link HaeinsaKeyValueScanner}s which wrap mutations
     * (Put and Delete) contained inside instance. Also assign sequenceID to every
     * MutationScanner of {@link HaeinsaMutation}.
     */
    public List<HaeinsaKeyValueScanner> getScanners() {
        List<HaeinsaKeyValueScanner> result = Lists.newArrayList();
        for (int i = 0; i < mutations.size(); i++) {
            HaeinsaMutation mutation = mutations.get(i);
            result.add(mutation.getScanner(mutations.size() - i));
        }
        return result;
    }

    @VisibleForTesting
    static final class MutationMerger {
        private final byte[] row;
        private HaeinsaPut firstMutationPut;
        private HaeinsaDelete secondMutationDelete;
        private HaeinsaPut lastMutationPut;

        public MutationMerger(byte[] row) {
            this.row = row;
            this.firstMutationPut = new HaeinsaPut(row);
            this.secondMutationDelete = new HaeinsaDelete(row);
            this.lastMutationPut = new HaeinsaPut(row);
        }

        @VisibleForTesting
        public byte[] getRow() {
            return row;
        }

        @VisibleForTesting
        public HaeinsaPut getFirstMutationPut() {
            return firstMutationPut;
        }

        @VisibleForTesting
        public HaeinsaDelete getSecondMutationDelete() {
            return secondMutationDelete;
        }

        @VisibleForTesting
        public HaeinsaPut getLastMutationPut() {
            return lastMutationPut;
        }

        @VisibleForTesting
        void merge(HaeinsaPut put) {
            secondMutationDelete.remove(put);
            HaeinsaDeleteTracker deleteTracker = new HaeinsaDeleteTracker(secondMutationDelete);
            FilterResult filterResult = filter(deleteTracker, put);
            lastMutationPut.add(filterResult.getDeleted());
            firstMutationPut.add(filterResult.getRemained());
            if (secondMutationDelete.isEmpty() && !lastMutationPut.isEmpty()) {
                firstMutationPut.add(lastMutationPut);
                lastMutationPut = new HaeinsaPut(row);
            }
        }

        @VisibleForTesting
        void merge(HaeinsaDelete delete) {
            HaeinsaDeleteTracker deleteTracker = new HaeinsaDeleteTracker(delete);

            FilterResult filterResult = filter(deleteTracker, lastMutationPut);
            lastMutationPut = filterResult.getRemained();

            secondMutationDelete.add(delete);

            filterResult = filter(deleteTracker, firstMutationPut);
            firstMutationPut = filterResult.getRemained();
        }

        List<HaeinsaMutation> toMutations() {
            List<HaeinsaMutation> result = Lists.newArrayListWithCapacity(3);
            if (!firstMutationPut.isEmpty()) {
                result.add(firstMutationPut);
            }
            if (!secondMutationDelete.isEmpty()) {
                result.add(secondMutationDelete);
            }
            if (!lastMutationPut.isEmpty()) {
                result.add(lastMutationPut);
            }
            return result;
        }
    }

    @VisibleForTesting
    static final class FilterResult {
        private final HaeinsaPut remained;
        private final HaeinsaPut deleted;

        private FilterResult(byte[] row) {
            this.remained = new HaeinsaPut(row);
            this.deleted = new HaeinsaPut(row);
        }

        public HaeinsaPut getRemained() {
            return remained;
        }

        public HaeinsaPut getDeleted() {
            return deleted;
        }
    }
}
