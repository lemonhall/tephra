/*
 * Copyright © 2012-2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.tephra.hbase94.coprocessor;

import co.cask.tephra.ChangeId;
import co.cask.tephra.TransactionManager;
import co.cask.tephra.TxConstants;
import co.cask.tephra.coprocessor.TransactionStateCache;
import co.cask.tephra.coprocessor.TransactionStateCacheSupplier;
import co.cask.tephra.persist.HDFSTransactionStateStorage;
import co.cask.tephra.persist.TransactionSnapshot;
import co.cask.tephra.snapshot.DefaultSnapshotCodec;
import co.cask.tephra.snapshot.SnapshotCodecProvider;
import co.cask.tephra.util.TxUtils;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.MockRegionServerServices;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests filtering of invalid transaction data by the {@link TransactionProcessor} coprocessor.
 */
public class TransactionProcessorTest {
  private static final Logger LOG = LoggerFactory.getLogger(TransactionProcessorTest.class);

  // 8 versions, 1 hour apart, latest is current ts.
  private static final long[] V;

  static {
    long now = System.currentTimeMillis();
    V = new long[9];
    for (int i = 0; i < V.length; i++) {
      V[i] = (now - TimeUnit.HOURS.toMillis(9 - i)) * TxConstants.MAX_TX_PER_MS;
    }
  }

  @ClassRule
  public static TemporaryFolder tmpFolder = new TemporaryFolder();
  private static MiniDFSCluster dfsCluster;
  private static Configuration conf;
  private static LongArrayList invalidSet = new LongArrayList(new long[]{V[3], V[5], V[7]});
  private static TransactionSnapshot txSnapshot;

  @BeforeClass
  public static void setupBeforeClass() throws Exception {
    Configuration hConf = new Configuration();
    hConf.set(MiniDFSCluster.HDFS_MINIDFS_BASEDIR, tmpFolder.newFolder().getAbsolutePath());

    dfsCluster = new MiniDFSCluster.Builder(hConf).numDataNodes(1).build();
    dfsCluster.waitActive();
    conf = HBaseConfiguration.create(dfsCluster.getFileSystem().getConf());

    conf.unset(TxConstants.Manager.CFG_TX_HDFS_USER);
    conf.unset(TxConstants.Persist.CFG_TX_SNAPHOT_CODEC_CLASSES);
    String localTestDir = "/tmp/transactionDataJanitorTest";
    conf.set(TxConstants.Manager.CFG_TX_SNAPSHOT_DIR, localTestDir);
    conf.set(TxConstants.Persist.CFG_TX_SNAPHOT_CODEC_CLASSES, DefaultSnapshotCodec.class.getName());

    // write an initial transaction snapshot
    txSnapshot =
      TransactionSnapshot.copyFrom(
        System.currentTimeMillis(), V[6] - 1, V[7], invalidSet,
        // this will set visibility upper bound to V[6]
        Maps.newTreeMap(ImmutableSortedMap.of(V[6], new TransactionManager.InProgressTx(V[6] - 1, Long.MAX_VALUE))),
        new HashMap<Long, Set<ChangeId>>(), new TreeMap<Long, Set<ChangeId>>());
    HDFSTransactionStateStorage tmpStorage =
      new HDFSTransactionStateStorage(conf, new SnapshotCodecProvider(conf));
    tmpStorage.startAndWait();
    tmpStorage.writeSnapshot(txSnapshot);
    tmpStorage.stopAndWait();
  }

  @AfterClass
  public static void shutdownAfterClass() throws Exception {
    dfsCluster.shutdown();
  }

