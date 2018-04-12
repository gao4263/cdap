/*
 * Copyright © 2018 Cask Data, Inc.
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

package co.cask.cdap.data2.metadata.lineage.field;

import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.data.DatasetContext;
import co.cask.cdap.api.dataset.DatasetProperties;
import co.cask.cdap.api.dataset.lib.AbstractDataset;
import co.cask.cdap.api.dataset.table.Put;
import co.cask.cdap.api.dataset.table.Row;
import co.cask.cdap.api.dataset.table.Scanner;
import co.cask.cdap.api.dataset.table.Table;
import co.cask.cdap.api.lineage.field.EndPoint;
import co.cask.cdap.api.lineage.field.Operation;
import co.cask.cdap.common.app.RunIds;
import co.cask.cdap.data2.datafabric.dataset.DatasetsUtil;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.data2.dataset2.lib.table.MDSKey;
import co.cask.cdap.proto.codec.OperationTypeAdapter;
import co.cask.cdap.proto.id.DatasetId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.id.ProgramRunId;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Dataset to store/retrieve field level lineage information.
 */
public class FieldLineageDataset extends AbstractDataset {

  // Storage format
  // --------------
  //
  // 1. For each unique set of operations one row is stored. Uniqueness of set of operations
  // is determined by the checksum of the operations.
  //
  // Row Key: c | <checksum-value>
  //
  // Column information:
  // a) Name: f | <EndPoint>
  // For each destination EndPoint in the set of operations, this column
  // stores the fields generated by those operations belonging to the given EndPoint.
  //
  // b) Name: i | <EndPoint | <field>
  // For each field generated for each of the destination EndPoint, this column stores
  // the collection of source EndPoint and field responsible for generating the given field.
  //
  // c) Name: o | <EndPoint> | <field>
  // For each field belonging to each of the source EndPoint, this column stores the
  // collection of destination EndPoint and fields that were generated by the given field.
  //
  // d) Name: r
  // Stores the set of operations.
  //
  // 2. Run level row:
  // For each destination EndPoint, reference to the checksum row is added in incoming direction.
  //
  //                                          --------------------------
  //                                          |     c      |    p      |
  // -------------------------------------------------------------------
  // | i | <EndPoint> | <inverted-start-time> | <checksum> | <program> |
  // -------------------------------------------------------------------
  //
  // For each source EndPoint, reference to the checksum row is added in outgoing direction.
  //
  //                                          ----------------------------
  //                                          |     c      |     p       |
  // ---------------------------------------------------------------------
  // | o | <EndPoint> | <inverted-start-time> | <checksum> |   <program> |
  // ---------------------------------------------------------------------


  public static final DatasetId FIELD_LINEAGE_DATASET_ID = NamespaceId.SYSTEM.dataset("fieldLineage");

  private static final Logger LOG = LoggerFactory.getLogger(FieldLineageDataset.class);
  private static final Gson GSON = new GsonBuilder()
    .registerTypeAdapter(Operation.class, new OperationTypeAdapter())
    .create();

  private static final byte[] CHECKSUM_MARKER = {'c'};
  private static final byte[] FIELD_MARKER = {'f'};
  private static final byte[] INCOMING_DIRECTION_MARKER = {'i'};
  private static final byte[] OUTGOING_DIRECTION_MARKER = {'o'};
  private static final byte[] RAW_OPERATION_MARKER = {'r'};
  private static final byte[] PROGRAM_MARKER = {'p'};

  private static final Type SET_FIELD_TYPE = new TypeToken<HashSet<String>>() { }.getType();
  private static final Type SET_ENDPOINT_FIELD_TYPE = new TypeToken<HashSet<EndPointField>>() { }.getType();
  private static final Type SET_OPERATION_TYPE = new TypeToken<HashSet<Operation>>() { }.getType();

  private final Table table;

  public FieldLineageDataset(String instanceName, Table table) {
    super(instanceName, table);
    this.table = table;
  }

