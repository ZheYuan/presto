package com.facebook.presto.operator.aggregation;

import com.facebook.presto.block.Block;
import com.facebook.presto.block.BlockBuilder;
import com.facebook.presto.block.BlockCursor;
import com.facebook.presto.tuple.TupleInfo;
import com.google.common.base.Preconditions;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.SliceInput;
import io.airlift.stats.QuantileDigest;

public class DoubleApproximatePercentileAggregation
    implements VariableWidthAggregationFunction<DoubleApproximatePercentileAggregation.DigestAndPercentile>
{
    public static final DoubleApproximatePercentileAggregation INSTANCE = new DoubleApproximatePercentileAggregation();

    @Override
    public DigestAndPercentile initialize()
    {
        return new DigestAndPercentile(new QuantileDigest(0.01));
    }

    @Override
    public DigestAndPercentile addInput(int positionCount, Block[] blocks, int[] fields, DigestAndPercentile currentValue)
    {
        BlockCursor valueCursor = blocks[0].cursor();
        int valueField = fields[0];
        while (valueCursor.advanceNextPosition()) {
            if (!valueCursor.isNull(valueField)) {
                double value = valueCursor.getDouble(valueField);

                currentValue.digest.add(doubleToSortableLong(value));
            }
        }

        BlockCursor percentileCursor = blocks[1].cursor();
        int percentileField = fields[1];
        if (percentileCursor.advanceNextPosition()) {
            if (!percentileCursor.isNull(percentileField)) {
                currentValue.percentile = percentileCursor.getDouble(percentileField);
            }
        }

        return currentValue;
    }

    @Override
    public DigestAndPercentile addInput(BlockCursor[] cursors, int[] fields, DigestAndPercentile currentValue)
    {
        if (!cursors[0].isNull(fields[0])) {
            double value = cursors[0].getDouble(fields[0]);
            currentValue.digest.add(doubleToSortableLong(value));
        }

        if (!cursors[1].isNull(fields[1])) {
            currentValue.percentile = cursors[1].getDouble(fields[1]);
        }

        return currentValue;
    }

    @Override
    public DigestAndPercentile addIntermediate(BlockCursor[] cursors, int[] fields, DigestAndPercentile currentValue)
    {
        if (!cursors[0].isNull(fields[0])) {
            SliceInput input = cursors[0].getSlice(fields[0]).getInput();

            currentValue.digest.merge(QuantileDigest.deserialize(input));
            currentValue.percentile = input.readDouble();
        }

        return currentValue;
    }

    @Override
    public void evaluateIntermediate(DigestAndPercentile currentValue, BlockBuilder output)
    {
        if (currentValue.digest.getCount() == 0.0) {
            output.appendNull();
        }
        else {
            DynamicSliceOutput sliceOutput = new DynamicSliceOutput(currentValue.digest.estimatedSerializedSizeInBytes());
            currentValue.digest.serialize(sliceOutput);
            sliceOutput.appendDouble(currentValue.percentile);

            output.append(sliceOutput.slice());
        }
    }

    @Override
    public void evaluateFinal(DigestAndPercentile currentValue, BlockBuilder output)
    {
        if (currentValue.digest.getCount() == 0.0) {
            output.appendNull();
        }
        else {
            Preconditions.checkState(currentValue.percentile != -1, "Percentile is missing");
            output.append(longToDouble(currentValue.digest.getQuantile(currentValue.percentile)));
        }
    }

    @Override
    public long estimateSizeInBytes(DigestAndPercentile value)
    {
        // TODO: account for DigestAndPercentile object
        return value.digest.estimatedInMemorySizeInBytes();
    }

    @Override
    public TupleInfo getFinalTupleInfo()
    {
        return TupleInfo.SINGLE_DOUBLE;
    }

    @Override
    public TupleInfo getIntermediateTupleInfo()
    {
        return TupleInfo.SINGLE_VARBINARY;
    }

    private static double longToDouble(long value)
    {
        if (value < 0) {
            value ^= 0x7fffffffffffffffL;
        }

        return Double.longBitsToDouble(value);
    }

    private static long doubleToSortableLong(double value)
    {
        long result = Double.doubleToRawLongBits(value);

        if (result < 0) {
            result ^= 0x7fffffffffffffffL;
        }

        return result;
    }

    public final static class DigestAndPercentile
    {
        private QuantileDigest digest;
        private double percentile = -1;

        public DigestAndPercentile(QuantileDigest digest)
        {
            this.digest = digest;
        }
    }
}