  @Test
  public void testDataJanitorRegionScanner() throws Exception {
    String tableName = "TestDataJanitorRegionScanner";
    byte[] familyBytes = Bytes.toBytes("f");
    byte[] columnBytes = Bytes.toBytes("c");
    HTableDescriptor htd = new HTableDescriptor(tableName);
    HColumnDescriptor cfd = new HColumnDescriptor(familyBytes);
    // with that, all older than upper visibility bound by 3 hours should be expired by TTL logic
    cfd.setValue(TxConstants.PROPERTY_TTL, String.valueOf(TimeUnit.HOURS.toMillis(3)));
    cfd.setMaxVersions(10);
    htd.addFamily(cfd);
    htd.addCoprocessor(TransactionProcessor.class.getName());
    Path tablePath = new Path("/tmp/" + tableName);
    Path hlogPath = new Path("/tmp/hlog");
    Path oldPath = new Path("/tmp/.oldLogs");
    Configuration hConf = conf;
    FileSystem fs = FileSystem.get(hConf);
    assertTrue(fs.mkdirs(tablePath));
    HLog hlog = new HLog(fs, hlogPath, oldPath, hConf);
    HRegion region = new HRegion(tablePath, hlog, fs, hConf, new HRegionInfo(Bytes.toBytes(tableName)), htd,
                                 new MockRegionServerServices());
    try {
      region.initialize();
      TransactionStateCache cache = new TransactionStateCacheSupplier(hConf).get();
      LOG.info("Coprocessor is using transaction state: " + cache.getLatestState());

      for (int i = 1; i <= 8; i++) {
        for (int k = 1; k <= i; k++) {
          Put p = new Put(Bytes.toBytes(i));
          p.add(familyBytes, columnBytes, V[k], Bytes.toBytes(V[k]));
          region.put(p);
        }
      }

      List<KeyValue> results = Lists.newArrayList();

      // force a flush to clear the data
      // during flush, the coprocessor should drop all KeyValues with timestamps in the invalid set
      LOG.info("Flushing region " + region.getRegionNameAsString());
      region.flushcache();

      // now a normal scan should only return the valid rows - testing that cleanup works on flush
      Scan scan = new Scan();
      scan.setMaxVersions(10);
      RegionScanner regionScanner = region.getScanner(scan);

      // first returned value should be "4" with version "4"
      results.clear();
      assertTrue(regionScanner.next(results));
      assertKeyValueMatches(results, 4, new long[]{V[4]});

      results.clear();
      assertTrue(regionScanner.next(results));
      assertKeyValueMatches(results, 5, new long[]{V[4]});

      results.clear();
      assertTrue(regionScanner.next(results));
      assertKeyValueMatches(results, 6, new long[]{V[6], V[4]});

      results.clear();
      assertTrue(regionScanner.next(results));
      assertKeyValueMatches(results, 7, new long[]{V[6], V[4]});

      results.clear();
      assertFalse(regionScanner.next(results));
      assertKeyValueMatches(results, 8, new long[]{V[8], V[6], V[4]});
    } finally {
      region.close();
    }
  }

  @Test
  public void testDeleteFiltering() throws Exception {
    String tableName = "TestDeleteFiltering";
    byte[] familyBytes = Bytes.toBytes("f");
    byte[] columnBytes = Bytes.toBytes("c");
    HTableDescriptor htd = new HTableDescriptor(tableName);
    HColumnDescriptor cfd = new HColumnDescriptor(familyBytes);
    cfd.setMaxVersions(10);
    htd.addFamily(cfd);
    htd.addCoprocessor(TransactionProcessor.class.getName());
    Path tablePath = new Path("/tmp/" + tableName);
    Path hlogPath = new Path("/tmp/hlog-" + tableName);
    Path oldPath = new Path("/tmp/.oldLogs-" + tableName);
    Configuration hConf = conf;
    FileSystem fs = FileSystem.get(hConf);
    assertTrue(fs.mkdirs(tablePath));
    HLog hlog = new HLog(fs, hlogPath, oldPath, hConf);
    HRegion region = new HRegion(tablePath, hlog, fs, hConf, new HRegionInfo(Bytes.toBytes(tableName)), htd,
                                 new MockRegionServerServices());
    try {
      region.initialize();
      TransactionStateCache cache = new TransactionStateCacheSupplier(hConf).get();
      LOG.info("Coprocessor is using transaction state: " + cache.getLatestState());

      byte[] row = Bytes.toBytes(1);
      for (int i = 4; i < V.length; i++) {
        if (i != 5) {
          Put p = new Put(row);
          p.add(familyBytes, columnBytes, V[i], Bytes.toBytes(V[i]));
          region.put(p);
        }
      }

      // delete from the third entry back
      Delete d = new Delete(row, V[5]);
      region.delete(d, false);

      List<KeyValue> results = Lists.newArrayList();

      // force a flush to clear the data
      // during flush, we should drop the deleted version, but not the others
      LOG.info("Flushing region " + region.getRegionNameAsString());
      region.flushcache();

      // now a normal scan should return row with versions at: V[8], V[6].
      // V[7] is invalid and V[5] and prior are deleted.
      Scan scan = new Scan();
      scan.setMaxVersions(10);
      RegionScanner regionScanner = region.getScanner(scan);
      // should be only one row
      assertFalse(regionScanner.next(results));
      assertKeyValueMatches(results, 1, new long[]{ V[8], V[6] });
    } finally {
      region.close();
    }
  }

