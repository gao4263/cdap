/*
 * Copyright © 2014-2015 Cask Data, Inc.
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

package co.cask.cdap.internal.app.runtime.spark;

import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.common.RuntimeArguments;
import co.cask.cdap.api.common.Scope;
import co.cask.cdap.api.dataset.lib.FileSet;
import co.cask.cdap.api.dataset.lib.FileSetArguments;
import co.cask.cdap.api.dataset.lib.KeyValueTable;
import co.cask.cdap.api.dataset.lib.ObjectStore;
import co.cask.cdap.api.dataset.lib.PartitionFilter;
import co.cask.cdap.api.dataset.lib.PartitionKey;
import co.cask.cdap.api.dataset.lib.PartitionOutput;
import co.cask.cdap.api.dataset.lib.PartitionedFileSet;
import co.cask.cdap.api.dataset.lib.PartitionedFileSetArguments;
import co.cask.cdap.api.dataset.lib.TimePartitionedFileSet;
import co.cask.cdap.api.dataset.lib.TimePartitionedFileSetArguments;
import co.cask.cdap.app.program.Program;
import co.cask.cdap.app.runtime.ProgramController;
import co.cask.cdap.app.runtime.ProgramRunner;
import co.cask.cdap.common.app.RunIds;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.data.dataset.DatasetInstantiator;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.internal.AppFabricTestHelper;
import co.cask.cdap.internal.DefaultId;
import co.cask.cdap.internal.TempFolder;
import co.cask.cdap.internal.app.deploy.pipeline.ApplicationWithPrograms;
import co.cask.cdap.internal.app.runtime.AbstractListener;
import co.cask.cdap.internal.app.runtime.BasicArguments;
import co.cask.cdap.internal.app.runtime.ProgramOptionConstants;
import co.cask.cdap.internal.app.runtime.ProgramRunnerFactory;
import co.cask.cdap.internal.app.runtime.SimpleProgramOptions;
import co.cask.cdap.proto.DatasetSpecificationSummary;
import co.cask.cdap.proto.Id;
import co.cask.cdap.test.XSlowTests;
import co.cask.tephra.TransactionExecutor;
import co.cask.tephra.TransactionExecutorFactory;
import co.cask.tephra.TransactionFailureException;
import co.cask.tephra.TransactionManager;
import co.cask.tephra.TxConstants;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Injector;
import org.apache.twill.common.Threads;
import org.apache.twill.filesystem.Location;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 *
 */
@Category(XSlowTests.class)
public class SparkProgramRunnerTest {

  private static final TempFolder TEMP_FOLDER = new TempFolder();

  private static Injector injector;
  private static TransactionExecutorFactory txExecutorFactory;

  private static TransactionManager txService;
  private static DatasetFramework dsFramework;
  private static DatasetInstantiator datasetInstantiator;

  final String testString1 = "persisted data";
  final String testString2 = "distributed systems";

  @ClassRule
  public static TemporaryFolder tmpFolder = new TemporaryFolder();

  private static final Supplier<File> TEMP_FOLDER_SUPPLIER = new Supplier<File>() {
    @Override
    public File get() {
      try {
        return tmpFolder.newFolder();
      } catch (IOException e) {
        throw Throwables.propagate(e);
      }
    }
  };

  @BeforeClass
  public static void beforeClass() {
    // we are only gonna do long-running transactions here. Set the tx timeout to a ridiculously low value.
    // that will test that the long-running transactions actually bypass that timeout.
    CConfiguration conf = CConfiguration.create();
    conf.set(Constants.CFG_LOCAL_DATA_DIR, TEMP_FOLDER.newFolder("data").getAbsolutePath());
    conf.setInt(TxConstants.Manager.CFG_TX_TIMEOUT, 1);
    conf.setInt(TxConstants.Manager.CFG_TX_CLEANUP_INTERVAL, 2);
    injector = AppFabricTestHelper.getInjector(conf);
    txService = injector.getInstance(TransactionManager.class);
    txExecutorFactory = injector.getInstance(TransactionExecutorFactory.class);
    dsFramework = injector.getInstance(DatasetFramework.class);
    datasetInstantiator = new DatasetInstantiator(DefaultId.NAMESPACE, dsFramework,
                                                  SparkProgramRunnerTest.class.getClassLoader(),
                                                  null, null);

    txService.startAndWait();
  }

