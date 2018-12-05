/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.job;

import static io.zeebe.logstreams.rocksdb.ZeebeStateConstants.STATE_BYTE_ORDER;
import static io.zeebe.util.buffer.BufferUtil.contentsEqual;

import io.zeebe.db.ColumnFamily;
import io.zeebe.db.ZeebeDb;
import io.zeebe.db.impl.ZbByte;
import io.zeebe.db.impl.ZbCompositeKey;
import io.zeebe.db.impl.ZbLong;
import io.zeebe.db.impl.ZbNil;
import io.zeebe.db.impl.ZbString;
import io.zeebe.db.impl.rocksdb.ZbColumnFamilies;
import io.zeebe.logstreams.rocksdb.ZbRocksDb.IteratorControl;
import io.zeebe.logstreams.rocksdb.ZbWriteBatch;
import io.zeebe.protocol.impl.record.value.job.JobRecord;
import io.zeebe.util.EnsureUtil;
import io.zeebe.util.buffer.BufferWriter;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;

public class JobState {

  // key => job record value
  private final UnpackedObjectValue jobRecord;
  private final ZbLong jobKey;
  private final ColumnFamily<ZbLong, UnpackedObjectValue> jobsColumnFamily;

  // key => job state
  private final ZbByte jobState;
  private final ColumnFamily<ZbLong, ZbByte> statesJobColumnFamily;

  // type => [key]
  private final ZbString jobTypeKey;
  private final ZbCompositeKey<ZbString, ZbLong> typeJobKey;
  private final ColumnFamily<ZbCompositeKey<ZbString, ZbLong>, ZbNil> activatableColumnFamily;

  // timeout => key
  private final ZbLong deadlineKey;
  private final ZbCompositeKey<ZbLong, ZbLong> deadlineJobKey;
  private final ColumnFamily<ZbCompositeKey<ZbLong, ZbLong>, ZbNil> deadlinesColumnFamily;
  private final ZeebeDb<ZbColumnFamilies> zeebeDb;

  public JobState(ZeebeDb<ZbColumnFamilies> zeebeDb) {
    jobRecord = new UnpackedObjectValue();
    jobKey = new ZbLong();
    jobsColumnFamily = zeebeDb.createColumnFamily(ZbColumnFamilies.JOBS, jobKey, jobRecord);

    jobState = new ZbByte();
    statesJobColumnFamily =
        zeebeDb.createColumnFamily(ZbColumnFamilies.JOB_STATES, jobKey, jobState);

    jobTypeKey = new ZbString();
    typeJobKey = new ZbCompositeKey<>();
    activatableColumnFamily =
        zeebeDb.createColumnFamily(ZbColumnFamilies.JOB_ACTIVATABLE, typeJobKey, ZbNil.INSTANCE);

    deadlineKey = new ZbLong();
    deadlineJobKey = new ZbCompositeKey<>();
    deadlinesColumnFamily =
        zeebeDb.createColumnFamily(ZbColumnFamilies.JOB_DEADLINES, deadlineJobKey, ZbNil.INSTANCE);

    this.zeebeDb = zeebeDb;
  }

  public void create(final long key, final JobRecord record) {
    final DirectBuffer type = record.getType();
    zeebeDb.batch(
        () -> {
          jobKey.wrapLong(key);
          jobRecord.wrapObject(record);
          jobsColumnFamily.put(jobKey, jobRecord);

          jobState.wrapByte(State.ACTIVATABLE.value);
          statesJobColumnFamily.put(jobKey, jobState);

          jobTypeKey.wrapBuffer(type);
          typeJobKey.wrapKeys(jobTypeKey, jobKey);
          activatableColumnFamily.put(typeJobKey, ZbNil.INSTANCE);
        });
  }

  public void activate(final long key, final JobRecord record) {
    final DirectBuffer type = record.getType();
    final long deadline = record.getDeadline();

    zeebeDb.batch(
        () -> {
          jobKey.wrapLong(key);
          jobRecord.wrapObject(record);
          // why i need to update this?!
          jobsColumnFamily.put(jobKey, jobRecord);

          jobState.wrapByte(State.ACTIVATED.value);
          statesJobColumnFamily.put(jobKey, jobState);

          jobTypeKey.wrapBuffer(type);
          typeJobKey.wrapKeys(jobTypeKey, jobKey);
          activatableColumnFamily.delete(typeJobKey);

          deadlineKey.wrapLong(deadline);
          deadlineJobKey.wrapKeys(deadlineKey, jobKey);
          deadlinesColumnFamily.put(deadlineJobKey, ZbNil.INSTANCE);
        });
  }