  @Test
  public void testDeleteMarkerCleanup() throws Exception {
    String tableName = "TestDeleteMarkerCleanup";
    byte[] familyBytes = Bytes.toBytes("f");
    HRegion region = createRegion(tableName, familyBytes, 0);
    try {
      region.initialize();

      // all puts use a timestamp before the tx snapshot's visibility upper bound, making them eligible for removal
      long writeTs = txSnapshot.getVisibilityUpperBound() - 10;
      // deletes are performed after the writes, but still before the visibility upper bound
      long deleteTs = writeTs + 1;
      // write separate columns to confirm that delete markers survive across flushes
      byte[] row = Bytes.toBytes(100);
      Put p = new Put(row);
      // all puts use V[4] timestamp, since it is visible according to transaction state
      LOG.info("Writing columns at timestamp " + V[4]);
      for (int i = 0; i < 5; i++) {
        byte[] iBytes = Bytes.toBytes(i);
        p.add(familyBytes, iBytes, writeTs, iBytes);
      }
      region.put(p);
      // read all back
      Scan scan = new Scan(row);
      RegionScanner regionScanner = region.getScanner(scan);
      List<KeyValue> results = Lists.newArrayList();
      assertFalse(regionScanner.next(results));
      for (int i = 0; i < 5; i++) {
        KeyValue cell = results.get(i);
        assertArrayEquals(row, cell.getRow());
        byte[] idxBytes = Bytes.toBytes(i);
        assertArrayEquals(idxBytes, cell.getQualifier());
        assertArrayEquals(idxBytes, cell.getValue());
      }

      // force a flush to clear the memstore
      LOG.info("Before delete, flushing region " + region.getRegionNameAsString());
      region.flushcache();

      // delete the odd entries
      for (int i = 0; i < 5; i++) {
        if (i % 2 == 1) {
          // deletes are performed as puts with empty values
          Put deletePut = new Put(row);
          deletePut.add(familyBytes, Bytes.toBytes(i), deleteTs, new byte[0]);
          region.put(deletePut);
        }
      }

      // read all back
      scan = new Scan(row);
      scan.setFilter(new TransactionVisibilityFilter(
          TxUtils.createDummyTransaction(txSnapshot), new TreeMap<byte[], Long>(), false, ScanType.USER_SCAN));
      regionScanner = region.getScanner(scan);
      results = Lists.newArrayList();
      assertFalse(regionScanner.next(results));
      assertEquals(3, results.size());
      // only even columns should exist
      for (int i = 0; i < 3; i++) {
        KeyValue cell = results.get(i);
        LOG.info("Got cell " + cell);
        assertArrayEquals(row, cell.getRow());
        byte[] idxBytes = Bytes.toBytes(i * 2);
        assertArrayEquals(idxBytes, cell.getQualifier());
        assertArrayEquals(idxBytes, cell.getValue());
      }

      // force another flush on the delete markers
      // during flush, we should retain the delete markers, since they can only safely be dropped by a major compaction
      LOG.info("After delete, flushing region " + region.getRegionNameAsString());
      region.flushcache();

      scan = new Scan(row);
      scan.setFilter(new TransactionVisibilityFilter(
          TxUtils.createDummyTransaction(txSnapshot), new TreeMap<byte[], Long>(), false, ScanType.USER_SCAN));
      regionScanner = region.getScanner(scan);
      results = Lists.newArrayList();
      assertFalse(regionScanner.next(results));
      assertEquals(3, results.size());
      // only even columns should exist
      for (int i = 0; i < 3; i++) {
        KeyValue cell = results.get(i);
        assertArrayEquals(row, cell.getRow());
        byte[] idxBytes = Bytes.toBytes(i * 2);
        assertArrayEquals(idxBytes, cell.getQualifier());
        assertArrayEquals(idxBytes, cell.getValue());
      }

      // force a major compaction
      LOG.info("Forcing major compaction of region " + region.getRegionNameAsString());
      region.compactStores(true);

      // perform a raw scan (no filter) to confirm that the delete markers are now gone
      scan = new Scan(row);
      regionScanner = region.getScanner(scan);
      results = Lists.newArrayList();
      assertFalse(regionScanner.next(results));
      assertEquals(3, results.size());
      // only even columns should exist
      for (int i = 0; i < 3; i++) {
        KeyValue cell = results.get(i);
        assertArrayEquals(row, cell.getRow());
        byte[] idxBytes = Bytes.toBytes(i * 2);
        assertArrayEquals(idxBytes, cell.getQualifier());
        assertArrayEquals(idxBytes, cell.getValue());
      }
    } finally {
      region.close();
    }
  }