  @AfterClass
  public static void afterClass() throws Exception {
    txService.stopAndWait();
  }

  @After
  public void after() throws Exception {
    // cleanup user data (only user datasets)
    for (DatasetSpecificationSummary spec : dsFramework.getInstances(DefaultId.NAMESPACE)) {
      dsFramework.deleteInstance(Id.DatasetInstance.from(DefaultId.NAMESPACE, spec.getName()));
    }
  }

  @Test
  public void testSparkWithObjectStore() throws Exception {
    final ApplicationWithPrograms app =
      AppFabricTestHelper.deployApplicationWithManager(SparkAppUsingObjectStore.class, TEMP_FOLDER_SUPPLIER);

    prepareInputData();
    runProgram(app, SparkAppUsingObjectStore.CharCountSpecification.class);
    checkOutputData();
  }

  @Test
  public void testScalaSparkWithObjectStore() throws Exception {
    final ApplicationWithPrograms app =
      AppFabricTestHelper.deployApplicationWithManager(ScalaSparkAppUsingObjectStore.class, TEMP_FOLDER_SUPPLIER);

    prepareInputData();
    runProgram(app, ScalaSparkAppUsingObjectStore.CharCountSpecification.class);
    checkOutputData();
  }

  @Test
  public void testSparkWithFileSet() throws Exception {
    final ApplicationWithPrograms app =
      AppFabricTestHelper.deployApplicationWithManager(SparkAppUsingFileSet.class, TEMP_FOLDER_SUPPLIER);

    final FileSet inputDS = datasetInstantiator.getDataset("fs");
    Location location = inputDS.getLocation("nn");
    prepareFileInput(location);

    Map<String, String> args = new HashMap<>();
    FileSetArguments.setInputPath(args, "nn");
    args = RuntimeArguments.addScope(Scope.DATASET, "fs", args);
    args.put("input", "fs");
    runProgram(app, SparkAppUsingFileSet.CharCountSpecification.class, args);

    validateFileOutput();
  }

  @Test
  public void testSparkWithPartitionedFileSet() throws Exception {
    final ApplicationWithPrograms app =
      AppFabricTestHelper.deployApplicationWithManager(SparkAppUsingFileSet.class, TEMP_FOLDER_SUPPLIER);

    final PartitionedFileSet inputDS = datasetInstantiator.getDataset("pfs");
    final PartitionOutput partitionOutput = inputDS.getPartitionOutput(
      PartitionKey.builder().addStringField("x", "nn").build());
    Location location = partitionOutput.getLocation();
    prepareFileInput(location);
    txExecutorFactory.createExecutor(datasetInstantiator.getTransactionAware()).execute(
      new TransactionExecutor.Subroutine() {
        @Override
        public void apply() throws Exception {
          partitionOutput.addPartition();
        }
      });

    Map<String, String> args = new HashMap<>();
    PartitionedFileSetArguments.setInputPartitionFilter(
      args, PartitionFilter.builder().addRangeCondition("x", "na", "nx").build());
    args = RuntimeArguments.addScope(Scope.DATASET, "pfs", args);
    args.put("input", "pfs");
    runProgram(app, SparkAppUsingFileSet.CharCountSpecification.class, args);

    validateFileOutput();
  }

  @Test
  public void testSparkWithTimePartitionedFileSet() throws Exception {
    final ApplicationWithPrograms app =
      AppFabricTestHelper.deployApplicationWithManager(SparkAppUsingFileSet.class, TEMP_FOLDER_SUPPLIER);

    long time = System.currentTimeMillis();
    final TimePartitionedFileSet inputDS = datasetInstantiator.getDataset("tpfs");
    final PartitionOutput partitionOutput = inputDS.getPartitionOutput(time);
    Location location = partitionOutput.getLocation();
    prepareFileInput(location);
    txExecutorFactory.createExecutor(datasetInstantiator.getTransactionAware()).execute(
      new TransactionExecutor.Subroutine() {
        @Override
        public void apply() throws Exception {
          partitionOutput.addPartition();
        }
      });

    Map<String, String> args = new HashMap<>();
    TimePartitionedFileSetArguments.setInputStartTime(args, time - 100);
    TimePartitionedFileSetArguments.setInputEndTime(args, time + 100);
    args = RuntimeArguments.addScope(Scope.DATASET, "tpfs", args);
    args.put("input", "tpfs");
    runProgram(app, SparkAppUsingFileSet.CharCountSpecification.class, args);

    validateFileOutput();
  }

