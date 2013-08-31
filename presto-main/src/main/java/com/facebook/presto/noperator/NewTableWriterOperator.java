package com.facebook.presto.noperator;

import com.facebook.presto.block.Block;
import com.facebook.presto.block.BlockBuilder;
import com.facebook.presto.execution.TaskMemoryManager;
import com.facebook.presto.execution.TaskOutput;
import com.facebook.presto.metadata.ColumnFileHandle;
import com.facebook.presto.metadata.LocalStorageManager;
import com.facebook.presto.operator.OperatorStats;
import com.facebook.presto.operator.Page;
import com.facebook.presto.operator.TableWriterResult;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.Split;
import com.facebook.presto.split.NativeSplit;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.facebook.presto.tuple.TupleInfo;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.facebook.presto.tuple.TupleInfo.SINGLE_LONG;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class NewTableWriterOperator
        implements NewSourceOperator
{
    private final List<ColumnHandle> columnHandles;

    public static class NewTableWriterOperatorFactory
            implements NewSourceOperatorFactory
    {
        private final PlanNodeId sourceId;
        private final LocalStorageManager storageManager;
        private final String nodeIdentifier;
        private final List<ColumnHandle> columnHandles;

        public NewTableWriterOperatorFactory(
                PlanNodeId sourceId,
                LocalStorageManager storageManager,
                String nodeIdentifier,
                List<ColumnHandle> columnHandles)
        {
            this.sourceId = checkNotNull(sourceId, "sourceId is null");
            this.storageManager = checkNotNull(storageManager, "storageManager is null");
            this.nodeIdentifier = checkNotNull(nodeIdentifier, "nodeIdentifier is null");
            this.columnHandles = ImmutableList.copyOf(checkNotNull(columnHandles, "columnHandles is null"));
        }

        @Override
        public PlanNodeId getSourceId()
        {
            return sourceId;
        }

        @Override
        public List<TupleInfo> getTupleInfos()
        {
            return ImmutableList.of(SINGLE_LONG);
        }

        @Override
        public NewSourceOperator createOperator(OperatorStats operatorStats, TaskMemoryManager taskMemoryManager)
        {
            try {
                return new NewTableWriterOperator(sourceId, storageManager, nodeIdentifier, columnHandles);
            }
            catch (IOException e) {
                throw Throwables.propagate(e);
            }
        }

        @Override
        public void close()
        {
        }
    }

    private enum State
    {
        RUNNING, FINISHING, FINISHED
    }

    private final PlanNodeId sourceId;
    private final LocalStorageManager storageManager;
    private final String nodeIdentifier;

    private ColumnFileHandle columnFileHandle;

    private final AtomicReference<TaskOutput> taskOutput = new AtomicReference<>();
    private final AtomicReference<NativeSplit> input = new AtomicReference<>();

    private State state = State.RUNNING;
    private long rowCount;

    public NewTableWriterOperator(
            PlanNodeId sourceId,
            LocalStorageManager storageManager,
            String nodeIdentifier,
            List<ColumnHandle> columnHandles)
            throws IOException
    {
        this.sourceId = checkNotNull(sourceId, "sourceId is null");
        this.storageManager = checkNotNull(storageManager, "storageManager is null");
        this.nodeIdentifier = checkNotNull(nodeIdentifier, "nodeIdentifier is null");
        this.columnHandles = ImmutableList.copyOf(columnHandles);
    }

    @Override
    public PlanNodeId getSourceId()
    {
        return sourceId;
    }

    public void setTaskOutput(TaskOutput taskOutput)
    {
        this.taskOutput.set(taskOutput);
    }

    @Override
    public void addSplit(Split split)
    {
        checkNotNull(split, "split is null");
        checkState(split instanceof NativeSplit, "Non-native split added!");
        checkState(input.get() == null, "Shard Id %s was already set!", input.get());
        input.set((NativeSplit) split);
    }

    @Override
    public void noMoreSplits()
    {
        checkState(input.get() != null, "No shard id was set!");
    }

    @Override
    public List<TupleInfo> getTupleInfos()
    {
        return ImmutableList.of(SINGLE_LONG);
    }

    @Override
    public void finish()
    {
        if (state == State.RUNNING) {
            state = State.FINISHING;
        }
    }

    @Override
    public boolean isFinished()
    {
        return state == State.FINISHED;
    }

    @Override
    public ListenableFuture<?> isBlocked()
    {
        return NOT_BLOCKED;
    }

    @Override
    public boolean needsInput()
    {
        return state == State.RUNNING;
    }

    @Override
    public void addInput(Page page)
    {
        checkNotNull(page, "page is null");
        checkState(state == State.RUNNING, "Operator is finishing");
        if (columnFileHandle == null) {
            try {
                columnFileHandle = storageManager.createStagingFileHandles(input.get().getShardId(), columnHandles);
            }
            catch (IOException e) {
                throw Throwables.propagate(e);
            }
        }
        rowCount += columnFileHandle.append(page);
    }

    @Override
    public Page getOutput()
    {
        if (state != State.FINISHING) {
            return null;
        }

        state = State.FINISHED;

        if (columnFileHandle != null) {
            try {
                storageManager.commit(columnFileHandle);
            }
            catch (IOException e) {
                throw Throwables.propagate(e);
            }

            TaskOutput taskOutput = this.taskOutput.get();
            checkState(taskOutput != null, "TaskOutput not set");
            taskOutput.addOutput(sourceId, ImmutableSet.of(new TableWriterResult(input.get().getShardId(), nodeIdentifier)));
        }

        Block block = new BlockBuilder(SINGLE_LONG).append(rowCount).build();
        return new Page(block);
    }
}