  private HRegion createRegion(String tableName, byte[] family, long ttl) throws IOException {
    HTableDescriptor htd = new HTableDescriptor(tableName);
    HColumnDescriptor cfd = new HColumnDescriptor(family);
    if (ttl > 0) {
      cfd.setValue(TxConstants.PROPERTY_TTL, String.valueOf(ttl));
    }
    cfd.setMaxVersions(10);
    htd.addFamily(cfd);
    htd.addCoprocessor(TransactionProcessor.class.getName());
    Path tablePath = new Path("/tmp/" + tableName);
    Path hlogPath = new Path("/tmp/hlog-" + tableName);
    Path oldPath = new Path("/tmp/.oldLogs-" + tableName);
    Configuration hConf = conf;
    FileSystem fs = FileSystem.get(hConf);
    assertTrue(fs.mkdirs(tablePath));
    HLog hlog = new HLog(fs, hlogPath, oldPath, hConf);
    return new HRegion(tablePath, hlog, fs, hConf, new HRegionInfo(Bytes.toBytes(tableName)), htd,
        new MockRegionServerServices());
  }

  private void assertKeyValueMatches(List<KeyValue> results, int index, long[] versions) {
    assertEquals(versions.length, results.size());
    for (int i = 0; i < versions.length; i++) {
      KeyValue kv = results.get(i);
      assertArrayEquals(Bytes.toBytes(index), kv.getRow());
      assertEquals(versions[i], kv.getTimestamp());
      assertArrayEquals(Bytes.toBytes(versions[i]), kv.getValue());
    }
  }

  @Test
  public void testTransactionStateCache() throws Exception {
    TransactionStateCache cache = new TransactionStateCache();
    cache.setConf(conf);
    cache.startAndWait();
    // verify that the transaction snapshot read matches what we wrote in setupBeforeClass()
    TransactionSnapshot cachedSnapshot = cache.getLatestState();
    assertNotNull(cachedSnapshot);
    assertEquals(invalidSet, cachedSnapshot.getInvalid());
    cache.stopAndWait();
  }
}