  private void validateFileOutput() throws InterruptedException, TransactionFailureException {
    final KeyValueTable output = datasetInstantiator.getDataset("count");
    //read output and verify result
    txExecutorFactory.createExecutor(datasetInstantiator.getTransactionAware()).execute(
      new TransactionExecutor.Subroutine() {
        @Override
        public void apply() {
          byte[] val = output.read(Bytes.toBytes(0L));
          Assert.assertTrue(val != null);
          Assert.assertEquals(Bytes.toInt(val), 13);

          val = output.read(Bytes.toBytes(14L));
          Assert.assertTrue(val != null);
          Assert.assertEquals(Bytes.toInt(val), 7);

        }
      });
  }

  private void prepareFileInput(Location location) throws IOException {
    try (OutputStreamWriter out = new OutputStreamWriter(location.getOutputStream())) {
      out.write("13 characters\n");
      out.write("7 chars\n");
    }
  }

  private void prepareInputData() throws TransactionFailureException, InterruptedException {
    final ObjectStore<String> input = datasetInstantiator.getDataset("keys");

    //Populate some input
    txExecutorFactory.createExecutor(datasetInstantiator.getTransactionAware()).execute(
      new TransactionExecutor.Subroutine() {
        @Override
        public void apply() {
          input.write(Bytes.toBytes(testString1), testString1);
          input.write(Bytes.toBytes(testString2), testString2);
        }
      });
  }

  private void checkOutputData() throws TransactionFailureException, InterruptedException {
    final KeyValueTable output = datasetInstantiator.getDataset("count");
    //read output and verify result
    txExecutorFactory.createExecutor(datasetInstantiator.getTransactionAware()).execute(
      new TransactionExecutor.Subroutine() {
        @Override
        public void apply() {
          byte[] val = output.read(Bytes.toBytes(testString1));
          Assert.assertTrue(val != null);
          Assert.assertEquals(Bytes.toInt(val), testString1.length());

          val = output.read(Bytes.toBytes(testString2));
          Assert.assertTrue(val != null);
          Assert.assertEquals(Bytes.toInt(val), testString2.length());

        }
      });
  }

  private void runProgram(ApplicationWithPrograms app, Class<?> programClass) throws Exception {
    runProgram(app, programClass, RuntimeArguments.NO_ARGUMENTS);
  }

  private void runProgram(ApplicationWithPrograms app, Class<?> programClass, Map<String, String> args)
    throws Exception {
    waitForCompletion(submit(app, programClass, args));
  }

  private void waitForCompletion(ProgramController controller) throws InterruptedException {
    final CountDownLatch completion = new CountDownLatch(1);
    controller.addListener(new AbstractListener() {
      @Override
      public void completed() {
        completion.countDown();
      }

      @Override
      public void error(Throwable cause) {
        completion.countDown();
      }
    }, Threads.SAME_THREAD_EXECUTOR);

    completion.await(10, TimeUnit.MINUTES);
  }

  private ProgramController submit(ApplicationWithPrograms app,
                                   Class<?> programClass,
                                   Map<String, String> userArgs) throws ClassNotFoundException {

    ProgramRunnerFactory runnerFactory = injector.getInstance(ProgramRunnerFactory.class);
    Program program = getProgram(app, programClass);
    Assert.assertNotNull(program);
    ProgramRunner runner = runnerFactory.create(ProgramRunnerFactory.Type.valueOf(program.getType().name()));

    BasicArguments systemArgs = new BasicArguments(ImmutableMap.of(ProgramOptionConstants.RUN_ID,
                                                                   RunIds.generate().getId()));

    return runner.run(program, new SimpleProgramOptions(program.getName(), systemArgs, new BasicArguments(userArgs)));
  }

  private Program getProgram(ApplicationWithPrograms app, Class<?> programClass) throws ClassNotFoundException {
    for (Program p : app.getPrograms()) {
      if (programClass.getCanonicalName().equals(p.getMainClass().getCanonicalName())) {
        return p;
      }
    }
    return null;
  }
}