  public void timeout(final long key, final JobRecord record) {
    final DirectBuffer type = record.getType();
    final long deadline = record.getDeadline();

    try (WriteOptions options = new WriteOptions();
        ZbWriteBatch batch = new ZbWriteBatch()) {

      DirectBuffer keyBuffer = getDefaultKey(key);
      DirectBuffer valueBuffer = writeValue(record);
      batch.put(jobsColumnFamily, keyBuffer, valueBuffer);

      valueBuffer = writeStatesValue(State.ACTIVATABLE);
      batch.put(statesColumnFamily, keyBuffer, valueBuffer);

      keyBuffer = getActivatableKey(key, type);
      batch.put(activatableColumnFamily, keyBuffer, NULL);

      keyBuffer = getDeadlinesKey(key, deadline);
      batch.delete(deadlinesColumnFamily, keyBuffer);

      db.write(options, batch);
    } catch (RocksDBException e) {
      throw new RuntimeException(e);
    }
  }

  public void delete(long key, JobRecord record) {
    final DirectBuffer type = record.getType();
    final long deadline = record.getDeadline();

    try (WriteOptions options = new WriteOptions();
        ZbWriteBatch batch = new ZbWriteBatch()) {
      DirectBuffer keyBuffer = getDefaultKey(key);
      batch.delete(jobsColumnFamily, keyBuffer);

      batch.delete(statesColumnFamily, keyBuffer);

      final DirectBuffer activatableKey = getActivatableKey(key, type);
      batch.delete(activatableColumnFamily, activatableKey);

      if (isInState(key, State.ACTIVATED)) {
        keyBuffer = getDeadlinesKey(key, deadline);
        batch.delete(deadlinesColumnFamily, keyBuffer);
      }

      db.write(options, batch);
    } catch (RocksDBException e) {
      throw new RuntimeException(e);
    }
  }

  public void fail(long key, JobRecord updatedValue) {
    final DirectBuffer type = updatedValue.getType();
    final long deadline = updatedValue.getDeadline();

    try (WriteOptions options = new WriteOptions();
        ZbWriteBatch batch = new ZbWriteBatch()) {

      DirectBuffer keyBuffer = getDefaultKey(key);
      DirectBuffer valueBuffer = writeValue(updatedValue);
      batch.put(jobsColumnFamily, keyBuffer, valueBuffer);

      final State newState = updatedValue.getRetries() > 0 ? State.ACTIVATABLE : State.FAILED;

      valueBuffer = writeStatesValue(newState);
      batch.put(statesColumnFamily, keyBuffer, valueBuffer);

      if (newState == State.ACTIVATABLE) {
        keyBuffer = getActivatableKey(key, type);
        batch.put(activatableColumnFamily, keyBuffer, NULL);
      }

      keyBuffer = getDeadlinesKey(key, deadline);
      batch.delete(deadlinesColumnFamily, keyBuffer);

      db.write(options, batch);
    } catch (RocksDBException e) {
      throw new RuntimeException(e);
    }
  }

  public void resolve(long key, final JobRecord updatedValue) {
    final DirectBuffer type = updatedValue.getType();

    try (WriteOptions options = new WriteOptions();
        ZbWriteBatch batch = new ZbWriteBatch()) {
      DirectBuffer keyBuffer = getDefaultKey(key);
      DirectBuffer valueBuffer = writeValue(updatedValue);
      batch.put(jobsColumnFamily, keyBuffer, valueBuffer);

      valueBuffer = writeStatesValue(State.ACTIVATABLE);
      batch.put(statesColumnFamily, keyBuffer, valueBuffer);

      keyBuffer = getActivatableKey(key, type);
      batch.put(activatableColumnFamily, keyBuffer, NULL);

      db.write(options, batch);
    } catch (RocksDBException e) {
      throw new RuntimeException(e);
    }
  }

  public void forEachTimedOutEntry(final long upperBound, final IteratorConsumer callback) {
    db.forEach(
        deadlinesColumnFamily,
        (e, c) -> {
          final long deadline = e.getKey().getLong(0, STATE_BYTE_ORDER);
          if (deadline < upperBound) {
            final DirectBuffer keyBuffer = new UnsafeBuffer(e.getKey(), Long.BYTES, Long.BYTES);

            final JobRecord job = getJob(keyBuffer);
            final long jobKey = keyBuffer.getLong(0, STATE_BYTE_ORDER);
            if (job == null) {
              throw new IllegalStateException(
                  String.format("Expected to find job with key %d, but no job found", jobKey));
            }
            callback.accept(jobKey, job, c);

          } else {
            c.stop();
          }
        });
  }

  public boolean exists(long jobKey) {
    final DirectBuffer dbKey = getDefaultKey(jobKey);
    return db.exists(jobsColumnFamily, dbKey);
  }

