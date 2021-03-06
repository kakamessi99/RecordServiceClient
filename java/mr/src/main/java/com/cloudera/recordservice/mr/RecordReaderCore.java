// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.recordservice.mr;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.token.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.recordservice.core.NetworkAddress;
import com.cloudera.recordservice.core.RecordServiceException;
import com.cloudera.recordservice.core.RecordServiceWorkerClient;
import com.cloudera.recordservice.core.Records;
import com.cloudera.recordservice.mr.security.DelegationTokenIdentifier;
import com.cloudera.recordservice.mr.security.TokenUtils;

/**
 * Core RecordReader functionality. Classes that implement the MR RecordReader
 * interface should contain this object.
 *
 * This class can authenticate to the worker using delegation tokens. We never
 * try to authenticate using kerberos (the planner should have created the delegation
 * token) to avoid causing issues with the KDC.
 */
public class RecordReaderCore implements Closeable {
  private final static Logger LOG = LoggerFactory.getLogger(RecordReaderCore.class);
  // Underlying worker connection.
  private RecordServiceWorkerClient worker_;

  // Iterator over the records returned by the server.
  private Records records_;

  // Schema for records_
  private Schema schema_;

  // Default fetch size to use for MR. This is currently much larger than the
  // server default but perfs better this way (but uses more memory).
  // TODO: investigate this more and do this in the server. Remove this.
  private static final int DEFAULT_FETCH_SIZE = 50000;

  /**
   * Creates a RecordReaderCore to read the records for taskInfo.
   */
  public RecordReaderCore(Configuration config, Credentials credentials,
      TaskInfo taskInfo) throws RecordServiceException, IOException {
    int fetchSize = config.getInt(RecordServiceConfig.FETCH_SIZE_CONF,
        DEFAULT_FETCH_SIZE);
    long memLimit = config.getLong(RecordServiceConfig.MEM_LIMIT_CONF, -1);
    long limit = config.getLong(RecordServiceConfig.RECORDS_LIMIT_CONF, -1);
    int maxAttempts = config.getInt(RecordServiceConfig.WORKER_RETRY_ATTEMPTS_CONF, -1);
    int taskSleepMs = config.getInt(RecordServiceConfig.WORKER_RETRY_SLEEP_MS_CONF, -1);
    int connectionTimeoutMs = config.getInt(
        RecordServiceConfig.WORKER_CONNECTION_TIMEOUT_MS_CONF, -1);
    int rpcTimeoutMs = config.getInt(
            RecordServiceConfig.WORKER_RPC_TIMEOUT_MS_CONF, -1);
    boolean enableLogging =
        config.getBoolean(RecordServiceConfig.WORKER_ENABLE_SERVER_LOGGING_CONF, false);

    // Try to get the delegation token from the credentials. If it is there, use it.
    @SuppressWarnings("unchecked")
    Token<DelegationTokenIdentifier> token = (Token<DelegationTokenIdentifier>)
        credentials.getToken(DelegationTokenIdentifier.DELEGATION_KIND);

    RecordServiceWorkerClient.Builder builder =
        new RecordServiceWorkerClient.Builder();
    if (fetchSize != -1) builder.setFetchSize(fetchSize);
    if (memLimit != -1) builder.setMemLimit(memLimit);
    if (limit != -1) builder.setLimit(limit);
    if (maxAttempts != -1) builder.setMaxAttempts(maxAttempts);
    if (taskSleepMs != -1 ) builder.setSleepDurationMs(taskSleepMs);
    if (connectionTimeoutMs != -1) builder.setConnectionTimeoutMs(connectionTimeoutMs);
    if (rpcTimeoutMs != -1) builder.setRpcTimeoutMs(rpcTimeoutMs);
    if (enableLogging) builder.setLoggingLevel(LOG);
    if (token != null) builder.setDelegationToken(TokenUtils.toDelegationToken(token));

    NetworkAddress address = null;
    // Important! We match locality on host names, not ips.
    String localHost = InetAddress.getLocalHost().getHostName();

    // 'localLocations' are the addresses where the data we want to read is
    // available locally, and 'globalLocations' are all worker addresses currently
    // available in the global membership.
    List<NetworkAddress> localLocations = taskInfo.getLocations();
    List<NetworkAddress> globalLocations = taskInfo.getAllWorkerAddresses();

    // 1. If the data is available on this node, schedule the task locally.
    for (NetworkAddress loc : localLocations) {
      if (localHost.equals(loc.hostname)) {
        LOG.info("Both data and RecordServiceWorker are available locally for task {}",
            taskInfo.getTask().taskId);
        address = loc;
        break;
      }
    }

    // 2. Check if there's a RecordServiceWorker running locally. If so, pick that node.
    if (address == null) {
      for (NetworkAddress loc : globalLocations) {
        if (localHost.equals(loc.hostname)) {
          address = loc;
          LOG.info("RecordServiceWorker is available locally for task {}.",
              taskInfo.getTask().taskId);
          break;
        }
      }
    }

    // 3. Finally, we don't have RecordServiceWorker running locally. Randomly pick
    // a node from the global membership.
    if (address == null) {
      Random rand = new Random();
      address = globalLocations.get(rand.nextInt(globalLocations.size()));
      LOG.info("Neither RecordServiceWorker nor data is available locally for task {}." +
          " Randomly selected host {} to execute it",
          taskInfo.getTask().taskId, address.hostname);
    }

    try {
      worker_ = builder.connect(address.hostname, address.port);
      records_ = worker_.execAndFetch(taskInfo.getTask());
    } finally {
      if (records_ == null) close();
    }
    schema_ = new Schema(records_.getSchema());
  }

  /**
   * Closes the task and worker connection.
   */
  @Override
  public void close() {
    if (records_ != null) records_.close();
    if (worker_ != null) worker_.close();
  }

  public Records records() { return records_; }
  public Schema schema() { return schema_; }
}

