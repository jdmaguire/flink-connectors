/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.connectors.flink;

import io.pravega.client.admin.ReaderGroupManager;
import io.pravega.client.stream.Checkpoint;
import io.pravega.client.stream.ReaderGroup;

import lombok.extern.slf4j.Slf4j;

import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.runtime.checkpoint.MasterTriggerRestoreHook;
import org.apache.flink.runtime.concurrent.Future;
import org.apache.flink.runtime.concurrent.impl.FlinkCompletableFuture;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * The hook executed in Flink's Checkpoint Coordinator that triggers and restores
 * checkpoints in on a Pravega ReaderGroup.
 */
@Slf4j
class ReaderCheckpointHook implements MasterTriggerRestoreHook<Checkpoint> {

    /** The prefix of checkpoint names */
    private static final String PRAVEGA_CHECKPOINT_NAME_PREFIX = "PVG-CHK-";

    // ------------------------------------------------------------------------

    /** The logical name of the operator. This is different from the (randomly generated)
     * reader group name, because it is used to identify the state in a checkpoint/savepoint
     * when resuming the checkpoint/savepoint with another job. */
    private final String readerName;

    /** The serializer for Pravega checkpoints, to store them in Flink checkpoints */
    private final CheckpointSerializer checkpointSerializer;

    /** The reader group used to trigger and restore pravega checkpoints */
    private final ReaderGroup readerGroup;

    /** The timeout on the future returned by the 'initiateCheckpoint()' call */
    private final long triggerTimeout;


    ReaderCheckpointHook(String readerName, String readerGroupName,
                         String scope, URI controllerURI,
                         long triggerTimeout) {

        this.readerName = checkNotNull(readerName);
        this.triggerTimeout = triggerTimeout;
        this.checkpointSerializer = new CheckpointSerializer();
        this.readerGroup = ReaderGroupManager.withScope(scope, controllerURI).getReaderGroup(readerGroupName);
    }

    // ------------------------------------------------------------------------

    @Override
    public String getIdentifier() {
        return this.readerName;
    }

    @Override
    public Future<Checkpoint> triggerCheckpoint(
            long checkpointId, long checkpointTimestamp, Executor executor) throws Exception {

        final String checkpointName = createCheckpointName(checkpointId);

        // The method only offers an 'Executor', but we need a 'ScheduledExecutorService'
        // Because the hook currently offers no "shutdown()" method, there is no good place to
        // shut down a long lived ScheduledExecutorService, so we create one per request
        // (we should change that by adding a shutdown() method to these hooks)
        // ths shutdown 

        final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

        final CompletableFuture<Checkpoint> checkpointResult =
                this.readerGroup.initiateCheckpoint(checkpointName, scheduledExecutorService);

        // Add a timeout to the future, to prevent long blocking calls
        scheduledExecutorService.schedule(() -> checkpointResult.cancel(false), triggerTimeout, TimeUnit.MILLISECONDS);

        // we make sure the executor is shut down after the future completes
        checkpointResult.handle((success, failure) -> scheduledExecutorService.shutdownNow());

        return flinkFutureFromJava8Future(checkpointResult, executor);
    }

    @Override
    public void restoreCheckpoint(long checkpointId, Checkpoint checkpoint) throws Exception {
        // checkpoint can be null when restoring from a savepoint that
        // did not include any state for that particular reader name
        if (checkpoint != null) {
            this.readerGroup.resetReadersToCheckpoint(checkpoint);
        }
    }

    @Override
    public SimpleVersionedSerializer<Checkpoint> createCheckpointDataSerializer() {
        return this.checkpointSerializer;
    }

    // ------------------------------------------------------------------------
    //  utils
    // ------------------------------------------------------------------------

    static long parseCheckpointId(String checkpointName) {
        checkArgument(checkpointName.startsWith(PRAVEGA_CHECKPOINT_NAME_PREFIX));

        try {
            return Long.parseLong(checkpointName.substring(PRAVEGA_CHECKPOINT_NAME_PREFIX.length()));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static String createCheckpointName(long checkpointId) {
        return PRAVEGA_CHECKPOINT_NAME_PREFIX + checkpointId;
    }

    // ------------------------------------------------------------------------

    /**
     * Utility function to convert a Java 8 Future ({@link CompletableFuture}) to a
     * Flink Future ({@link Future}). Flink brings its own futures currently, because it
     * still assumes only Java 7 and still needs the functionality of completable futures.
     */
    private static <T> Future<T> flinkFutureFromJava8Future(CompletableFuture<T> javaFuture, Executor executor) {

        final FlinkCompletableFuture<T> flinkFuture = new FlinkCompletableFuture<>();

        javaFuture.handleAsync((success, failure) -> {
            if (failure != null) {
                flinkFuture.completeExceptionally(failure);
            } else {
                flinkFuture.complete(success);
            }
            return null;
        }, executor);

        return flinkFuture;
    }
}
