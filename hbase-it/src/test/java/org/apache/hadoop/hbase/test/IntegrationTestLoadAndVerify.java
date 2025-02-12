/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.Sets;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.IntegrationTestBase;
import org.apache.hadoop.hbase.IntegrationTestingUtility;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.testclassification.IntegrationTests;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.BufferedMutator;
import org.apache.hadoop.hbase.client.BufferedMutatorParams;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.ScannerCallable;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.NMapInputFormat;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.mapreduce.TableRecordReaderImpl;
import org.apache.hadoop.hbase.util.AbstractHBaseTool;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.ToolRunner;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.IOException;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A large test which loads a lot of data that has internal references, and
 * verifies the data.
 *
 * In load step, 200 map tasks are launched, which in turn write loadmapper.num_to_write
 * (default 100K) rows to an hbase table. Rows are written in blocks, for a total of
 * 100 blocks. Each row in a block, contains loadmapper.backrefs (default 50) references
 * to random rows in the prev block.
 *
 * Verify step is scans the table, and verifies that for every referenced row, the row is
 * actually there (no data loss). Failed rows are output from reduce to be saved in the
 * job output dir in hdfs and inspected later.
 *
 * This class can be run as a unit test, as an integration test, or from the command line
 *
 * Originally taken from Apache Bigtop.
 */
@Category(IntegrationTests.class)
public class IntegrationTestLoadAndVerify  extends IntegrationTestBase  {
  private static final String TEST_NAME = "IntegrationTestLoadAndVerify";
  private static final byte[] TEST_FAMILY = Bytes.toBytes("f1");
  private static final byte[] TEST_QUALIFIER = Bytes.toBytes("q1");

  private static final String NUM_TO_WRITE_KEY =
    "loadmapper.num_to_write";
  private static final long NUM_TO_WRITE_DEFAULT = 100*1000;

  private static final String TABLE_NAME_KEY = "loadmapper.table";
  private static final String TABLE_NAME_DEFAULT = "table";

  private static final String NUM_BACKREFS_KEY = "loadmapper.backrefs";
  private static final int NUM_BACKREFS_DEFAULT = 50;

  private static final String NUM_MAP_TASKS_KEY = "loadmapper.map.tasks";
  private static final String NUM_REDUCE_TASKS_KEY = "verify.reduce.tasks";
  private static final int NUM_MAP_TASKS_DEFAULT = 200;
  private static final int NUM_REDUCE_TASKS_DEFAULT = 35;

  private static final int SCANNER_CACHING = 500;

  private String toRun = null;

  private enum Counters {
    ROWS_WRITTEN,
    REFERENCES_WRITTEN,
    REFERENCES_CHECKED
  }

  @Override
  public void setUpCluster() throws Exception {
    util = getTestingUtil(getConf());
    util.initializeCluster(3);
    this.setConf(util.getConfiguration());
    if (!util.isDistributedCluster()) {
      getConf().setLong(NUM_TO_WRITE_KEY, NUM_TO_WRITE_DEFAULT / 100);
      getConf().setInt(NUM_MAP_TASKS_KEY, NUM_MAP_TASKS_DEFAULT / 100);
      getConf().setInt(NUM_REDUCE_TASKS_KEY, NUM_REDUCE_TASKS_DEFAULT / 10);
      util.startMiniMapReduceCluster();
    }
  }

@Override
public void cleanUpCluster() throws Exception {
  super.cleanUpCluster();
  if (!util.isDistributedCluster()) {
    util.shutdownMiniMapReduceCluster();
  }
}

  /**
   * Converts a "long" value between endian systems.
   * Borrowed from Apache Commons IO
   * @param value value to convert
   * @return the converted value
   */
  public static long swapLong(long value)
  {
    return
      ( ( ( value >> 0 ) & 0xff ) << 56 ) +
      ( ( ( value >> 8 ) & 0xff ) << 48 ) +
      ( ( ( value >> 16 ) & 0xff ) << 40 ) +
      ( ( ( value >> 24 ) & 0xff ) << 32 ) +
      ( ( ( value >> 32 ) & 0xff ) << 24 ) +
      ( ( ( value >> 40 ) & 0xff ) << 16 ) +
      ( ( ( value >> 48 ) & 0xff ) << 8 ) +
      ( ( ( value >> 56 ) & 0xff ) << 0 );
  }

