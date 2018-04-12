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

import co.cask.cdap.api.dataset.DatasetProperties;
import co.cask.cdap.api.lineage.field.EndPoint;
import co.cask.cdap.api.lineage.field.InputField;
import co.cask.cdap.api.lineage.field.Operation;
import co.cask.cdap.api.lineage.field.ReadOperation;
import co.cask.cdap.api.lineage.field.TransformOperation;
import co.cask.cdap.api.lineage.field.WriteOperation;
import co.cask.cdap.common.app.RunIds;
import co.cask.cdap.data2.datafabric.dataset.DatasetsUtil;
import co.cask.cdap.data2.dataset2.DatasetFrameworkTestUtil;
import co.cask.cdap.proto.ProgramType;
import co.cask.cdap.proto.id.DatasetId;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.proto.id.ProgramRunId;
import org.apache.tephra.TransactionAware;
import org.apache.tephra.TransactionExecutor;
import org.apache.twill.api.RunId;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Test for storage and retrieval of the field lineage operations.
 */
public class FieldLineageDatasetTest {
  @ClassRule
  public static DatasetFrameworkTestUtil dsFrameworkUtil = new DatasetFrameworkTestUtil();

  @Test
  public void testSimpleOperations() throws Exception {
    final FieldLineageDataset fieldLineageDataset = getFieldLineageDataset("testSimpleOperations");
    Assert.assertNotNull(fieldLineageDataset);
    TransactionExecutor txnl = dsFrameworkUtil.newInMemoryTransactionExecutor((TransactionAware) fieldLineageDataset);

    RunId runId = RunIds.generate(10000);
    ProgramId program = new ProgramId("default", "app1", ProgramType.WORKFLOW, "workflow1");
    final ProgramRunId programRun1 = program.run(runId.getId());

    runId = RunIds.generate(11000);
    program = new ProgramId("default", "app1", ProgramType.WORKFLOW, "workflow2");
    final ProgramRunId programRun2 = program.run(runId.getId());

    final FieldLineageInfo fieldLineageInfo
            = new FieldLineageInfo(getOperations(new String[] {"offset", "body"}));

    txnl.execute(() -> fieldLineageDataset.addFieldLineageInfo(programRun1, fieldLineageInfo));
    txnl.execute(() -> fieldLineageDataset.addFieldLineageInfo(programRun2, fieldLineageInfo));

    runId = RunIds.generate(12000);
    program = new ProgramId("default", "app1", ProgramType.WORKFLOW, "workflow3");
    final ProgramRunId programRun3 = program.run(runId.getId());

    final FieldLineageInfo anotherFieldLineageInfo
            = new FieldLineageInfo(getOperations(new String[] {"offset", "body", "file"}));

    txnl.execute(() -> fieldLineageDataset.addFieldLineageInfo(programRun3, anotherFieldLineageInfo));

    txnl.execute(() -> {
      EndPoint source = EndPoint.of("ns1", "endpoint1");
      EndPoint destination = EndPoint.of("myns", "another_file");

      Set<String> expectedFields = new HashSet<>(Arrays.asList("offset", "name"));
      Assert.assertEquals(expectedFields, fieldLineageDataset.getFields(destination, 0, 100000));

      EndPointField expectedEndPointField = new EndPointField(source, "offset");
      Set<EndPointField> actualEndPointFields
              = fieldLineageDataset.getIncomingSummary(new EndPointField(destination, "offset"), 0, 10000);
      Assert.assertEquals(expectedEndPointField, actualEndPointFields.iterator().next());

      expectedEndPointField = new EndPointField(source, "body");
      actualEndPointFields = fieldLineageDataset.getIncomingSummary(new EndPointField(destination, "name"), 0, 10000);
      Assert.assertEquals(expectedEndPointField, actualEndPointFields.iterator().next());

      expectedEndPointField = new EndPointField(destination, "offset");
      actualEndPointFields = fieldLineageDataset.getOutgoingSummary(new EndPointField(source, "offset"), 0, 10000);
      Assert.assertEquals(expectedEndPointField, actualEndPointFields.iterator().next());

      expectedEndPointField = new EndPointField(destination, "name");
      actualEndPointFields = fieldLineageDataset.getOutgoingSummary(new EndPointField(source, "body"), 0, 10000);
      Assert.assertEquals(expectedEndPointField, actualEndPointFields.iterator().next());

      Set<ProgramRunOperations> incomingOperations = fieldLineageDataset.getIncomingOperations(destination, 0, 10000);
      Set<ProgramRunOperations> outgoingOperations = fieldLineageDataset.getOutgoingOperations(source, 0, 10000);
      Assert.assertEquals(1, incomingOperations.size());
      Assert.assertEquals(incomingOperations, outgoingOperations);

      ProgramRunOperations programRunOperations = incomingOperations.iterator().next();
      Assert.assertEquals(programRunOperations.getChecksum(), fieldLineageInfo.getChecksum());

      Assert.assertEquals(Collections.singleton(programRun1), programRunOperations.getProgramRunIds());

      // test with bigger time range
      incomingOperations = fieldLineageDataset.getIncomingOperations(destination, 0, 11000);
      outgoingOperations = fieldLineageDataset.getOutgoingOperations(source, 0, 11000);
      // since checksum is same both for operations it should still store as same row
      Assert.assertEquals(1, incomingOperations.size());
      Assert.assertEquals(incomingOperations, outgoingOperations);
      programRunOperations = incomingOperations.iterator().next();
      Assert.assertEquals(new HashSet<>(Arrays.asList(programRun1, programRun2)),
                          programRunOperations.getProgramRunIds());

      // test for multiple set of operations
      incomingOperations = fieldLineageDataset.getIncomingOperations(destination, 0, 12000);
      outgoingOperations = fieldLineageDataset.getOutgoingOperations(source, 0, 12000);

      Assert.assertEquals(2, incomingOperations.size());
      Assert.assertEquals(incomingOperations, outgoingOperations);

      Set<ProgramRunOperations> expectedSet = new HashSet<>();
      expectedSet.add(new ProgramRunOperations(fieldLineageInfo.getChecksum(),
              new HashSet<>(Arrays.asList(programRun1, programRun2)), fieldLineageInfo.getOperations()));
      expectedSet.add(new ProgramRunOperations(anotherFieldLineageInfo.getChecksum(),
              Collections.singleton(programRun3), anotherFieldLineageInfo.getOperations()));

      Assert.assertEquals(expectedSet, incomingOperations);
      Assert.assertEquals(expectedSet, outgoingOperations);
    });
  }

