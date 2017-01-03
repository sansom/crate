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

package io.crate.executor.transport.distributed

import io.crate.Streamer
import io.crate.core.collections.Bucket
import io.crate.core.collections.Row
import io.crate.operation.projectors.*
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import org.elasticsearch.action.ActionListener
import org.elasticsearch.common.logging.ESLogger
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference


class FutureListener<T> : CompletableFuture<T>(), ActionListener<T> {

  override fun onFailure(e: Throwable?) {
    super.completeExceptionally(e)
  }

  override fun onResponse(response: T) {
    super.complete(response)
  }
}

class DistributingDownstream2(logger: ESLogger,
                              jobId: UUID,
                              multiBucketBuilder:MultiBucketBuilder,
                              targetPhaseId:Int,
                              inputId:Byte,
                              bucketIdx:Int,
                              downstreamNodeIds:Collection<String>,
                              distributedResultAction:TransportDistributedResultAction,
                              streamers:Array<Streamer<*>>,
                              pageSize:Int):RowReceiver {

  private val logger:ESLogger
  internal val multiBucketBuilder:MultiBucketBuilder
  private val jobId:UUID
  private val targetPhaseId:Int
  private val inputId:Byte
  private val bucketIdx:Int
  private val pageSize:Int
  private val downstreams:ArrayList<Downstream> = ArrayList(downstreamNodeIds.count())
  private val distributedResultAction:TransportDistributedResultAction
  private val streamers:Array<Streamer<*>>
  private val buckets:Array<Bucket?>
  private val traceEnabled:Boolean
  private var upstreamFinished:Boolean = false
  private val failure = AtomicReference<Throwable>(null)

  private var isKilled = false
  @Volatile private var stop = false

  init{
    this.logger = logger
    this.multiBucketBuilder = multiBucketBuilder
    this.jobId = jobId
    this.targetPhaseId = targetPhaseId
    this.inputId = inputId
    this.bucketIdx = bucketIdx
    this.pageSize = pageSize
    this.streamers = streamers
    buckets = arrayOfNulls<Bucket>(downstreamNodeIds.count())
    this.distributedResultAction = distributedResultAction
    for (downstreamNodeId in downstreamNodeIds)
    {
      downstreams.add(Downstream(downstreamNodeId))
    }
    traceEnabled = logger.isTraceEnabled()
  }

  override fun setNextRow(row:Row): RowReceiver.Result {
    if (stop) {
      return RowReceiver.Result.STOP
    }
    multiBucketBuilder.add(row)
    if (multiBucketBuilder.size() >= pageSize) {
      multiBucketBuilder.build(buckets)
      sendRequests()
      if (downstreams.all { it.isFinished }) {
        return RowReceiver.Result.STOP
      }
    }
    return RowReceiver.Result.CONTINUE
  }

  private fun sendRequests() {
    val results = ArrayList<FutureListener<DistributedResultResponse>>()

    async {
      for (i in 0..downstreams.size - 1) {
        val downstream = downstreams[i]
        if (downstream.isFinished) {
          continue
        }

        val bucket = buckets[i]
        val listenerFuture = FutureListener<DistributedResultResponse>()
        listenerFuture.
                whenComplete { t, u ->
                  if (t != null) {
                    downstream.isFinished = !t.needMore()
                  }
                }
        results.add(listenerFuture)

        distributedResultAction.pushResult(
                downstream.nodeId,
                DistributedResultRequest(
                        jobId, targetPhaseId, inputId, bucketIdx, streamers, bucket, false),
                listenerFuture
        )
      }

      for (resultFuture in results) {
        val result = resultFuture.await()
      }
    }
  }

  private fun traceLog(msg:String) {
    if (traceEnabled)
    {
      logger.trace("targetPhase={}/{} bucketIdx={} " + msg, targetPhaseId, inputId, bucketIdx)
    }
  }

  override fun pauseProcessed(resumeable:ResumeHandle) {
  }

  override fun finish(repeatable:RepeatHandle) {
    traceLog("action=finish")
    multiBucketBuilder.build(buckets)
    for (i in 0..downstreams.size - 1) {
      val downstream = downstreams[i]
      if (downstream.isFinished) {
        continue
      }

      val bucket = buckets[i]
      val listenerFuture = FutureListener<DistributedResultResponse>()

      distributedResultAction.pushResult(
              downstream.nodeId,
              DistributedResultRequest(
                      jobId, targetPhaseId, inputId, bucketIdx, streamers, bucket, true),
              listenerFuture
      )
    }
  }

  override fun fail(throwable:Throwable) {
    traceLog("action=fail")
  }


  override fun kill(throwable:Throwable) {
    stop = true
    // forward kill reason to downstream if not already done, otherwise downstream may wait forever for a final result
    if (failure.compareAndSet(null, throwable)) {
      upstreamFinished = true
      isKilled = true
    }
  }

  override fun requirements():Set<Requirement> {
    return Requirements.NO_REQUIREMENTS
  }

  private inner class Downstream internal constructor(downstreamNodeId:String) {
    val nodeId:String
    internal var isFinished = false

    init{
      this.nodeId = downstreamNodeId
    }
  }
}

