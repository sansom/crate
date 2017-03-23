/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.operation.projectors;

import io.crate.analyze.symbol.Aggregation;
import io.crate.analyze.symbol.InputColumn;
import io.crate.breaker.RamAccountingContext;
import io.crate.data.*;
import io.crate.operation.aggregation.Aggregator;
import io.crate.operation.aggregation.impl.SumAggregation;
import io.crate.operation.collect.CollectExpression;
import io.crate.operation.collect.InputCollectExpression;
import io.crate.types.DataTypes;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class GroupingIntegerCollectorBenchmark {

    private static final RamAccountingContext RAM_ACCOUNTING_CONTEXT =
        new RamAccountingContext("dummy", new NoopCircuitBreaker(CircuitBreaker.FIELDDATA));

    private GroupingCollector groupBySumCollector;
    private List<Row> rows;

    @Setup
    public void createGroupingCollector() {
        groupBySumCollector = createGroupBySumCollector();

        rows = new ArrayList<>(20_000_000);
        for (int i = 0; i < 20_000_000; i++) {
            rows.add(new Row1(i % 200));
        }
    }

    private static GroupingCollector createGroupBySumCollector() {
        InputCollectExpression keyInput = new InputCollectExpression(0);
        List<Input<?>> keyInputs = Collections.singletonList(keyInput);
        CollectExpression[] collectExpressions = new CollectExpression[]{keyInput};

        SumAggregation sumAgg = new SumAggregation(DataTypes.INTEGER);
        Aggregation aggregation = Aggregation.finalAggregation(
            sumAgg.info(),
            Collections.singletonList(new InputColumn(0)),
            Aggregation.Step.ITER
        );

        Aggregator[] aggregators = new Aggregator[]{
            new Aggregator(
                RAM_ACCOUNTING_CONTEXT,
                aggregation,
                sumAgg,
                keyInput
            )
        };

        return GroupingCollector.singleKey(
            collectExpressions,
            aggregators,
            RAM_ACCOUNTING_CONTEXT,
            keyInputs.get(0),
            DataTypes.INTEGER
        );
    }

    @Benchmark
    public void measureGroupBySumInteger(Blackhole blackhole) throws Exception {
        BatchIterator rowsIterator = RowsBatchIterator.newInstance(rows, 1);
        blackhole.consume(BatchRowVisitor.visitRows(rowsIterator, groupBySumCollector).get());
    }
}
