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
package com.facebook.presto.benchmark;

import com.facebook.presto.benchmark.HandTpchQuery6.TpchQuery6Operator.TpchQuery6OperatorFactory;
import com.facebook.presto.operator.AggregationOperator.AggregationOperatorFactory;
import com.facebook.presto.operator.DriverContext;
import com.facebook.presto.operator.Operator;
import com.facebook.presto.operator.OperatorContext;
import com.facebook.presto.operator.OperatorFactory;
import com.facebook.presto.operator.Page;
import com.facebook.presto.operator.PageBuilder;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockCursor;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.planner.plan.AggregationNode.Step;
import com.facebook.presto.sql.tree.Input;
import com.facebook.presto.testing.LocalQueryRunner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import java.util.List;

import static com.facebook.presto.benchmark.BenchmarkQueryRunner.createLocalQueryRunner;
import static com.facebook.presto.operator.AggregationFunctionDefinition.aggregation;
import static com.facebook.presto.operator.aggregation.DoubleSumAggregation.DOUBLE_SUM;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkState;

public class HandTpchQuery6
        extends AbstractSimpleOperatorBenchmark
{
    public HandTpchQuery6(LocalQueryRunner localQueryRunner)
    {
        super(localQueryRunner, "hand_tpch_query_6", 10, 100);
    }

    @Override
    protected List<? extends OperatorFactory> createOperatorFactories()
    {
        // select sum(extendedprice * discount) as revenue
        // from lineitem
        // where shipdate >= '1994-01-01'
        //    and shipdate < '1995-01-01'
        //    and discount >= 0.05
        //    and discount <= 0.07
        //    and quantity < 24;
        OperatorFactory tableScanOperator = createTableScanOperator(0, "lineitem", "extendedprice", "discount", "shipdate", "quantity");

        TpchQuery6OperatorFactory tpchQuery6Operator = new TpchQuery6OperatorFactory(1);

        AggregationOperatorFactory aggregationOperator = new AggregationOperatorFactory(
                2,
                Step.SINGLE,
                ImmutableList.of(
                        aggregation(DOUBLE_SUM, ImmutableList.of(new Input(0)), Optional.<Input>absent(), Optional.<Input>absent(), 1.0)
                ));

        return ImmutableList.of(tableScanOperator, tpchQuery6Operator, aggregationOperator);
    }

    public static class TpchQuery6Operator
            extends com.facebook.presto.operator.AbstractFilterAndProjectOperator
    {
        public static class TpchQuery6OperatorFactory
                implements OperatorFactory
        {
            private final int operatorId;

            public TpchQuery6OperatorFactory(int operatorId)
            {
                this.operatorId = operatorId;
            }

            @Override
            public List<Type> getTypes()
            {
                return ImmutableList.<Type>of(DOUBLE);
            }

            @Override
            public Operator createOperator(DriverContext driverContext)
            {
                OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, TpchQuery6Operator.class.getSimpleName());
                return new TpchQuery6Operator(operatorContext);
            }

            @Override
            public void close()
            {
            }
        }

        private static final Slice MIN_SHIP_DATE = Slices.copiedBuffer("1994-01-01", UTF_8);
        private static final Slice MAX_SHIP_DATE = Slices.copiedBuffer("1995-01-01", UTF_8);

        public TpchQuery6Operator(OperatorContext operatorContext)
        {
            super(operatorContext, ImmutableList.of(DOUBLE));
        }

        @Override
        protected void filterAndProjectRowOriented(Page page, PageBuilder pageBuilder)
        {
            filterAndProjectRowOriented(pageBuilder, page.getBlock(0), page.getBlock(1), page.getBlock(2), page.getBlock(3));
        }

        private void filterAndProjectRowOriented(PageBuilder pageBuilder, Block extendedPriceBlock, Block discountBlock, Block shipDateBlock, Block quantityBlock)
        {
            int rows = extendedPriceBlock.getPositionCount();

            BlockCursor extendedPriceCursor = extendedPriceBlock.cursor();
            BlockCursor discountCursor = discountBlock.cursor();
            BlockCursor shipDateCursor = shipDateBlock.cursor();
            BlockCursor quantityCursor = quantityBlock.cursor();

            for (int position = 0; position < rows; position++) {
                checkState(extendedPriceCursor.advanceNextPosition());
                checkState(discountCursor.advanceNextPosition());
                checkState(shipDateCursor.advanceNextPosition());
                checkState(quantityCursor.advanceNextPosition());

                // where shipdate >= '1994-01-01'
                //    and shipdate < '1995-01-01'
                //    and discount >= 0.05
                //    and discount <= 0.07
                //    and quantity < 24;
                if (filter(discountCursor, shipDateCursor, quantityCursor)) {
                    project(pageBuilder, extendedPriceCursor, discountCursor);
                }
            }

            checkState(!extendedPriceCursor.advanceNextPosition());
            checkState(!discountCursor.advanceNextPosition());
            checkState(!shipDateCursor.advanceNextPosition());
            checkState(!quantityCursor.advanceNextPosition());
        }

        private void project(PageBuilder pageBuilder, BlockCursor extendedPriceCursor, BlockCursor discountCursor)
        {
            if (discountCursor.isNull() || extendedPriceCursor.isNull()) {
                pageBuilder.getBlockBuilder(0).appendNull();
            }
            else {
                pageBuilder.getBlockBuilder(0).appendDouble(extendedPriceCursor.getDouble() * discountCursor.getDouble());
            }
        }

        private boolean filter(BlockCursor discountCursor, BlockCursor shipDateCursor, BlockCursor quantityCursor)
        {
            return !shipDateCursor.isNull() && shipDateCursor.getSlice().compareTo(MIN_SHIP_DATE) >= 0 &&
                    !shipDateCursor.isNull() && shipDateCursor.getSlice().compareTo(MAX_SHIP_DATE) < 0 &&
                    !discountCursor.isNull() && discountCursor.getDouble() >= 0.05 &&
                    !discountCursor.isNull() && discountCursor.getDouble() <= 0.07 &&
                    !quantityCursor.isNull() && quantityCursor.getLong() < 24;
        }
    }

    public static void main(String[] args)
    {
        new HandTpchQuery6(createLocalQueryRunner()).runBenchmark(new SimpleLineBenchmarkResultWriter(System.out));
    }
}