  public static class LoadMapper
      extends Mapper<NullWritable, NullWritable, NullWritable, NullWritable>
  {
    protected long recordsToWrite;
    protected Connection connection;
    protected BufferedMutator mutator;
    protected Configuration conf;
    protected int numBackReferencesPerRow;
    protected String shortTaskId;

    protected Random rand = new Random();

    protected Counter rowsWritten, refsWritten;

    @Override
    public void setup(Context context) throws IOException {
      conf = context.getConfiguration();
      recordsToWrite = conf.getLong(NUM_TO_WRITE_KEY, NUM_TO_WRITE_DEFAULT);
      String tableName = conf.get(TABLE_NAME_KEY, TABLE_NAME_DEFAULT);
      numBackReferencesPerRow = conf.getInt(NUM_BACKREFS_KEY, NUM_BACKREFS_DEFAULT);
      this.connection = ConnectionFactory.createConnection(conf);
      mutator = connection.getBufferedMutator(
          new BufferedMutatorParams(TableName.valueOf(tableName))
              .writeBufferSize(4 * 1024 * 1024));

      String taskId = conf.get("mapreduce.task.attempt.id");
      Matcher matcher = Pattern.compile(".+_m_(\\d+_\\d+)").matcher(taskId);
      if (!matcher.matches()) {
        throw new RuntimeException("Strange task ID: " + taskId);
      }
      shortTaskId = matcher.group(1);

      rowsWritten = context.getCounter(Counters.ROWS_WRITTEN);
      refsWritten = context.getCounter(Counters.REFERENCES_WRITTEN);
    }

    @Override
    public void cleanup(Context context) throws IOException {
      mutator.close();
      connection.close();
    }

    @Override
    protected void map(NullWritable key, NullWritable value,
        Context context) throws IOException, InterruptedException {

      String suffix = "/" + shortTaskId;
      byte[] row = Bytes.add(new byte[8], Bytes.toBytes(suffix));

      int BLOCK_SIZE = (int)(recordsToWrite / 100);

      for (long i = 0; i < recordsToWrite;) {
        long blockStart = i;
        for (long idxInBlock = 0;
             idxInBlock < BLOCK_SIZE && i < recordsToWrite;
             idxInBlock++, i++) {

          long byteSwapped = swapLong(i);
          Bytes.putLong(row, 0, byteSwapped);

          Put p = new Put(row);
          p.add(TEST_FAMILY, TEST_QUALIFIER, HConstants.EMPTY_BYTE_ARRAY);
          if (blockStart > 0) {
            for (int j = 0; j < numBackReferencesPerRow; j++) {
              long referredRow = blockStart - BLOCK_SIZE + rand.nextInt(BLOCK_SIZE);
              Bytes.putLong(row, 0, swapLong(referredRow));
              p.add(TEST_FAMILY, row, HConstants.EMPTY_BYTE_ARRAY);
            }
            refsWritten.increment(1);
          }
          rowsWritten.increment(1);
          mutator.mutate(p);

          if (i % 100 == 0) {
            context.setStatus("Written " + i + "/" + recordsToWrite + " records");
            context.progress();
          }
        }
        // End of block, flush all of them before we start writing anything
        // pointing to these!
        mutator.flush();
      }
    }
  }

  public static class VerifyMapper extends TableMapper<BytesWritable, BytesWritable> {
    static final BytesWritable EMPTY = new BytesWritable(HConstants.EMPTY_BYTE_ARRAY);


    @Override
    protected void map(ImmutableBytesWritable key, Result value, Context context)
        throws IOException, InterruptedException {
      BytesWritable bwKey = new BytesWritable(key.get());
      BytesWritable bwVal = new BytesWritable();
      for (Cell kv : value.listCells()) {
        if (Bytes.compareTo(TEST_QUALIFIER, 0, TEST_QUALIFIER.length,
                            kv.getQualifierArray(), kv.getQualifierOffset(), kv.getQualifierLength()) == 0) {
          context.write(bwKey, EMPTY);
        } else {
          bwVal.set(kv.getQualifierArray(), kv.getQualifierOffset(), kv.getQualifierLength());
          context.write(bwVal, bwKey);
        }
      }
    }
  }

  public static class VerifyReducer extends Reducer<BytesWritable, BytesWritable, Text, Text> {
    private static final Log LOG = LogFactory.getLog(VerifyReducer.class);
    private Counter refsChecked;
    private Counter rowsWritten;

    @Override
    public void setup(Context context) throws IOException {
      refsChecked = context.getCounter(Counters.REFERENCES_CHECKED);
      rowsWritten = context.getCounter(Counters.ROWS_WRITTEN);
    }

