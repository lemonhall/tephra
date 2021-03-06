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

package co.cask.tephra.snapshot;

import co.cask.tephra.ChangeId;
import co.cask.tephra.TransactionManager;
import co.cask.tephra.persist.TransactionSnapshot;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Handles serialization/deserialization of a {@link co.cask.tephra.persist.TransactionSnapshot}
 * and its elements to {@code byte[]}.
 */
public class DefaultSnapshotCodec implements SnapshotCodec {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultSnapshotCodec.class);

  @Override
  public int getVersion() {
    return 1;
  }

  @Override
  public void encode(OutputStream out, TransactionSnapshot snapshot) {
    try {
      BinaryEncoder encoder = new BinaryEncoder(out);

      encoder.writeLong(snapshot.getTimestamp());
      encoder.writeLong(snapshot.getReadPointer());
      encoder.writeLong(snapshot.getWritePointer());
      encodeInvalid(encoder, snapshot.getInvalid());
      encodeInProgress(encoder, snapshot.getInProgress());
      encodeChangeSets(encoder, snapshot.getCommittingChangeSets());
      encodeChangeSets(encoder, snapshot.getCommittedChangeSets());

    } catch (IOException e) {
      LOG.error("Unable to serialize transaction state: ", e);
      throw Throwables.propagate(e);
    }
  }

  @Override
  public TransactionSnapshot decode(InputStream in) {
    BinaryDecoder decoder = new BinaryDecoder(in);

    try {
      long timestamp = decoder.readLong();
      long readPointer = decoder.readLong();
      long writePointer = decoder.readLong();
      Collection<Long> invalid = decodeInvalid(decoder);
      NavigableMap<Long, TransactionManager.InProgressTx> inProgress = decodeInProgress(decoder);
      NavigableMap<Long, Set<ChangeId>> committing = decodeChangeSets(decoder);
      NavigableMap<Long, Set<ChangeId>> committed = decodeChangeSets(decoder);

      return new TransactionSnapshot(timestamp, readPointer, writePointer, invalid, inProgress,
                                     committing, committed);
    } catch (IOException e) {
      LOG.error("Unable to deserialize transaction state: ", e);
      throw Throwables.propagate(e);
    }
  }

  private void encodeInvalid(BinaryEncoder encoder, Collection<Long> invalid) throws IOException {
    if (!invalid.isEmpty()) {
      encoder.writeInt(invalid.size());
      for (long invalidTx : invalid) {
        encoder.writeLong(invalidTx);
      }
    }
    encoder.writeInt(0); // zero denotes end of list as per AVRO spec
  }

  private Collection<Long> decodeInvalid(BinaryDecoder decoder) throws IOException {
    int size = decoder.readInt();
    Collection<Long> invalid = Lists.newArrayListWithCapacity(size);
    while (size != 0) { // zero denotes end of list as per AVRO spec
      for (int remaining = size; remaining > 0; --remaining) {
        invalid.add(decoder.readLong());
      }
      size = decoder.readInt();
    }
    return invalid;
  }

  protected void encodeInProgress(BinaryEncoder encoder, Map<Long, TransactionManager.InProgressTx> inProgress)
    throws IOException {

    if (!inProgress.isEmpty()) {
      encoder.writeInt(inProgress.size());
      for (Map.Entry<Long, TransactionManager.InProgressTx> entry : inProgress.entrySet()) {
        encoder.writeLong(entry.getKey()); // tx id
        encoder.writeLong(entry.getValue().getExpiration());
        encoder.writeLong(entry.getValue().getVisibilityUpperBound());
      }
    }
    encoder.writeInt(0); // zero denotes end of list as per AVRO spec
  }

  protected NavigableMap<Long, TransactionManager.InProgressTx> decodeInProgress(BinaryDecoder decoder)
    throws IOException {

    int size = decoder.readInt();
    NavigableMap<Long, TransactionManager.InProgressTx> inProgress = Maps.newTreeMap();
    while (size != 0) { // zero denotes end of list as per AVRO spec
      for (int remaining = size; remaining > 0; --remaining) {
        long txId = decoder.readLong();
        long expiration = decoder.readLong();
        long visibilityUpperBound = decoder.readLong();
        inProgress.put(txId,
                       new TransactionManager.InProgressTx(visibilityUpperBound, expiration));
      }
      size = decoder.readInt();
    }
    return inProgress;
  }

  private void encodeChangeSets(BinaryEncoder encoder, Map<Long, Set<ChangeId>> changes) throws IOException {
    if (!changes.isEmpty()) {
      encoder.writeInt(changes.size());
      for (Map.Entry<Long, Set<ChangeId>> entry : changes.entrySet()) {
        encoder.writeLong(entry.getKey());
        encodeChanges(encoder, entry.getValue());
      }
    }
    encoder.writeInt(0); // zero denotes end of list as per AVRO spec
  }

  private NavigableMap<Long, Set<ChangeId>> decodeChangeSets(BinaryDecoder decoder) throws IOException {
    int size = decoder.readInt();
    NavigableMap<Long, Set<ChangeId>> changeSets = new TreeMap<Long, Set<ChangeId>>();
    while (size != 0) { // zero denotes end of list as per AVRO spec
      for (int remaining = size; remaining > 0; --remaining) {
        changeSets.put(decoder.readLong(), decodeChanges(decoder));
      }
      size = decoder.readInt();
    }
    return changeSets;
  }

  private void encodeChanges(BinaryEncoder encoder, Set<ChangeId> changes) throws IOException {
    if (!changes.isEmpty()) {
      encoder.writeInt(changes.size());
      for (ChangeId change : changes) {
        encoder.writeBytes(change.getKey());
      }
    }
    encoder.writeInt(0); // zero denotes end of list as per AVRO spec
  }

  private Set<ChangeId> decodeChanges(BinaryDecoder decoder) throws IOException {
    int size = decoder.readInt();
    HashSet<ChangeId> changes = Sets.newHashSetWithExpectedSize(size);
    while (size != 0) { // zero denotes end of list as per AVRO spec
      for (int remaining = size; remaining > 0; --remaining) {
        changes.add(new ChangeId(decoder.readBytes()));
      }
      size = decoder.readInt();
    }
    // todo is there an immutable hash set?
    return changes;
  }
}
