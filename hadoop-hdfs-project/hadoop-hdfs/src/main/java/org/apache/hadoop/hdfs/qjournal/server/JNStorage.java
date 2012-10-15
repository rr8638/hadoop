/**
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
package org.apache.hadoop.hdfs.qjournal.server;

import java.io.File;
import java.io.IOException;

import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.NodeType;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.StartupOption;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hdfs.server.common.StorageErrorReporter;
import org.apache.hadoop.hdfs.server.namenode.FileJournalManager;
import org.apache.hadoop.hdfs.server.namenode.NNStorage;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;

/**
 * A {@link Storage} implementation for the {@link JournalNode}.
 * 
 * The JN has a storage directory for each namespace for which it stores
 * metadata. There is only a single directory per JN in the current design.
 */
class JNStorage extends Storage {

  private final FileJournalManager fjm;
  private final StorageDirectory sd;
  private boolean lazyInitted = false;

  /**
   * @param logDir the path to the directory in which data will be stored
   * @param errorReporter a callback to report errors
   */
  protected JNStorage(File logDir, StorageErrorReporter errorReporter) {
    super(NodeType.JOURNAL_NODE);
    
    sd = new StorageDirectory(logDir);
    this.addStorageDir(sd);
    this.fjm = new FileJournalManager(sd, errorReporter);
  }
  
  FileJournalManager getJournalManager() {
    return fjm;
  }

  @Override
  public boolean isPreUpgradableLayout(StorageDirectory sd) throws IOException {
    return false;
  }

  /**
   * Find an edits file spanning the given transaction ID range.
   * If no such file exists, an exception is thrown.
   */
  File findFinalizedEditsFile(long startTxId, long endTxId) throws IOException {
    File ret = new File(sd.getCurrentDir(),
        NNStorage.getFinalizedEditsFileName(startTxId, endTxId));
    if (!ret.exists()) {
      throw new IOException(
          "No edits file for range " + startTxId + "-" + endTxId);
    }
    return ret;
  }

  /**
   * @return the path for an in-progress edits file starting at the given
   * transaction ID. This does not verify existence of the file. 
   */
  File getInProgressEditLog(long startTxId) {
    return new File(sd.getCurrentDir(),
        NNStorage.getInProgressEditsFileName(startTxId));
  }

  /**
   * @return the path for the file which contains persisted data for the
   * paxos-like recovery process for the given log segment.
   */
  File getPaxosFile(long segmentTxId) {
    return new File(getPaxosDir(), String.valueOf(segmentTxId));
  }
  
  private File getPaxosDir() {
    return new File(sd.getCurrentDir(), "paxos");
  }

  void format(NamespaceInfo nsInfo) throws IOException {
    setStorageInfo(nsInfo);
    LOG.info("Formatting journal storage directory " + 
        sd + " with nsid: " + getNamespaceID());
    sd.clearDirectory();
    writeProperties(sd);
    if (!getPaxosDir().mkdirs()) {
      throw new IOException("Could not create paxos dir: " + getPaxosDir());
    }
  }
  
  void analyzeStorage(NamespaceInfo nsInfo) throws IOException {
    if (lazyInitted) {
      checkConsistentNamespace(nsInfo);
      return;
    }
    
    StorageState state = sd.analyzeStorage(StartupOption.REGULAR, this);
    switch (state) {
    case NON_EXISTENT:
    case NOT_FORMATTED:
      format(nsInfo);
      // In the NORMAL case below, analyzeStorage() has already locked the
      // directory for us. But in the case that we format it, we have to
      // lock it here.
      // The directory is unlocked in close() when the node shuts down.
      sd.lock();
      break;
    case NORMAL:
      // Storage directory is already locked by analyzeStorage() - no
      // need to lock it here.
      readProperties(sd);
      checkConsistentNamespace(nsInfo);
      break;
      
    default:
      LOG.warn("TODO: unhandled state for storage dir " + sd + ": " + state);
    }
    lazyInitted  = true;
  }

  private void checkConsistentNamespace(NamespaceInfo nsInfo)
      throws IOException {
    if (nsInfo.getNamespaceID() != getNamespaceID()) {
      throw new IOException("Incompatible namespaceID for journal " +
          this.sd + ": NameNode has nsId " + nsInfo.getNamespaceID() +
          " but storage has nsId " + getNamespaceID());
    }
    
    if (!nsInfo.getClusterID().equals(getClusterID())) {
      throw new IOException("Incompatible clusterID for journal " +
          this.sd + ": NameNode has clusterId '" + nsInfo.getClusterID() +
          "' but storage has clusterId '" + getClusterID() + "'");
      
    }
  }

  public void close() throws IOException {
    LOG.info("Closing journal storage for " + sd);
    unlockAll();
  }
}
