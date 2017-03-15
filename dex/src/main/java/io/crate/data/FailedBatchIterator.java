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

package io.crate.data;

import io.crate.concurrent.CompletableFutures;
import io.crate.exceptions.Exceptions;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.concurrent.CompletionStage;

public class FailedBatchIterator implements BatchIterator {

    private static final ListBackedColumns EMPTY_COLUMNS = new ListBackedColumns(Collections.emptyList());
    private final Throwable failure;

    public FailedBatchIterator(Throwable failure) {
        this.failure = failure;
    }

    @Override
    public void kill(@Nonnull Throwable throwable) {
    }

    @Override
    public Columns rowData() {
        return EMPTY_COLUMNS;
    }

    @Override
    public void moveToStart() {
        Exceptions.rethrowUnchecked(failure);
    }

    @Override
    public boolean moveNext() {
        Exceptions.rethrowUnchecked(failure);
        return false;
    }

    @Override
    public void close() {
    }

    @Override
    public CompletionStage<?> loadNextBatch() {
        return CompletableFutures.failedFuture(failure);
    }

    @Override
    public boolean allLoaded() {
        Exceptions.rethrowUnchecked(failure);
        return true;
    }
}
