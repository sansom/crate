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
import io.crate.data.Input;
import io.crate.data.Row;
import io.crate.data.Row1;
import io.crate.operation.aggregation.AggregationFunction;
import io.crate.operation.aggregation.Aggregator;
import io.crate.operation.aggregation.impl.SumAggregation;
import io.crate.operation.collect.CollectExpression;
import io.crate.operation.collect.InputCollectExpression;
import io.crate.types.DataTypes;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.openjdk.jmh.annotations.*;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class AggregateCollectorBenchmark {

    private final RamAccountingContext RAM_ACCOUNTING_CONTEXT =
        new RamAccountingContext("dummy", new NoopCircuitBreaker("dummy"));
    private final List<Row> rows = IntStream.range(0, 10_000).mapToObj(Row1::new).collect(Collectors.toList());

    private AggregateCollectorNew colllectorNew;
    private AggregateCollector colllector;

    @Setup
    public void setup() {
        InputCollectExpression inExpr0 = new InputCollectExpression(0);

        SumAggregation sumAggregation = new SumAggregation(DataTypes.INTEGER);

        Aggregator[] aggregators = new Aggregator[1];
        aggregators[0] = new Aggregator(
            RAM_ACCOUNTING_CONTEXT,
            Aggregation.finalAggregation(
                sumAggregation.info(),
                Collections.singletonList(new InputColumn(0, DataTypes.INTEGER)),
                Aggregation.Step.ITER
            ),
            sumAggregation,
            inExpr0
        );
        colllector = new AggregateCollector(Collections.singletonList(inExpr0), aggregators);
        colllectorNew = new AggregateCollectorNew(
            Collections.singletonList(inExpr0),
            RAM_ACCOUNTING_CONTEXT,
            Aggregation.Step.ITER,
            Aggregation.Step.FINAL,
            new AggregationFunction[]{ sumAggregation },
            new Input[] { inExpr0 }
        );
    }

    @Benchmark
    public Object[] measureAggregateCollector() {
        Object[] state = colllector.supplier().get();
        BiConsumer<Object[], Row> accumulator = colllector.accumulator();
        Function<Object[], Object[]> finisher = colllector.finisher();
        for (int i = 0; i < rows.size(); i++) {
            accumulator.accept(state, rows.get(i));
        }
        return finisher.apply(state);
    }

    @Benchmark
    public Object[] measureAggregateCollectorNew() {
        Object[] state = colllectorNew.supplier().get();
        BiConsumer<Object[], Row> accumulator = colllectorNew.accumulator();
        Function<Object[], Object[]> finisher = colllectorNew.finisher();
        for (int i = 0; i < rows.size(); i++) {
            accumulator.accept(state, rows.get(i));
        }
        return finisher.apply(state);
    }

    public class AggregateCollectorNew implements Collector<Row, Object[], Object[]> {

        private final List<? extends CollectExpression<Row, ?>> expressions;
        private final RamAccountingContext ramAccountingContext;
        private final AggregationFunction[] functions;
        private final Input[][] inputs;
        private final BiConsumer<Object[], Row> accumulator;

        public AggregateCollectorNew(List<? extends CollectExpression<Row, ?>> expressions,
                                     RamAccountingContext ramAccountingContext,
                                     Aggregation.Step from,
                                     Aggregation.Step to,
                                     AggregationFunction[] functions,
                                     Input[]... inputs) {
            this.expressions = expressions;
            this.ramAccountingContext = ramAccountingContext;
            this.functions = functions;
            this.inputs = inputs;
            if (from == Aggregation.Step.ITER) {
                accumulator = this::iterate;
            } else {
                accumulator = this::reduce;
            }
        }


        @Override
        public Supplier<Object[]> supplier() {
            return this::prepareState;
        }

        @Override
        public BiConsumer<Object[], Row> accumulator() {
            return accumulator;
        }

        @Override
        public BinaryOperator<Object[]> combiner() {
            return (state1, state2) -> { throw new UnsupportedOperationException("combine not supported"); };
        }

        @Override
        public Function<Object[], Object[]> finisher() {
            return this::finishCollect;
        }

        @Override
        public Set<Characteristics> characteristics() {
            return Collections.emptySet();
        }

        private Object[] prepareState() {
            Object[] states = new Object[functions.length];
            for (int i = 0; i < functions.length; i++) {
                states[i] = functions[i].newState(ramAccountingContext);
            }
            return states;
        }

        private void iterate(Object[] state, Row row) {
            setRow(row);
            for (int i = 0; i < functions.length; i++) {
                state[i] = functions[i].iterate(ramAccountingContext, state[i], inputs[i]);
            }
        }

        private void reduce(Object[] state, Row row) {
            setRow(row);
            for (int i = 0; i < functions.length; i++) {
                state[i] = functions[i].reduce(ramAccountingContext, state[i], inputs[i][0].value());
            }
        }

        private void setRow(Row row) {
            for (int i = 0, expressionsSize = expressions.size(); i < expressionsSize; i++) {
                expressions.get(i).setNextRow(row);
            }
        }

        private Object[] finishCollect(Object[] state) {
            for (int i = 0; i < functions.length; i++) {
                state[i] = functions[i].terminatePartial(ramAccountingContext, state[i]);
            }
            return state;
        }
    }
}