  /**
   * Gets an instance of {@link FieldLineageDataset}. The dataset instance will be created if it is not yet exist.
   *
   * @param datasetContext the {@link DatasetContext} for getting the dataset instance.
   * @param datasetFramework the {@link DatasetFramework} for creating the dataset instance if missing
   * @param datasetId the {@link DatasetId} of the {@link FieldLineageDataset}
   * @return an instance of {@link FieldLineageDataset}
   */
  @VisibleForTesting
  public static FieldLineageDataset getFieldLineageDataset(DatasetContext datasetContext,
                                                           DatasetFramework datasetFramework, DatasetId datasetId) {
    try {
      return DatasetsUtil.getOrCreateDataset(datasetContext, datasetFramework, datasetId,
                                             FieldLineageDataset.class.getName(), DatasetProperties.EMPTY);
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  /**
   * Store the field lineage information.
   *
   * @param info the field lineage information
   */
  public void addFieldLineageInfo(ProgramRunId programRunId, FieldLineageInfo info) {
    byte[] rowKey = getChecksumRowKey(info.getChecksum());
    if (table.get(rowKey).isEmpty()) {
      Put put = new Put(rowKey);
      put.add(RAW_OPERATION_MARKER, GSON.toJson(info.getOperations()));

      Map<EndPoint, Set<String>> destinationFields = info.getDestinationFields();
      for (Map.Entry<EndPoint, Set<String>> entry : destinationFields.entrySet()) {
        put.add(getFieldColumnKey(entry.getKey()), GSON.toJson(entry.getValue()));
      }

      addSummary(put, INCOMING_DIRECTION_MARKER, info.getIncomingSummary());
      addSummary(put, OUTGOING_DIRECTION_MARKER, info.getOutgoingSummary());
      table.put(put);
    }

    addFieldLineageInfoReferenceRecords(programRunId, info);
  }

  /**
   * Add records referring to the common operation record having the given checksum.
   * Operations represent transformations from source endpoints to the destination endpoints.
   * From source perspective the operations are added as lineage in outgoing direction, while from
   * destination perspective they are added as lineage in incoming direction.
   *
   * @param programRunId program run for which lineage is to be added
   * @param info the FieldLineageInfo created by program run
   */
  private void addFieldLineageInfoReferenceRecords(ProgramRunId programRunId, FieldLineageInfo info) {
    // For all the destinations, operations represents incoming lineage
    for (EndPoint destination : info.getDestinations()) {
      addOperationReferenceRecord(INCOMING_DIRECTION_MARKER, destination, programRunId, info.getChecksum());
    }

    // For all the sources, operations represents the outgoing lineage
    for (EndPoint source : info.getSources()) {
      addOperationReferenceRecord(OUTGOING_DIRECTION_MARKER, source, programRunId, info.getChecksum());
    }
  }

  public Set<String> getFields(EndPoint endPoint, long start, long end) {
    // TODO: can this list be very large??
    Set<Long> checksums = getChecksumsWithProgramRunsInRange(INCOMING_DIRECTION_MARKER, endPoint, start, end).keySet();
    Set<String> result = new HashSet<>();
    byte[] columnKey = getFieldColumnKey(endPoint);
    for (long checksum : checksums) {
      byte[] rowKey = getChecksumRowKey(checksum);
      String value = Bytes.toString(table.get(rowKey, columnKey));
      Set<String> fields = GSON.fromJson(value, SET_FIELD_TYPE);
      if (fields != null) {
        result.addAll(fields);
      }
    }
    return result;
  }

  public Set<EndPointField> getIncomingSummary(EndPointField endPointField, long start, long end) {
    return getSummary(INCOMING_DIRECTION_MARKER, endPointField, start, end);
  }

  public Set<EndPointField> getOutgoingSummary(EndPointField endPointField, long start, long end) {
    return getSummary(OUTGOING_DIRECTION_MARKER, endPointField, start, end);
  }

  private Set<EndPointField> getSummary(byte[] direction, EndPointField endPointField, long start, long end) {
    Set<Long> checksums = getChecksumsWithProgramRunsInRange(direction, endPointField.getEndPoint(),
                                                             start, end).keySet();
    Set<EndPointField> result = new HashSet<>();
    byte[] columnKey = getSummaryColumnKey(direction, endPointField);

    for (long checksum : checksums) {
      byte[] rowKey = getChecksumRowKey(checksum);
      String value = Bytes.toString(table.get(rowKey, columnKey));
      Set<EndPointField> endPointFields = GSON.fromJson(value, SET_ENDPOINT_FIELD_TYPE);
      if (endPointFields != null) {
        // TODO: can it be null ever??
        result.addAll(endPointFields);
      }
    }

    return result;
  }

  public Set<ProgramRunOperations> getIncomingOperations(EndPoint endPoint, long start, long end) {
    return getOperations(INCOMING_DIRECTION_MARKER, endPoint, start, end);
  }

  public Set<ProgramRunOperations> getOutgoingOperations(EndPoint endPoint, long start, long end) {
    return getOperations(OUTGOING_DIRECTION_MARKER, endPoint, start, end);
  }

  private Set<ProgramRunOperations> getOperations(byte[] direction, EndPoint endPoint, long start, long end) {
    Map<Long, Set<ProgramRunId>> checksumsWithProgramRunsInRange
            = getChecksumsWithProgramRunsInRange(direction, endPoint, start, end);

    Set<Long> checksums = checksumsWithProgramRunsInRange.keySet();
    Set<ProgramRunOperations> result = new HashSet<>();

    for (long checksum : checksums) {
      byte[] rowKey = getChecksumRowKey(checksum);
      String value = Bytes.toString(table.get(rowKey, RAW_OPERATION_MARKER));
      Set<Operation> operations = GSON.fromJson(value, SET_OPERATION_TYPE);
      if (operations != null) {
        result.add(new ProgramRunOperations(checksum, checksumsWithProgramRunsInRange.get(checksum), operations));
      }
    }

    return result;
  }

  private Map<Long, Set<ProgramRunId>> getChecksumsWithProgramRunsInRange(byte[] direction, EndPoint endPoint,
                                                                          long start, long end) {
    byte[] scanStartKey = getScanStartKey(direction, endPoint, end);
    byte[] scanEndKey = getScanEndKey(direction, endPoint, start);
    Map<Long, Set<ProgramRunId>> result = new HashMap<>();
    try (Scanner scanner = table.scan(scanStartKey, scanEndKey)) {
      Row row;
      while ((row = scanner.next()) != null) {
        if (LOG.isTraceEnabled()) {
          LOG.trace("Got row key = {}", Bytes.toString(row.getRow()));
        }

        long checksum = Bytes.toLong(row.get(CHECKSUM_MARKER));
        ProgramRunId programRunId = GSON.fromJson(Bytes.toString(row.get(PROGRAM_MARKER)), ProgramRunId.class);
        Set<ProgramRunId> programRuns = result.computeIfAbsent(checksum, k -> new HashSet<>());
        programRuns.add(programRunId);
      }
    }
    return result;
  }

  private byte[] getScanStartKey(byte[] direction, EndPoint endPoint, long end) {
    // time is inverted, hence we need to have end time in start key.
    // Since end time is exclusive, add 1 to make it inclusive.
    return getScanKey(direction, endPoint, end + 1);
  }

  private byte[] getScanEndKey(byte[] direction, EndPoint endPoint, long start) {
    // time is inverted, hence we need to have start time in end key.
    // Since start time is inclusive, subtract 1 to make it exclusive.
    return getScanKey(direction, endPoint, start - 1);
  }

  private byte[] getScanKey(byte[] direction, EndPoint endPoint, long time) {
    MDSKey.Builder builder = new MDSKey.Builder();
    builder.add(direction);
    addEndPoint(builder, endPoint);
    builder.add(invertTime(time));
    return builder.build().getKey();
  }

  private void addOperationReferenceRecord(byte[] direction, EndPoint endPoint, ProgramRunId programRunId,
                                           long checksum) {
    byte[] rowKey = getOperationReferenceRowKey(direction, endPoint, programRunId);
    Put put = new Put(rowKey);
    put.add(CHECKSUM_MARKER, checksum);
    put.add(PROGRAM_MARKER, GSON.toJson(programRunId));
    table.put(put);
  }

  private byte[] getChecksumRowKey(long checksum) {
    MDSKey.Builder builder = new MDSKey.Builder();
    builder.add(CHECKSUM_MARKER);
    builder.add(checksum);
    return builder.build().getKey();
  }

  private byte[] getOperationReferenceRowKey(byte[] direction, EndPoint endPoint, ProgramRunId programRunId) {
    long invertedStartTime = getInvertedStartTime(programRunId);
    MDSKey.Builder builder = new MDSKey.Builder();
    builder.add(direction);
    addEndPoint(builder, endPoint);
    builder.add(invertedStartTime);
    return builder.build().getKey();
  }

  private byte[] getFieldColumnKey(EndPoint endPoint) {
    MDSKey.Builder builder = new MDSKey.Builder();
    builder.add(FIELD_MARKER);
    addEndPoint(builder, endPoint);
    return builder.build().getKey();
  }

  private long invertTime(long time) {
    return Long.MAX_VALUE - time;
  }

  private long getInvertedStartTime(ProgramRunId run) {
    return invertTime(RunIds.getTime(RunIds.fromString(run.getEntityName()), TimeUnit.MILLISECONDS));
  }

  private void addEndPoint(MDSKey.Builder keyBuilder, EndPoint endPoint) {
    keyBuilder.add(endPoint.getNamespace())
      .add(endPoint.getName());
  }

  private void addSummary(Put put, byte[] direction, Map<EndPointField, Set<EndPointField>> summary) {
    for (Map.Entry<EndPointField, Set<EndPointField>> entry : summary.entrySet()) {
      put.add(getSummaryColumnKey(direction, entry.getKey()), GSON.toJson(entry.getValue()));
    }
  }

  private byte[] getSummaryColumnKey(byte[] direction, EndPointField endPointField) {
    MDSKey.Builder builder = new MDSKey.Builder();
    builder.add(direction);
    addEndPoint(builder, endPointField.getEndPoint());
    builder.add(endPointField.getField());
    return builder.build().getKey();
  }
}
