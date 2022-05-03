/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.worker.block;

import alluxio.exception.AlluxioException;
import alluxio.exception.BlockAlreadyExistsException;
import alluxio.exception.BlockDoesNotExistException;
import alluxio.exception.InvalidWorkerStateException;
import alluxio.exception.WorkerOutOfSpaceException;
import alluxio.grpc.AsyncCacheRequest;
import alluxio.grpc.CacheRequest;
import alluxio.grpc.GetConfigurationPOptions;
import alluxio.proto.dataserver.Protocol;
import alluxio.wire.Configuration;
import alluxio.wire.FileInfo;
import alluxio.worker.SessionCleanable;
import alluxio.worker.Worker;
import alluxio.worker.block.io.BlockReader;
import alluxio.worker.block.io.BlockWriter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A block worker in the Alluxio system.
 */
public interface BlockWorker extends Worker, SessionCleanable {
  /**
   * @return the worker id
   */
  AtomicReference<Long> getWorkerId();

  /**
   * Aborts the temporary block created by the session.
   *
   * @param sessionId the id of the client
   * @param blockId the id of the block to be aborted
   * @throws BlockAlreadyExistsException if blockId already exists in committed blocks
   * @throws BlockDoesNotExistException if the temporary block cannot be found
   * @throws InvalidWorkerStateException if blockId does not belong to sessionId
   */
  void abortBlock(long sessionId, long blockId) throws BlockAlreadyExistsException,
      BlockDoesNotExistException, InvalidWorkerStateException, IOException;

  /**
   * Commits a block to Alluxio managed space. The block must be temporary. The block will not be
   * persisted or accessible before commitBlock succeeds.
   *
   * @param sessionId the id of the client
   * @param blockId the id of the block to commit
   * @param pinOnCreate whether to pin block on create
   * @throws BlockAlreadyExistsException if blockId already exists in committed blocks
   * @throws BlockDoesNotExistException if the temporary block cannot be found
   * @throws InvalidWorkerStateException if blockId does not belong to sessionId
   * @throws WorkerOutOfSpaceException if there is no more space left to hold the block
   */
  void commitBlock(long sessionId, long blockId, boolean pinOnCreate)
      throws BlockAlreadyExistsException, BlockDoesNotExistException, InvalidWorkerStateException,
      IOException, WorkerOutOfSpaceException;

  /**
   * Commits a block in UFS.
   *
   * @param blockId the id of the block to commit
   * @param length length of the block to commit
   */
  void commitBlockInUfs(long blockId, long length) throws IOException;

  /**
   * Creates a block in Alluxio managed space.
   * Calls {@link #createBlockWriter} to get a writer for writing to the block.
   * The block will be temporary until it is committed by {@link #commitBlock} .
   * Throws an {@link IllegalArgumentException} if the location does not belong to tiered storage.
   *
   * @param sessionId the id of the client
   * @param blockId the id of the block to create
   * @param tier the tier to place the new block in
   *        {@link BlockStoreLocation#ANY_TIER} for any tier
   * @param createBlockOptions the createBlockOptions
   * @return a string representing the path to the local file
   * @throws BlockAlreadyExistsException if blockId already exists, either temporary or committed,
   *         or block in eviction plan already exists
   * @throws WorkerOutOfSpaceException if this Store has no more space than the initialBlockSize
   */
  String createBlock(long sessionId, long blockId, int tier,
      CreateBlockOptions createBlockOptions)
      throws BlockAlreadyExistsException, WorkerOutOfSpaceException, IOException;

  /**
   * Creates a {@link BlockWriter} for an existing temporary block which is already created by
   * {@link #createBlock}.
   *
   * @param sessionId the id of the client
   * @param blockId the id of the block to be opened for writing
   * @return the block writer for the local block file
   * @throws BlockDoesNotExistException if the block cannot be found
   * @throws BlockAlreadyExistsException if a committed block with the same ID exists
   * @throws InvalidWorkerStateException if the worker state is invalid
   */
  BlockWriter createBlockWriter(long sessionId, long blockId)
      throws BlockDoesNotExistException, BlockAlreadyExistsException, InvalidWorkerStateException,
      IOException;

  /**
   * Gets a report for the periodic heartbeat to master. Contains the blocks added since the last
   * heart beat and blocks removed since the last heartbeat.
   *
   * @return a block heartbeat report
   */
  BlockHeartbeatReport getReport();

  /**
   * Gets the metadata for the entire block store. Contains the block mapping per storage dir and
   * the total capacity and used capacity of each tier. This function is cheap.
   *
   * @return the block store metadata
   */
  BlockStoreMeta getStoreMeta();

  /**
   * Similar as {@link BlockWorker#getStoreMeta} except that this also contains full blockId
   * list. This function is expensive.
   *
   * @return the full block store metadata
   */
  BlockStoreMeta getStoreMetaFull();