    @Override
    protected void reduce(BytesWritable referredRow, Iterable<BytesWritable> referrers,
        VerifyReducer.Context ctx) throws IOException, InterruptedException {
      boolean gotOriginalRow = false;
      int refCount = 0;

      for (BytesWritable ref : referrers) {
        if (ref.getLength() == 0) {
          assert !gotOriginalRow;
          gotOriginalRow = true;
        } else {
          refCount++;
        }
      }
      refsChecked.increment(refCount);

      if (!gotOriginalRow) {
        String parsedRow = makeRowReadable(referredRow.getBytes(), referredRow.getLength());
        String binRow = Bytes.toStringBinary(referredRow.getBytes(), 0, referredRow.getLength());
        LOG.error("Reference error row " + parsedRow);
        ctx.write(new Text(binRow), new Text(parsedRow));
        rowsWritten.increment(1);
      }
    }

    private String makeRowReadable(byte[] bytes, int length) {
      long rowIdx = swapLong(Bytes.toLong(bytes, 0));
      String suffix = Bytes.toString(bytes, 8, length - 8);

      return "Row #" + rowIdx + " suffix " + suffix;
    }
  }

  protected Job doLoad(Configuration conf, HTableDescriptor htd) throws Exception {
    Path outputDir = getTestDir(TEST_NAME, "load-output");

    NMapInputFormat.setNumMapTasks(conf, conf.getInt(NUM_MAP_TASKS_KEY, NUM_MAP_TASKS_DEFAULT));
    conf.set(TABLE_NAME_KEY, htd.getTableName().getNameAsString());

    Job job = Job.getInstance(conf);
    job.setJobName(TEST_NAME + " Load for " + htd.getTableName());
    job.setJarByClass(this.getClass());
    setMapperClass(job);
    job.setInputFormatClass(NMapInputFormat.class);
    job.setNumReduceTasks(0);
    setJobScannerConf(job);
    FileOutputFormat.setOutputPath(job, outputDir);

    TableMapReduceUtil.addDependencyJars(job);

    TableMapReduceUtil.addDependencyJars(job.getConfiguration(), AbstractHBaseTool.class);
    TableMapReduceUtil.initCredentials(job);
    assertTrue(job.waitForCompletion(true));
    return job;
  }

  protected void setMapperClass(Job job) {
    job.setMapperClass(LoadMapper.class);
  }

  protected void doVerify(Configuration conf, HTableDescriptor htd) throws Exception {
    Path outputDir = getTestDir(TEST_NAME, "verify-output");

    Job job = Job.getInstance(conf);
    job.setJarByClass(this.getClass());
    job.setJobName(TEST_NAME + " Verification for " + htd.getTableName());
    setJobScannerConf(job);

    Scan scan = new Scan();

    TableMapReduceUtil.initTableMapperJob(
        htd.getTableName().getNameAsString(), scan, VerifyMapper.class,
        BytesWritable.class, BytesWritable.class, job);
    TableMapReduceUtil.addDependencyJars(job.getConfiguration(), AbstractHBaseTool.class);
    int scannerCaching = conf.getInt("verify.scannercaching", SCANNER_CACHING);
    TableMapReduceUtil.setScannerCaching(job, scannerCaching);

    job.setReducerClass(VerifyReducer.class);
    job.setNumReduceTasks(conf.getInt(NUM_REDUCE_TASKS_KEY, NUM_REDUCE_TASKS_DEFAULT));
    FileOutputFormat.setOutputPath(job, outputDir);
    assertTrue(job.waitForCompletion(true));

    long numOutputRecords = job.getCounters().findCounter(Counters.ROWS_WRITTEN).getValue();
    assertEquals(0, numOutputRecords);
  }

  private static void setJobScannerConf(Job job) {
    // Make sure scanners log something useful to make debugging possible.
    job.getConfiguration().setBoolean(ScannerCallable.LOG_SCANNER_ACTIVITY, true);
    long lpr = job.getConfiguration().getLong(NUM_TO_WRITE_KEY, NUM_TO_WRITE_DEFAULT) / 100;
    job.getConfiguration().setInt(TableRecordReaderImpl.LOG_PER_ROW_COUNT, (int)lpr);
  }

