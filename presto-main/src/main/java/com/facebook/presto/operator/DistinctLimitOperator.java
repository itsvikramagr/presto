/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator;

import com.facebook.presto.spi.block.BlockCursor;
import com.facebook.presto.spi.type.Type;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Arrays;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class DistinctLimitOperator
        implements Operator
{
    public static class DistinctLimitOperatorFactory
            implements OperatorFactory
    {
        private final int operatorId;
        private final List<Type> types;
        private final long limit;
        private boolean closed;

        public DistinctLimitOperatorFactory(int operatorId, List<? extends Type> types, long limit)
        {
            this.operatorId = operatorId;
            this.types = ImmutableList.copyOf(checkNotNull(types, "types is null"));
            checkArgument(limit >= 0, "limit must be at least zero");
            this.limit = limit;
        }

        @Override
        public List<Type> getTypes()
        {
            return types;
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, DistinctLimitOperator.class.getSimpleName());
            return new DistinctLimitOperator(operatorContext, types, limit);
        }

        @Override
        public void close()
        {
            closed = true;
        }
    }

    private final OperatorContext operatorContext;
    private final List<Type> types;
    private final BlockCursor[] cursors;

    private final PageBuilder pageBuilder;
    private Page outputPage;
    private long remainingLimit;

    private boolean finishing;

    private final GroupByHash groupByHash;
    private long nextDistinctId;

    public DistinctLimitOperator(OperatorContext operatorContext, List<Type> types, long limit)
    {
        this.operatorContext = checkNotNull(operatorContext, "operatorContext is null");
        this.types = ImmutableList.copyOf(checkNotNull(types, "types is null"));
        checkArgument(limit >= 0, "limit must be at least zero");

        ImmutableList.Builder<Type> distinctTypes = ImmutableList.builder();
        ImmutableList.Builder<Integer> distinctChannels = ImmutableList.builder();
        for (int i = 0; i < types.size(); i++) {
            distinctTypes.add(types.get(i));
            distinctChannels.add(i);
        }

        this.groupByHash = new GroupByHash(distinctTypes.build(), Ints.toArray(distinctChannels.build()), Math.min((int) limit, 10_000));

        this.cursors = new BlockCursor[types.size()];
        this.pageBuilder = new PageBuilder(getTypes());
        remainingLimit = limit;
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public List<Type> getTypes()
    {
        return types;
    }

    @Override
    public void finish()
    {
        finishing = true;
        Arrays.fill(cursors, null);
        pageBuilder.reset();
    }

    @Override
    public boolean isFinished()
    {
        return (finishing && outputPage == null) || (remainingLimit == 0 && outputPage == null);
    }

    @Override
    public ListenableFuture<?> isBlocked()
    {
        return NOT_BLOCKED;
    }

    @Override
    public boolean needsInput()
    {
        operatorContext.setMemoryReservation(groupByHash.getEstimatedSize());
        return !finishing && remainingLimit > 0 && outputPage == null;
    }

    @Override
    public void addInput(Page page)
    {
        checkState(needsInput());
        operatorContext.setMemoryReservation(groupByHash.getEstimatedSize());

        // open cursors
        for (int i = 0; i < page.getChannelCount(); i++) {
            cursors[i] = page.getBlock(i).cursor();
        }
        pageBuilder.reset();

        GroupByIdBlock ids = groupByHash.getGroupIds(page);
        for (int i = 0; i < ids.getPositionCount(); i++) {
            checkState(advanceNextCursorPosition());
            if (ids.getGroupId(i) == nextDistinctId) {
                for (int j = 0; j < cursors.length; j++) {
                    cursors[j].appendTo(pageBuilder.getBlockBuilder(j));
                }
                remainingLimit--;
                nextDistinctId++;
                if (remainingLimit == 0) {
                    break;
                }
            }
        }
        if (!pageBuilder.isEmpty()) {
            outputPage = pageBuilder.build();
        }
    }

    private boolean advanceNextCursorPosition()
    {
        // advance all cursors
        boolean advanced = cursors[0].advanceNextPosition();
        for (int i = 1; i < cursors.length; i++) {
            checkState(advanced == cursors[i].advanceNextPosition());
        }

        if (!advanced) {
            Arrays.fill(cursors, null);
        }

        return advanced;
    }

    @Override
    public Page getOutput()
    {
        Page result = outputPage;
        outputPage = null;
        return result;
    }
}