  public boolean isInState(long key, State state) {
    final DirectBuffer keyBuffer = getDefaultKey(key);
    if (db.exists(statesColumnFamily, keyBuffer, valueBuffer)) {
      return valueBuffer.getByte(0) == state.value;
    } else {
      return false;
    }
  }

  public void forEachActivatableJobs(final DirectBuffer type, final IteratorConsumer callback) {
    final DirectBuffer prefix = getActivatablePrefix(type);

    // iterate by prefix, and since we're looking for exactly the type, once we find the first one,
    // it should iterate exactly over all those with that exact type, and once we hit a longer or
    // different one it should stop.
    db.forEachPrefixed(
        activatableColumnFamily,
        prefix,
        (e, c) -> {
          final DirectBuffer entryKey = e.getKey();
          final DirectBuffer typeBuffer =
              new UnsafeBuffer(entryKey, 0, entryKey.capacity() - Long.BYTES);
          if (contentsEqual(type, typeBuffer)) {
            final DirectBuffer keyBuffer =
                new UnsafeBuffer(entryKey, typeBuffer.capacity(), Long.BYTES);

            final JobRecord job = getJob(keyBuffer);
            final long jobKey = keyBuffer.getLong(0, STATE_BYTE_ORDER);
            if (job == null) {
              throw new IllegalStateException(
                  String.format("Expected to find job with key %d, but no job found", jobKey));
            }
            callback.accept(jobKey, job, c);

          } else {
            c.stop();
          }
        });
  }

  public JobRecord updateJobRetries(final long jobKey, final int retries) {
    final JobRecord job = getJob(jobKey);
    if (job != null) {
      job.setRetries(retries);

      final DirectBuffer keyBuffer = getDefaultKey(jobKey);
      final DirectBuffer valueBuffer = writeValue(job);
      db.put(jobsColumnFamily, keyBuffer, valueBuffer);
    }
    return job;
  }

  public JobRecord getJob(final long key) {
    final DirectBuffer keyBuffer = getDefaultKey(key);
    return getJob(keyBuffer);
  }

  private JobRecord getJob(final DirectBuffer keyBuffer) {
    final int bytesRead = db.get(jobsColumnFamily, keyBuffer, valueBuffer);

    if (bytesRead == RocksDB.NOT_FOUND) {
      return null;
    }

    return readJob(valueBuffer, bytesRead);
  }

  private JobRecord readJob(final DirectBuffer buffer, final int length) {
    final JobRecord record = new JobRecord();
    record.wrap(buffer, 0, length);

    return record;
  }

  private UnsafeBuffer getDefaultKey(final long key) {
    keyBuffer.putLong(0, key, STATE_BYTE_ORDER);
    return new UnsafeBuffer(keyBuffer, 0, Long.BYTES);
  }

  private UnsafeBuffer getActivatableKey(final long key, final DirectBuffer type) {
    EnsureUtil.ensureNotNullOrEmpty("type", type);
    final int typeLength = type.capacity();
    keyBuffer.putBytes(0, type, 0, typeLength);
    keyBuffer.putLong(typeLength, key, STATE_BYTE_ORDER);

    return new UnsafeBuffer(keyBuffer, 0, typeLength + Long.BYTES);
  }

  private UnsafeBuffer getActivatablePrefix(final DirectBuffer type) {
    EnsureUtil.ensureNotNullOrEmpty("type", type);
    final int typeLength = type.capacity();
    keyBuffer.putBytes(0, type, 0, typeLength);

    return new UnsafeBuffer(keyBuffer, 0, typeLength);
  }

  private UnsafeBuffer getDeadlinesKey(final long key, final long deadline) {
    EnsureUtil.ensureGreaterThan("deadline", deadline, 0);
    keyBuffer.putLong(0, deadline, STATE_BYTE_ORDER);
    keyBuffer.putLong(Long.BYTES, key, STATE_BYTE_ORDER);

    return new UnsafeBuffer(keyBuffer, 0, Long.BYTES * 2);
  }

  private UnsafeBuffer writeValue(final BufferWriter writer) {
    writer.write(valueBuffer, 0);
    return new UnsafeBuffer(valueBuffer, 0, writer.getLength());
  }

  private DirectBuffer writeStatesValue(final State state) {
    valueBuffer.putByte(0, state.value);
    return new UnsafeBuffer(valueBuffer, 0, 1);
  }

  public enum State {
    ACTIVATABLE((byte) 0),
    ACTIVATED((byte) 1),
    FAILED((byte) 2);

    byte value;

    State(byte value) {
      this.value = value;
    }
  }

  @FunctionalInterface
  public interface IteratorConsumer {
    void accept(long key, JobRecord record, IteratorControl control);
  }
}
