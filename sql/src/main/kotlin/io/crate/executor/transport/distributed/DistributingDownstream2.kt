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
import org.elasticsearch.action.ActionListener
import org.elasticsearch.common.logging.ESLogger
import org.elasticsearch.common.logging.Loggers
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
  private val inFlightRequests = AtomicInteger(0)
  private val lock = Any()
  private val downstreams:Array<Downstream?>
  private val distributedResultAction:TransportDistributedResultAction
  private val streamers:Array<Streamer<*>>
  private val buckets:Array<Bucket?>
  private val traceEnabled:Boolean
  private var upstreamFinished:Boolean = false
  private val resumeHandleRef = AtomicReference(ResumeHandle.INVALID)
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
    downstreams = arrayOfNulls<Downstream>(downstreamNodeIds.count())
    this.distributedResultAction = distributedResultAction
    var i = 0
    for (downstreamNodeId in downstreamNodeIds)
    {
      downstreams[i] = Downstream(downstreamNodeId)
      i++
    }
    traceEnabled = logger.isTraceEnabled()
  }

  override fun setNextRow(row:Row): RowReceiver.Result {
    if (stop) {
      return RowReceiver.Result.STOP
    }
    multiBucketBuilder.add(row)
    if (multiBucketBuilder.size() >= pageSize) {
      if (inFlightRequests.get() === 0) {
        trySendRequests()
      }
      else
      {
            /*
     * trySendRequests is called after pause has been processed to avoid race condition:
     * ( trySendRequest -> return PAUSE -> onResponse before pauseProcessed)
     */
        return RowReceiver.Result.PAUSE
      }
    }
    return RowReceiver.Result.CONTINUE
  }

  private fun traceLog(msg:String) {
    if (traceEnabled)
    {
      logger.trace("targetPhase={}/{} bucketIdx={} " + msg, targetPhaseId, inputId, bucketIdx)
    }
  }

  override fun pauseProcessed(resumeable:ResumeHandle) {
    if (resumeHandleRef.compareAndSet(ResumeHandle.INVALID, resumeable))
    {
      trySendRequests()
    }
    else
    {
      throw IllegalStateException("Invalid resumeHandle was set")
    }
  }

  override fun finish(repeatable:RepeatHandle) {
    traceLog("action=finish")
    upstreamFinished = true
    trySendRequests()
  }

  override fun fail(throwable:Throwable) {
    traceLog("action=fail")
    stop = true
    failure.compareAndSet(null, throwable)
    upstreamFinished = true
    trySendRequests()
  }

  private fun trySendRequests() {
    var isLastRequest:Boolean = false
    var error:Throwable? = null
    var resumeWithoutSendingRequests = false

    synchronized (lock) {
      val numInFlightRequests = inFlightRequests.get()
      if (numInFlightRequests > 0) {
        traceLog("action=trySendRequests numInFlightRequests > 0")
        return
      }
      isLastRequest = upstreamFinished
      error = failure.get()
      if (isLastRequest || multiBucketBuilder.size() >= pageSize) {
        multiBucketBuilder.build(buckets)
        inFlightRequests.addAndGet(downstreams.size)
        if (traceEnabled) {
          logger.trace("targetPhase={}/{} bucketIdx={} action=trySendRequests isLastRequest={} ",
                       targetPhaseId, inputId, bucketIdx, isLastRequest)
        }
      }
      else {
        resumeWithoutSendingRequests = true
      }
    }
    if (resumeWithoutSendingRequests) {
      // do resume outside of the lock
      doResume()
      return
    }
    val allDownstreamsFinished = doSendRequests(isLastRequest, error)
    if (isLastRequest) {
      return
    }
    if (allDownstreamsFinished) {
      stop = true
    }
    doResume()
  }

  private fun doSendRequests(isLastRequest:Boolean, error:Throwable?):Boolean {
    var allDownstreamsFinished = true
    for (i in downstreams.indices)
    {
      val downstream = downstreams[i] ?: continue
      allDownstreamsFinished = allDownstreamsFinished and downstream.isFinished
      if (downstream.isFinished) {
        inFlightRequests.decrementAndGet()
      }
      else {
        if (error == null) {
          downstream.sendRequest(buckets[i], isLastRequest)
        }
        else {
          downstream.sendRequest(error, isKilled)
        }
      }
    }
    return allDownstreamsFinished
  }

  private fun doResume() {
    val resumeHandle = resumeHandleRef.get()
    if (resumeHandle !== ResumeHandle.INVALID)
    {
      resumeHandleRef.set(ResumeHandle.INVALID)
      resumeHandle.resume(true)
    }
  }

  override fun kill(throwable:Throwable) {
    stop = true
    // forward kill reason to downstream if not already done, otherwise downstream may wait forever for a final result
    if (failure.compareAndSet(null, throwable))
    {
      upstreamFinished = true
      isKilled = true
      trySendRequests()
    }
  }

  override fun requirements():Set<Requirement> {
    return Requirements.NO_REQUIREMENTS
  }

  private inner class Downstream internal constructor(downstreamNodeId:String): ActionListener<DistributedResultResponse> {
    private val downstreamNodeId:String
    internal var isFinished = false

    init{
      this.downstreamNodeId = downstreamNodeId
    }

    internal fun sendRequest(t:Throwable, isKilled:Boolean) {
      distributedResultAction.pushResult(
        downstreamNodeId,
        DistributedResultRequest(jobId, targetPhaseId, inputId, bucketIdx, t, isKilled),
        NO_OP_ACTION_LISTENER
      )
    }

    internal fun sendRequest(bucket:Bucket?, isLast:Boolean) {
      distributedResultAction.pushResult(
        downstreamNodeId,
        DistributedResultRequest(jobId, targetPhaseId, inputId, bucketIdx, streamers, bucket, isLast),
        this
      )
    }

    override fun onResponse(distributedResultResponse:DistributedResultResponse) {
      isFinished = !distributedResultResponse.needMore()
      inFlightRequests.decrementAndGet()
      trySendRequests()
    }

    override fun onFailure(e:Throwable) {
      stop = true
      isFinished = true
      inFlightRequests.decrementAndGet()
      trySendRequests() // still need to resume upstream if it was paused
    }
  }

  companion object {
    private val NO_OP_ACTION_LISTENER = object:ActionListener<DistributedResultResponse> {
      private val LOGGER = Loggers.getLogger(DistributingDownstream::class.java)
      override fun onResponse(distributedResultResponse:DistributedResultResponse) {}
      override fun onFailure(e:Throwable) {
        LOGGER.trace("Received failure from downstream", e)
      }
    }
  }
}