  /**
   * Checks if the storage has a given block.
   *
   * @param blockId the block id
   * @return true if the block is contained, false otherwise
   */
  boolean hasBlockMeta(long blockId);

  /**
   * Moves a block from its current location to a target location, currently only tier level moves
   * are supported. Throws an {@link IllegalArgumentException} if the tierAlias is out of range of
   * tiered storage.
   *
   * @param sessionId the id of the client
   * @param blockId the id of the block to move
   * @param tier the tier to move the block to
   * @throws BlockDoesNotExistException if blockId cannot be found
   * @throws InvalidWorkerStateException if blockId has not been committed
   * @throws WorkerOutOfSpaceException if newLocation does not have enough extra space to hold the
   *         block
   */
  void moveBlock(long sessionId, long blockId, int tier)
      throws BlockDoesNotExistException, InvalidWorkerStateException,
      WorkerOutOfSpaceException, IOException;

  /**
   * Moves a block from its current location to a target location, with a specific medium type.
   * Throws an {@link IllegalArgumentException} if the medium type is not one of the listed medium
   * types.
   *
   * @param sessionId the id of the client
   * @param blockId the id of the block to move
   * @param mediumType the medium type to move to
   * @throws BlockDoesNotExistException if blockId cannot be found
   * @throws InvalidWorkerStateException if blockId has not been committed
   * @throws WorkerOutOfSpaceException if newLocation does not have enough extra space to hold the
   *         block
   */
  void moveBlockToMedium(long sessionId, long blockId, String mediumType)
      throws BlockDoesNotExistException, InvalidWorkerStateException,
      WorkerOutOfSpaceException, IOException;

  /**
   * Creates the block reader to read from Alluxio block or UFS block.
   * Owner of this block reader must close it or lock will leak.
   *
   * @param sessionId the client session ID
   * @param blockId the ID of the UFS block to read
   * @param offset the offset within the block
   * @param positionShort whether the operation is using positioned read to a small buffer size
   * @param options the options
   * @return a block reader to read data from
   * @throws BlockDoesNotExistException if the requested block does not exist in this worker
   * @throws IOException if it fails to get block reader
   */
  BlockReader createBlockReader(long sessionId, long blockId, long offset,
      boolean positionShort, Protocol.OpenUfsBlockOptions options)
      throws BlockDoesNotExistException, IOException;

  /**
   * Creates a block reader to read a UFS block starting from given block offset.
   * Owner of this block reader must close it to cleanup state.
   *
   * @param sessionId the client session ID
   * @param blockId the ID of the UFS block to read
   * @param offset the offset within the block
   * @param positionShort whether the operation is using positioned read to a small buffer size
   * @param options the options
   * @return the block reader instance
   * @throws IOException if it fails to get block reader
   */
  BlockReader createUfsBlockReader(long sessionId, long blockId, long offset, boolean positionShort,
      Protocol.OpenUfsBlockOptions options) throws IOException;

  /**
   * Frees a block from Alluxio managed space.
   *
   * @param sessionId the id of the client
   * @param blockId the id of the block to be freed
   * @throws InvalidWorkerStateException if blockId has not been committed
   * @throws BlockDoesNotExistException if block cannot be found
   */
  void removeBlock(long sessionId, long blockId)
      throws InvalidWorkerStateException, BlockDoesNotExistException, IOException;

  /**
   * Request an amount of space for a block in its storage directory. The block must be a temporary
   * block.
   *
   * @param sessionId the id of the client
   * @param blockId the id of the block to allocate space to
   * @param additionalBytes the amount of bytes to allocate
   * @throws BlockDoesNotExistException if blockId can not be found, or some block in eviction plan
   *         cannot be found
   * @throws WorkerOutOfSpaceException if requested space can not be satisfied
   */
  void requestSpace(long sessionId, long blockId, long additionalBytes)
      throws BlockDoesNotExistException, WorkerOutOfSpaceException, IOException;

  /**
   * Submits the async cache request to async cache manager to execute.
   *
   * @param request the async cache request
   *
   * @deprecated This method will be deprecated as of v3.0, use {@link #cache}
   */
  @Deprecated
  void asyncCache(AsyncCacheRequest request);

  /**
   * Submits the cache request to cache manager to execute.
   *
   * @param request the cache request
   */
  void cache(CacheRequest request) throws AlluxioException, IOException;

  /**
   * Sets the pinlist for the underlying block store.
   *
   * @param pinnedInodes a set of pinned inodes
   */
  void updatePinList(Set<Long> pinnedInodes);

  /**
   * Gets the file information.
   *
   * @param fileId the file id
   * @return the file info
   */
  FileInfo getFileInfo(long fileId) throws IOException;

  /**
   * Clears the worker metrics.
   */
  void clearMetrics();

  /**
   * @param options method options
   * @return configuration information list
   */
  Configuration getConfiguration(GetConfigurationPOptions options);

  /**
   * @return the white list
   */
  List<String> getWhiteList();
}