  public Path getTestDir(String testName, String subdir) throws IOException {
    //HBaseTestingUtility.getDataTestDirOnTestFs() has not been backported.
    FileSystem fs = FileSystem.get(getConf());
    Path base = new Path(fs.getWorkingDirectory(), "test-data");
    String randomStr = UUID.randomUUID().toString();
    Path testDir = new Path(base, randomStr);
    fs.deleteOnExit(testDir);

    return new Path(new Path(testDir, testName), subdir);
  }

  @Test
  public void testLoadAndVerify() throws Exception {
    HTableDescriptor htd = new HTableDescriptor(TableName.valueOf(TEST_NAME));
    htd.addFamily(new HColumnDescriptor(TEST_FAMILY));

    Admin admin = getTestingUtil(getConf()).getHBaseAdmin();
    admin.createTable(htd, Bytes.toBytes(0L), Bytes.toBytes(-1L), 40);

    doLoad(getConf(), htd);
    doVerify(getConf(), htd);

    // Only disable and drop if we succeeded to verify - otherwise it's useful
    // to leave it around for post-mortem
    getTestingUtil(getConf()).deleteTable(htd.getTableName());
  }

  @Override
  public void printUsage() {
    printUsage(this.getClass().getSimpleName() + " <options>"
        + " [-Doptions] <load|verify|loadAndVerify>", "Options", "");
    System.err.println("");
    System.err.println("  Loads a table with row dependencies and verifies the dependency chains");
    System.err.println("  -Dloadmapper.table=<name>        Table to write/verify (default autogen)");
    System.err.println("  -Dloadmapper.backrefs=<n>        Number of backreferences per row (default 50)");
    System.err.println("  -Dloadmapper.num_to_write=<n>    Number of rows per mapper (default 100,000 per mapper)");
    System.err.println("  -Dloadmapper.deleteAfter=<bool>  Delete after a successful verify (default true)");
    System.err.println("  -Dloadmapper.numPresplits=<n>    Number of presplit regions to start with (default 40)");
    System.err.println("  -Dloadmapper.map.tasks=<n>       Number of map tasks for load (default 200)");
    System.err.println("  -Dverify.reduce.tasks=<n>        Number of reduce tasks for verify (default 35)");
    System.err.println("  -Dverify.scannercaching=<n>      Number hbase scanner caching rows to read (default 50)");
  }


  @Override
  protected void processOptions(CommandLine cmd) {
    super.processOptions(cmd);

    String[] args = cmd.getArgs();
    if (args == null || args.length < 1 || args.length > 1) {
      printUsage();
      throw new RuntimeException("Incorrect Number of args.");
    }
    toRun = args[0];
  }

  @Override
  public int runTestFromCommandLine() throws Exception {
    IntegrationTestingUtility.setUseDistributedCluster(getConf());
    boolean doLoad = false;
    boolean doVerify = false;
    boolean doDelete = getConf().getBoolean("loadmapper.deleteAfter",true);
    int numPresplits = getConf().getInt("loadmapper.numPresplits", 40);

    if (toRun.equals("load")) {
      doLoad = true;
    } else if (toRun.equals("verify")) {
      doVerify= true;
    } else if (toRun.equals("loadAndVerify")) {
      doLoad=true;
      doVerify= true;
    } else {
      System.err.println("Invalid argument " + toRun);
      printUsage();
      return 1;
    }

    // create HTableDescriptor for specified table
    TableName table = getTablename();
    HTableDescriptor htd = new HTableDescriptor(table);
    htd.addFamily(new HColumnDescriptor(TEST_FAMILY));

    try (Connection conn = ConnectionFactory.createConnection(getConf());
        Admin admin = conn.getAdmin()) {
      if (doLoad) {
        admin.createTable(htd, Bytes.toBytes(0L), Bytes.toBytes(-1L), numPresplits);
        doLoad(getConf(), htd);
      }
    }
    if (doVerify) {
      doVerify(getConf(), htd);
      if (doDelete) {
        getTestingUtil(getConf()).deleteTable(htd.getTableName());
      }
    }
    return 0;
  }

  @Override
  public TableName getTablename() {
    return TableName.valueOf(getConf().get(TABLE_NAME_KEY, TEST_NAME));
  }

  @Override
  protected Set<String> getColumnFamilies() {
    return Sets.newHashSet(Bytes.toString(TEST_FAMILY));
  }

  public static void main(String argv[]) throws Exception {
    Configuration conf = HBaseConfiguration.create();
    IntegrationTestingUtility.setUseDistributedCluster(conf);
    int ret = ToolRunner.run(conf, new IntegrationTestLoadAndVerify(), argv);
    System.exit(ret);
  }
}