  private static FieldLineageDataset getFieldLineageDataset(String instanceId) throws Exception {
    DatasetId id = DatasetFrameworkTestUtil.NAMESPACE_ID.dataset(instanceId);
    return DatasetsUtil.getOrCreateDataset(dsFrameworkUtil.getFramework(), id, FieldLineageDataset.class.getName(),
                                           DatasetProperties.EMPTY, null);
  }

  private List<Operation> getOperations(String[] readOutput) {
    // read: file -> (offset, body)
    // parse: (body) -> (first_name, last_name)
    // concat: (first_name, last_name) -> (name)
    // write: (offset, name) -> another_file

    ReadOperation read = new ReadOperation("read", "some read", EndPoint.of("ns1", "endpoint1"), readOutput);

    TransformOperation parse = new TransformOperation("parse", "parsing body",
            Collections.singletonList(InputField.of("read", "body")),
            "first_name", "last_name");

    TransformOperation concat = new TransformOperation("concat", "concatinating the fields",
            Arrays.asList(InputField.of("parse", "first_name"),
                    InputField.of("parse", "last_name")), "name");

    WriteOperation write = new WriteOperation("write_op", "writing data to file",
            EndPoint.of("myns", "another_file"),
            Arrays.asList(InputField.of("read", "offset"),
                    InputField.of("concat", "name")));

    List<Operation> operations = new ArrayList<>();
    operations.add(parse);
    operations.add(concat);
    operations.add(read);
    operations.add(write);

    return operations;
  }
}
