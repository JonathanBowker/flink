/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.client.program;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobSubmissionResult;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.client.JobExecutionException;
import org.apache.flink.runtime.client.JobStatusMessage;
import org.apache.flink.runtime.clusterframework.messages.GetClusterStatusResponse;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.concurrent.ScheduledExecutor;
import org.apache.flink.runtime.concurrent.ScheduledExecutorServiceAdapter;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobStatus;
import org.apache.flink.runtime.leaderretrieval.LeaderRetrievalException;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.runtime.rpc.akka.exceptions.AkkaRpcException;
import org.apache.flink.runtime.rpc.exceptions.FencingTokenException;
import org.apache.flink.runtime.util.ExecutorThreadFactory;
import org.apache.flink.runtime.util.LeaderConnectionInfo;
import org.apache.flink.runtime.util.LeaderRetrievalUtils;
import org.apache.flink.util.FlinkException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * Client to interact with a {@link MiniCluster}.
 */
public class MiniClusterClient extends ClusterClient<MiniClusterClient.MiniClusterId> {

	private final MiniCluster miniCluster;
	private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(4, new ExecutorThreadFactory("Flink-MiniClusterClient"));
	private final ScheduledExecutor scheduledExecutor = new ScheduledExecutorServiceAdapter(scheduledExecutorService);

	public MiniClusterClient(@Nonnull Configuration configuration, @Nonnull MiniCluster miniCluster) throws Exception {
		super(configuration, miniCluster.getHighAvailabilityServices(), true);

		this.miniCluster = miniCluster;
	}

	@Override
	public void shutdown() throws Exception {
		super.shutdown();
		scheduledExecutorService.shutdown();
	}

	@Override
	public JobSubmissionResult submitJob(JobGraph jobGraph, ClassLoader classLoader) throws ProgramInvocationException {
		if (isDetached()) {
			try {
				miniCluster.runDetached(jobGraph);
			} catch (JobExecutionException | InterruptedException e) {
				throw new ProgramInvocationException(
					String.format("Could not run job %s in detached mode.", jobGraph.getJobID()),
					e);
			}

			return new JobSubmissionResult(jobGraph.getJobID());
		} else {
			try {
				return miniCluster.executeJobBlocking(jobGraph);
			} catch (JobExecutionException | InterruptedException e) {
				throw new ProgramInvocationException(
					String.format("Could not run job %s.", jobGraph.getJobID()),
					e);
			}
		}
	}

	@Override
	public void cancel(JobID jobId) throws Exception {
		guardWithSingleRetry(() -> miniCluster.cancelJob(jobId), scheduledExecutor);
	}

	@Override
	public String cancelWithSavepoint(JobID jobId, @Nullable String savepointDirectory) throws Exception {
		return guardWithSingleRetry(() -> miniCluster.triggerSavepoint(jobId, savepointDirectory, true), scheduledExecutor).get();
	}

	@Override
	public void stop(JobID jobId) throws Exception {
		throw new UnsupportedOperationException("MiniClusterClient does not yet support this operation.");
	}

	@Override
	public CompletableFuture<String> triggerSavepoint(JobID jobId, @Nullable String savepointDirectory) throws FlinkException {
		throw new UnsupportedOperationException("MiniClusterClient does not yet support this operation.");
	}

	@Override
	public CompletableFuture<Acknowledge> disposeSavepoint(String savepointPath, Time timeout) throws FlinkException {
		throw new UnsupportedOperationException("MiniClusterClient does not yet support this operation.");
	}

	@Override
	public CompletableFuture<Collection<JobStatusMessage>> listJobs() throws Exception {
		throw new UnsupportedOperationException("MiniClusterClient does not yet support this operation.");
	}

	@Override
	public Map<String, Object> getAccumulators(JobID jobID) throws Exception {
		throw new UnsupportedOperationException("MiniClusterClient does not yet support this operation.");
	}

	@Override
	public Map<String, Object> getAccumulators(JobID jobID, ClassLoader loader) throws Exception {
		throw new UnsupportedOperationException("MiniClusterClient does not yet support this operation.");
	}

	@Override
	public CompletableFuture<JobStatus> getJobStatus(JobID jobId) {
		return guardWithSingleRetry(() -> miniCluster.getJobStatus(jobId), scheduledExecutor);
	}

	@Override
	public MiniClusterClient.MiniClusterId getClusterId() {
		return MiniClusterId.INSTANCE;
	}

	@Override
	public LeaderConnectionInfo getClusterConnectionInfo() throws LeaderRetrievalException {
		return LeaderRetrievalUtils.retrieveLeaderConnectionInfo(
			highAvailabilityServices.getDispatcherLeaderRetriever(),
			timeout);
	}

	// ======================================
	// Legacy methods
	// ======================================

	@Override
	public void waitForClusterToBeReady() {
		// no op
	}

	@Override
	public String getWebInterfaceURL() {
		return miniCluster.getRestAddress().toString();
	}

	@Override
	public GetClusterStatusResponse getClusterStatus() {
		return null;
	}

	@Override
	public List<String> getNewMessages() {
		return Collections.emptyList();
	}

	@Override
	public int getMaxSlots() {
		return 0;
	}

	@Override
	public boolean hasUserJarsInClassPath(List<URL> userJarFiles) {
		return false;
	}

	enum MiniClusterId {
		INSTANCE
	}

	private static <X> CompletableFuture<X> guardWithSingleRetry(Supplier<CompletableFuture<X>> operation, ScheduledExecutor executor) {
		return FutureUtils.retryWithDelay(
			operation,
			1,
			Time.milliseconds(500),
			throwable -> throwable instanceof FencingTokenException || throwable instanceof AkkaRpcException,
			executor);
	}
}
