/**
 *
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
package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HBaseTestCase;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.HFileLink;
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding;
import org.apache.hadoop.hbase.io.hfile.BlockCache;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.CacheStats;
import org.apache.hadoop.hbase.io.hfile.HFileContext;
import org.apache.hadoop.hbase.io.hfile.HFileContextBuilder;
import org.apache.hadoop.hbase.io.hfile.HFileDataBlockEncoder;
import org.apache.hadoop.hbase.io.hfile.HFileDataBlockEncoderImpl;
import org.apache.hadoop.hbase.io.hfile.HFileScanner;
import org.apache.hadoop.hbase.util.BloomFilterFactory;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.ChecksumType;
import org.apache.hadoop.hbase.util.FSUtils;
import org.junit.experimental.categories.Category;
import org.mockito.Mockito;

import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

/**
 * Test HStoreFile
 */
@Category(SmallTests.class)
public class TestStoreFile extends HBaseTestCase {
  static final Log LOG = LogFactory.getLog(TestStoreFile.class);
  private static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private CacheConfig cacheConf =  new CacheConfig(TEST_UTIL.getConfiguration());
  private static String ROOT_DIR = TEST_UTIL.getDataTestDir("TestStoreFile").toString();
  private static final ChecksumType CKTYPE = ChecksumType.CRC32;
  private static final int CKBYTES = 512;
  private static String TEST_FAMILY = "cf";

  @Override
  public void setUp() throws Exception {
    super.setUp();
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();
  }

  /**
   * Write a file and then assert that we can read from top and bottom halves
   * using two HalfMapFiles.
   * @throws Exception
   */
  public void testBasicHalfMapFile() throws Exception {
    final HRegionInfo hri =
        new HRegionInfo(TableName.valueOf("testBasicHalfMapFileTb"));
    HRegionFileSystem regionFs = HRegionFileSystem.createRegionOnFileSystem(
      conf, fs, new Path(this.testDir, hri.getTable().getNameAsString()), hri);

    HFileContext meta = new HFileContextBuilder().withBlockSize(2*1024).build();
    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf, this.fs)
            .withFilePath(regionFs.createTempName())
            .withFileContext(meta)
            .build();
    writeStoreFile(writer);

    Path sfPath = regionFs.commitStoreFile(TEST_FAMILY, writer.getPath());
    StoreFile sf = new StoreFile(this.fs, sfPath, conf, cacheConf,
      BloomType.NONE);
    checkHalfHFile(regionFs, sf);
  }

  private void writeStoreFile(final StoreFile.Writer writer) throws IOException {
    writeStoreFile(writer, Bytes.toBytes(getName()), Bytes.toBytes(getName()));
  }

  // pick an split point (roughly halfway)
  byte[] SPLITKEY = new byte[] { (LAST_CHAR + FIRST_CHAR)/2, FIRST_CHAR};

  /*
   * Writes HStoreKey and ImmutableBytes data to passed writer and
   * then closes it.
   * @param writer
   * @throws IOException
   */
  public static void writeStoreFile(final StoreFile.Writer writer, byte[] fam, byte[] qualifier)
  throws IOException {
    long now = System.currentTimeMillis();
    try {
      for (char d = FIRST_CHAR; d <= LAST_CHAR; d++) {
        for (char e = FIRST_CHAR; e <= LAST_CHAR; e++) {
          byte[] b = new byte[] { (byte) d, (byte) e };
          writer.append(new KeyValue(b, fam, qualifier, now, b));
        }
      }
    } finally {
      writer.close();
    }
  }

  /**
   * Test that our mechanism of writing store files in one region to reference
   * store files in other regions works.
   * @throws IOException
   */
  public void testReference() throws IOException {
    final HRegionInfo hri = new HRegionInfo(TableName.valueOf("testReferenceTb"));
    HRegionFileSystem regionFs = HRegionFileSystem.createRegionOnFileSystem(
      conf, fs, new Path(this.testDir, hri.getTable().getNameAsString()), hri);

    HFileContext meta = new HFileContextBuilder().withBlockSize(8 * 1024).build();
    // Make a store file and write data to it.
    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf, this.fs)
            .withFilePath(regionFs.createTempName())
            .withFileContext(meta)
            .build();
    writeStoreFile(writer);

    Path hsfPath = regionFs.commitStoreFile(TEST_FAMILY, writer.getPath());
    StoreFile hsf = new StoreFile(this.fs, hsfPath, conf, cacheConf,
      BloomType.NONE);
    StoreFile.Reader reader = hsf.createReader();
    // Split on a row, not in middle of row.  Midkey returned by reader
    // may be in middle of row.  Create new one with empty column and
    // timestamp.

    KeyValue kv = KeyValue.createKeyValueFromKey(reader.midkey());
    byte [] midRow = kv.getRow();
    kv = KeyValue.createKeyValueFromKey(reader.getLastKey());
    byte [] finalRow = kv.getRow();
    hsf.closeReader(true);

    // Make a reference
    HRegionInfo splitHri = new HRegionInfo(hri.getTable(), null, midRow);
    Path refPath = splitStoreFile(regionFs, splitHri, TEST_FAMILY, hsf, midRow, true);
    StoreFile refHsf = new StoreFile(this.fs, refPath, conf, cacheConf,
      BloomType.NONE);
    // Now confirm that I can read from the reference and that it only gets
    // keys from top half of the file.
    HFileScanner s = refHsf.createReader().getScanner(false, false);
    for(boolean first = true; (!s.isSeeked() && s.seekTo()) || s.next();) {
      ByteBuffer bb = s.getKey();
      kv = KeyValue.createKeyValueFromKey(bb);
      if (first) {
        assertTrue(Bytes.equals(kv.getRow(), midRow));
        first = false;
      }
    }
    assertTrue(Bytes.equals(kv.getRow(), finalRow));
  }

  public void testHFileLink() throws IOException {
    final HRegionInfo hri = new HRegionInfo(TableName.valueOf("testHFileLinkTb"));
    // force temp data in hbase/target/test-data instead of /tmp/hbase-xxxx/
    Configuration testConf = new Configuration(this.conf);
    FSUtils.setRootDir(testConf, this.testDir);
    HRegionFileSystem regionFs = HRegionFileSystem.createRegionOnFileSystem(
      testConf, fs, FSUtils.getTableDir(this.testDir, hri.getTable()), hri);
    HFileContext meta = new HFileContextBuilder().withBlockSize(8 * 1024).build();

    // Make a store file and write data to it.
    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf, this.fs)
            .withFilePath(regionFs.createTempName())
            .withFileContext(meta)
            .build();
    writeStoreFile(writer);

    Path storeFilePath = regionFs.commitStoreFile(TEST_FAMILY, writer.getPath());
    Path dstPath = new Path(regionFs.getTableDir(), new Path("test-region", TEST_FAMILY));
    HFileLink.create(testConf, this.fs, dstPath, hri, storeFilePath.getName());
    Path linkFilePath = new Path(dstPath,
                  HFileLink.createHFileLinkName(hri, storeFilePath.getName()));

    // Try to open store file from link
    StoreFileInfo storeFileInfo = new StoreFileInfo(testConf, this.fs, linkFilePath);
    StoreFile hsf = new StoreFile(this.fs, storeFileInfo, testConf, cacheConf,
      BloomType.NONE);
    assertTrue(storeFileInfo.isLink());

    // Now confirm that I can read from the link
    int count = 1;
    HFileScanner s = hsf.createReader().getScanner(false, false);
    s.seekTo();
    while (s.next()) {
      count++;
    }
    assertEquals((LAST_CHAR - FIRST_CHAR + 1) * (LAST_CHAR - FIRST_CHAR + 1), count);
  }

  /**
   * This test creates an hfile and then the dir structures and files to verify that references
   * to hfilelinks (created by snapshot clones) can be properly interpreted.
   */
  public void testReferenceToHFileLink() throws IOException {
    // force temp data in hbase/target/test-data instead of /tmp/hbase-xxxx/
    Configuration testConf = new Configuration(this.conf);
    FSUtils.setRootDir(testConf, this.testDir);

    // adding legal table name chars to verify regex handles it.
    HRegionInfo hri = new HRegionInfo(TableName.valueOf("_original-evil-name"));
    HRegionFileSystem regionFs = HRegionFileSystem.createRegionOnFileSystem(
      testConf, fs, FSUtils.getTableDir(this.testDir, hri.getTable()), hri);

    HFileContext meta = new HFileContextBuilder().withBlockSize(8 * 1024).build();
    // Make a store file and write data to it. <root>/<tablename>/<rgn>/<cf>/<file>
    StoreFile.Writer writer = new StoreFile.WriterBuilder(testConf, cacheConf, this.fs)
            .withFilePath(regionFs.createTempName())
            .withFileContext(meta)
            .build();
    writeStoreFile(writer);
    Path storeFilePath = regionFs.commitStoreFile(TEST_FAMILY, writer.getPath());

    // create link to store file. <root>/clone/region/<cf>/<hfile>-<region>-<table>
    HRegionInfo hriClone = new HRegionInfo(TableName.valueOf("clone"));
    HRegionFileSystem cloneRegionFs = HRegionFileSystem.createRegionOnFileSystem(
      testConf, fs, FSUtils.getTableDir(this.testDir, hri.getTable()),
        hriClone);
    Path dstPath = cloneRegionFs.getStoreDir(TEST_FAMILY);
    HFileLink.create(testConf, this.fs, dstPath, hri, storeFilePath.getName());
    Path linkFilePath = new Path(dstPath,
                  HFileLink.createHFileLinkName(hri, storeFilePath.getName()));

    // create splits of the link.
    // <root>/clone/splitA/<cf>/<reftohfilelink>,
    // <root>/clone/splitB/<cf>/<reftohfilelink>
    HRegionInfo splitHriA = new HRegionInfo(hri.getTable(), null, SPLITKEY);
    HRegionInfo splitHriB = new HRegionInfo(hri.getTable(), SPLITKEY, null);
    StoreFile f = new StoreFile(fs, linkFilePath, testConf, cacheConf, BloomType.NONE);
    f.createReader();
    Path pathA = splitStoreFile(cloneRegionFs, splitHriA, TEST_FAMILY, f, SPLITKEY, true); // top
    Path pathB = splitStoreFile(cloneRegionFs, splitHriB, TEST_FAMILY, f, SPLITKEY, false);// bottom
    f.closeReader(true);
    // OK test the thing
    FSUtils.logFileSystemState(fs, this.testDir, LOG);

    // There is a case where a file with the hfilelink pattern is actually a daughter
    // reference to a hfile link.  This code in StoreFile that handles this case.

    // Try to open store file from link
    StoreFile hsfA = new StoreFile(this.fs, pathA, testConf, cacheConf,
      BloomType.NONE);

    // Now confirm that I can read from the ref to link
    int count = 1;
    HFileScanner s = hsfA.createReader().getScanner(false, false);
    s.seekTo();
    while (s.next()) {
      count++;
    }
    assertTrue(count > 0); // read some rows here

    // Try to open store file from link
    StoreFile hsfB = new StoreFile(this.fs, pathB, testConf, cacheConf,
      BloomType.NONE);

    // Now confirm that I can read from the ref to link
    HFileScanner sB = hsfB.createReader().getScanner(false, false);
    sB.seekTo();
    
    //count++ as seekTo() will advance the scanner
    count++;
    while (sB.next()) {
      count++;
    }

    // read the rest of the rows
    assertEquals((LAST_CHAR - FIRST_CHAR + 1) * (LAST_CHAR - FIRST_CHAR + 1), count);
  }

  private void checkHalfHFile(final HRegionFileSystem regionFs, final StoreFile f)
      throws IOException {
    byte [] midkey = f.createReader().midkey();
    KeyValue midKV = KeyValue.createKeyValueFromKey(midkey);
    byte [] midRow = midKV.getRow();
    // Create top split.
    HRegionInfo topHri = new HRegionInfo(regionFs.getRegionInfo().getTable(),
        null, midRow);
    Path topPath = splitStoreFile(regionFs, topHri, TEST_FAMILY, f, midRow, true);
    // Create bottom split.
    HRegionInfo bottomHri = new HRegionInfo(regionFs.getRegionInfo().getTable(),
        midRow, null);
    Path bottomPath = splitStoreFile(regionFs, bottomHri, TEST_FAMILY, f, midRow, false);
    // Make readers on top and bottom.
    StoreFile.Reader top = new StoreFile(
      this.fs, topPath, conf, cacheConf, BloomType.NONE).createReader();
    StoreFile.Reader bottom = new StoreFile(
      this.fs, bottomPath, conf, cacheConf, BloomType.NONE).createReader();
    ByteBuffer previous = null;
    LOG.info("Midkey: " + midKV.toString());
    ByteBuffer bbMidkeyBytes = ByteBuffer.wrap(midkey);
    try {
      // Now make two HalfMapFiles and assert they can read the full backing
      // file, one from the top and the other from the bottom.
      // Test bottom half first.
      // Now test reading from the top.
      boolean first = true;
      ByteBuffer key = null;
      HFileScanner topScanner = top.getScanner(false, false);
      while ((!topScanner.isSeeked() && topScanner.seekTo()) ||
             (topScanner.isSeeked() && topScanner.next())) {
        key = topScanner.getKey();

        if (topScanner.getReader().getComparator().compareFlatKey(key.array(),
          key.arrayOffset(), key.limit(), midkey, 0, midkey.length) < 0) {
          fail("key=" + Bytes.toStringBinary(key) + " < midkey=" +
              Bytes.toStringBinary(midkey));
        }
        if (first) {
          first = false;
          LOG.info("First in top: " + Bytes.toString(Bytes.toBytes(key)));
        }
      }
      LOG.info("Last in top: " + Bytes.toString(Bytes.toBytes(key)));

      first = true;
      HFileScanner bottomScanner = bottom.getScanner(false, false);
      while ((!bottomScanner.isSeeked() && bottomScanner.seekTo()) ||
          bottomScanner.next()) {
        previous = bottomScanner.getKey();
        key = bottomScanner.getKey();
        if (first) {
          first = false;
          LOG.info("First in bottom: " +
            Bytes.toString(Bytes.toBytes(previous)));
        }
        assertTrue(key.compareTo(bbMidkeyBytes) < 0);
      }
      if (previous != null) {
        LOG.info("Last in bottom: " + Bytes.toString(Bytes.toBytes(previous)));
      }
      // Remove references.
      regionFs.cleanupDaughterRegion(topHri);
      regionFs.cleanupDaughterRegion(bottomHri);

      // Next test using a midkey that does not exist in the file.
      // First, do a key that is < than first key. Ensure splits behave
      // properly.
      byte [] badmidkey = Bytes.toBytes("  .");
      assertTrue(fs.exists(f.getPath()));
      topPath = splitStoreFile(regionFs, topHri, TEST_FAMILY, f, badmidkey, true);
      bottomPath = splitStoreFile(regionFs, bottomHri, TEST_FAMILY, f, badmidkey, false);
      
      assertNull(bottomPath);
      
      top = new StoreFile(this.fs, topPath, conf, cacheConf, BloomType.NONE).createReader();
      // Now read from the top.
      first = true;
      topScanner = top.getScanner(false, false);
      while ((!topScanner.isSeeked() && topScanner.seekTo()) ||
          topScanner.next()) {
        key = topScanner.getKey();
        assertTrue(topScanner.getReader().getComparator().compareFlatKey(key.array(),
          key.arrayOffset(), key.limit(), badmidkey, 0, badmidkey.length) >= 0);
        if (first) {
          first = false;
          KeyValue keyKV = KeyValue.createKeyValueFromKey(key);
          LOG.info("First top when key < bottom: " + keyKV);
          String tmp = Bytes.toString(keyKV.getRow());
          for (int i = 0; i < tmp.length(); i++) {
            assertTrue(tmp.charAt(i) == 'a');
          }
        }
      }
      KeyValue keyKV = KeyValue.createKeyValueFromKey(key);
      LOG.info("Last top when key < bottom: " + keyKV);
      String tmp = Bytes.toString(keyKV.getRow());
      for (int i = 0; i < tmp.length(); i++) {
        assertTrue(tmp.charAt(i) == 'z');
      }
      // Remove references.
      regionFs.cleanupDaughterRegion(topHri);
      regionFs.cleanupDaughterRegion(bottomHri);

      // Test when badkey is > than last key in file ('||' > 'zz').
      badmidkey = Bytes.toBytes("|||");
      topPath = splitStoreFile(regionFs,topHri, TEST_FAMILY, f, badmidkey, true);
      bottomPath = splitStoreFile(regionFs, bottomHri, TEST_FAMILY, f, badmidkey, false);
      assertNull(topPath);
      bottom = new StoreFile(this.fs, bottomPath, conf, cacheConf,
        BloomType.NONE).createReader();
      first = true;
      bottomScanner = bottom.getScanner(false, false);
      while ((!bottomScanner.isSeeked() && bottomScanner.seekTo()) ||
          bottomScanner.next()) {
        key = bottomScanner.getKey();
        if (first) {
          first = false;
          keyKV = KeyValue.createKeyValueFromKey(key);
          LOG.info("First bottom when key > top: " + keyKV);
          tmp = Bytes.toString(keyKV.getRow());
          for (int i = 0; i < tmp.length(); i++) {
            assertTrue(tmp.charAt(i) == 'a');
          }
        }
      }
      keyKV = KeyValue.createKeyValueFromKey(key);
      LOG.info("Last bottom when key > top: " + keyKV);
      for (int i = 0; i < tmp.length(); i++) {
        assertTrue(Bytes.toString(keyKV.getRow()).charAt(i) == 'z');
      }
    } finally {
      if (top != null) {
        top.close(true); // evict since we are about to delete the file
      }
      if (bottom != null) {
        bottom.close(true); // evict since we are about to delete the file
      }
      fs.delete(f.getPath(), true);
    }
  }

  private static final String localFormatter = "%010d";

  private void bloomWriteRead(StoreFile.Writer writer, FileSystem fs) throws Exception {
    float err = conf.getFloat(BloomFilterFactory.IO_STOREFILE_BLOOM_ERROR_RATE, 0);
    Path f = writer.getPath();
    long now = System.currentTimeMillis();
    for (int i = 0; i < 2000; i += 2) {
      String row = String.format(localFormatter, i);
      KeyValue kv = new KeyValue(row.getBytes(), "family".getBytes(),
        "col".getBytes(), now, "value".getBytes());
      writer.append(kv);
    }
    writer.close();

    StoreFile.Reader reader = new StoreFile.Reader(fs, f, cacheConf, conf);
    reader.loadFileInfo();
    reader.loadBloomfilter();
    StoreFileScanner scanner = reader.getStoreFileScanner(false, false);

    // check false positives rate
    int falsePos = 0;
    int falseNeg = 0;
    for (int i = 0; i < 2000; i++) {
      String row = String.format(localFormatter, i);
      TreeSet<byte[]> columns = new TreeSet<byte[]>(Bytes.BYTES_COMPARATOR);
      columns.add("family:col".getBytes());

      Scan scan = new Scan(row.getBytes(),row.getBytes());
      scan.addColumn("family".getBytes(), "family:col".getBytes());
      boolean exists = scanner.shouldUseScanner(scan, columns, Long.MIN_VALUE);
      if (i % 2 == 0) {
        if (!exists) falseNeg++;
      } else {
        if (exists) falsePos++;
      }
    }
    reader.close(true); // evict because we are about to delete the file
    fs.delete(f, true);
    assertEquals("False negatives: " + falseNeg, 0, falseNeg);
    int maxFalsePos = (int) (2 * 2000 * err);
    assertTrue("Too many false positives: " + falsePos + " (err=" + err
        + ", expected no more than " + maxFalsePos + ")",
        falsePos <= maxFalsePos);
  }
  
  private static final int BLOCKSIZE_SMALL = 8192;

  public void testBloomFilter() throws Exception {
    FileSystem fs = FileSystem.getLocal(conf);
    conf.setFloat(BloomFilterFactory.IO_STOREFILE_BLOOM_ERROR_RATE, (float) 0.01);
    conf.setBoolean(BloomFilterFactory.IO_STOREFILE_BLOOM_ENABLED, true);

    // write the file
    Path f = new Path(ROOT_DIR, getName());
    HFileContext meta = new HFileContextBuilder().withBlockSize(BLOCKSIZE_SMALL)
                        .withChecksumType(CKTYPE)
                        .withBytesPerCheckSum(CKBYTES).build();
    // Make a store file and write data to it.
    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf, this.fs)
            .withFilePath(f)
            .withBloomType(BloomType.ROW)
            .withMaxKeyCount(2000)
            .withFileContext(meta)
            .build();
    bloomWriteRead(writer, fs);
  }

  public void testDeleteFamilyBloomFilter() throws Exception {
    FileSystem fs = FileSystem.getLocal(conf);
    conf.setFloat(BloomFilterFactory.IO_STOREFILE_BLOOM_ERROR_RATE, (float) 0.01);
    conf.setBoolean(BloomFilterFactory.IO_STOREFILE_BLOOM_ENABLED, true);
    float err = conf.getFloat(BloomFilterFactory.IO_STOREFILE_BLOOM_ERROR_RATE, 0);

    // write the file
    Path f = new Path(ROOT_DIR, getName());

    HFileContext meta = new HFileContextBuilder()
                        .withBlockSize(BLOCKSIZE_SMALL)
                        .withChecksumType(CKTYPE)
                        .withBytesPerCheckSum(CKBYTES).build();
    // Make a store file and write data to it.
    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf, this.fs)
            .withFilePath(f)
            .withMaxKeyCount(2000)
            .withFileContext(meta)
            .build();

    // add delete family
    long now = System.currentTimeMillis();
    for (int i = 0; i < 2000; i += 2) {
      String row = String.format(localFormatter, i);
      KeyValue kv = new KeyValue(row.getBytes(), "family".getBytes(),
          "col".getBytes(), now, KeyValue.Type.DeleteFamily, "value".getBytes());
      writer.append(kv);
    }
    writer.close();

    StoreFile.Reader reader = new StoreFile.Reader(fs, f, cacheConf, conf);
    reader.loadFileInfo();
    reader.loadBloomfilter();

    // check false positives rate
    int falsePos = 0;
    int falseNeg = 0;
    for (int i = 0; i < 2000; i++) {
      String row = String.format(localFormatter, i);
      byte[] rowKey = Bytes.toBytes(row);
      boolean exists = reader.passesDeleteFamilyBloomFilter(rowKey, 0,
          rowKey.length);
      if (i % 2 == 0) {
        if (!exists)
          falseNeg++;
      } else {
        if (exists)
          falsePos++;
      }
    }
    assertEquals(1000, reader.getDeleteFamilyCnt());
    reader.close(true); // evict because we are about to delete the file
    fs.delete(f, true);
    assertEquals("False negatives: " + falseNeg, 0, falseNeg);
    int maxFalsePos = (int) (2 * 2000 * err);
    assertTrue("Too many false positives: " + falsePos + " (err=" + err
        + ", expected no more than " + maxFalsePos, falsePos <= maxFalsePos);
  }

  /**
   * Test for HBASE-8012
   */
  public void testReseek() throws Exception {
    // write the file
    Path f = new Path(ROOT_DIR, getName());
    HFileContext meta = new HFileContextBuilder().withBlockSize(8 * 1024).build();
    // Make a store file and write data to it.
    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf, this.fs)
            .withFilePath(f)
            .withFileContext(meta)
            .build();

    writeStoreFile(writer);
    writer.close();

    StoreFile.Reader reader = new StoreFile.Reader(fs, f, cacheConf, conf);

    // Now do reseek with empty KV to position to the beginning of the file

    KeyValue k = KeyValueUtil.createFirstOnRow(HConstants.EMPTY_BYTE_ARRAY);
    StoreFileScanner s = reader.getStoreFileScanner(false, false);
    s.reseek(k);

    assertNotNull("Intial reseek should position at the beginning of the file", s.peek());
  }

  public void testBloomTypes() throws Exception {
    float err = (float) 0.01;
    FileSystem fs = FileSystem.getLocal(conf);
    conf.setFloat(BloomFilterFactory.IO_STOREFILE_BLOOM_ERROR_RATE, err);
    conf.setBoolean(BloomFilterFactory.IO_STOREFILE_BLOOM_ENABLED, true);

    int rowCount = 50;
    int colCount = 10;
    int versions = 2;

    // run once using columns and once using rows
    BloomType[] bt = {BloomType.ROWCOL, BloomType.ROW};
    int[] expKeys  = {rowCount*colCount, rowCount};
    // below line deserves commentary.  it is expected bloom false positives
    //  column = rowCount*2*colCount inserts
    //  row-level = only rowCount*2 inserts, but failures will be magnified by
    //              2nd for loop for every column (2*colCount)
    float[] expErr   = {2*rowCount*colCount*err, 2*rowCount*2*colCount*err};

    for (int x : new int[]{0,1}) {
      // write the file
      Path f = new Path(ROOT_DIR, getName() + x);
      HFileContext meta = new HFileContextBuilder().withBlockSize(BLOCKSIZE_SMALL)
          .withChecksumType(CKTYPE)
          .withBytesPerCheckSum(CKBYTES).build();
      // Make a store file and write data to it.
      StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf, this.fs)
              .withFilePath(f)
              .withBloomType(bt[x])
              .withMaxKeyCount(expKeys[x])
              .withFileContext(meta)
              .build();

      long now = System.currentTimeMillis();
      for (int i = 0; i < rowCount*2; i += 2) { // rows
        for (int j = 0; j < colCount*2; j += 2) {   // column qualifiers
          String row = String.format(localFormatter, i);
          String col = String.format(localFormatter, j);
          for (int k= 0; k < versions; ++k) { // versions
            KeyValue kv = new KeyValue(row.getBytes(),
              "family".getBytes(), ("col" + col).getBytes(),
                now-k, Bytes.toBytes((long)-1));
            writer.append(kv);
          }
        }
      }
      writer.close();

      StoreFile.Reader reader = new StoreFile.Reader(fs, f, cacheConf, conf);
      reader.loadFileInfo();
      reader.loadBloomfilter();
      StoreFileScanner scanner = reader.getStoreFileScanner(false, false);
      assertEquals(expKeys[x], reader.generalBloomFilter.getKeyCount());

      // check false positives rate
      int falsePos = 0;
      int falseNeg = 0;
      for (int i = 0; i < rowCount*2; ++i) { // rows
        for (int j = 0; j < colCount*2; ++j) {   // column qualifiers
          String row = String.format(localFormatter, i);
          String col = String.format(localFormatter, j);
          TreeSet<byte[]> columns = new TreeSet<byte[]>(Bytes.BYTES_COMPARATOR);
          columns.add(("col" + col).getBytes());

          Scan scan = new Scan(row.getBytes(),row.getBytes());
          scan.addColumn("family".getBytes(), ("col"+col).getBytes());
          boolean exists =
              scanner.shouldUseScanner(scan, columns, Long.MIN_VALUE);
          boolean shouldRowExist = i % 2 == 0;
          boolean shouldColExist = j % 2 == 0;
          shouldColExist = shouldColExist || bt[x] == BloomType.ROW;
          if (shouldRowExist && shouldColExist) {
            if (!exists) falseNeg++;
          } else {
            if (exists) falsePos++;
          }
        }
      }
      reader.close(true); // evict because we are about to delete the file
      fs.delete(f, true);
      System.out.println(bt[x].toString());
      System.out.println("  False negatives: " + falseNeg);
      System.out.println("  False positives: " + falsePos);
      assertEquals(0, falseNeg);
      assertTrue(falsePos < 2*expErr[x]);
    }
  }

  public void testSeqIdComparator() {
    assertOrdering(StoreFile.Comparators.SEQ_ID,
        mockStoreFile(true,  100,   1000, -1, "/foo/123"),
        mockStoreFile(true,  100,   1000, -1, "/foo/124"),
        mockStoreFile(true,  99,    1000, -1, "/foo/126"),
        mockStoreFile(true,  98,    2000, -1, "/foo/126"),
        mockStoreFile(false, 3453, -1,     1, "/foo/1"),
        mockStoreFile(false, 2,    -1,     3, "/foo/2"),
        mockStoreFile(false, 1000, -1,     5, "/foo/2"),
        mockStoreFile(false, 76,   -1,     5, "/foo/3"));
  }

  /**
   * Assert that the given comparator orders the given storefiles in the
   * same way that they're passed.
   */
  private void assertOrdering(Comparator<StoreFile> comparator, StoreFile ... sfs) {
    ArrayList<StoreFile> sorted = Lists.newArrayList(sfs);
    Collections.shuffle(sorted);
    Collections.sort(sorted, comparator);
    LOG.debug("sfs: " + Joiner.on(",").join(sfs));
    LOG.debug("sorted: " + Joiner.on(",").join(sorted));
    assertTrue(Iterables.elementsEqual(Arrays.asList(sfs), sorted));
  }

  /**
   * Create a mock StoreFile with the given attributes.
   */
  private StoreFile mockStoreFile(boolean bulkLoad,
                                  long size,
                                  long bulkTimestamp,
                                  long seqId,
                                  String path) {
    StoreFile mock = Mockito.mock(StoreFile.class);
    StoreFile.Reader reader = Mockito.mock(StoreFile.Reader.class);

    Mockito.doReturn(size).when(reader).length();

    Mockito.doReturn(reader).when(mock).getReader();
    Mockito.doReturn(bulkLoad).when(mock).isBulkLoadResult();
    Mockito.doReturn(bulkTimestamp).when(mock).getBulkLoadTimestamp();
    Mockito.doReturn(seqId).when(mock).getMaxSequenceId();
    Mockito.doReturn(new Path(path)).when(mock).getPath();
    String name = "mock storefile, bulkLoad=" + bulkLoad +
      " bulkTimestamp=" + bulkTimestamp +
      " seqId=" + seqId +
      " path=" + path;
    Mockito.doReturn(name).when(mock).toString();
    return mock;
  }

  /**
   * Generate a list of KeyValues for testing based on given parameters
   * @param timestamps
   * @param numRows
   * @param qualifier
   * @param family
   * @return
   */
  List<KeyValue> getKeyValueSet(long[] timestamps, int numRows,
      byte[] qualifier, byte[] family) {
    List<KeyValue> kvList = new ArrayList<KeyValue>();
    for (int i=1;i<=numRows;i++) {
      byte[] b = Bytes.toBytes(i) ;
      LOG.info(Bytes.toString(b));
      LOG.info(Bytes.toString(b));
      for (long timestamp: timestamps)
      {
        kvList.add(new KeyValue(b, family, qualifier, timestamp, b));
      }
    }
    return kvList;
  }

  /**
   * Test to ensure correctness when using StoreFile with multiple timestamps
   * @throws IOException
   */
  public void testMultipleTimestamps() throws IOException {
    byte[] family = Bytes.toBytes("familyname");
    byte[] qualifier = Bytes.toBytes("qualifier");
    int numRows = 10;
    long[] timestamps = new long[] {20,10,5,1};
    Scan scan = new Scan();

    // Make up a directory hierarchy that has a regiondir ("7e0102") and familyname.
    Path storedir = new Path(new Path(this.testDir, "7e0102"), "familyname");
    Path dir = new Path(storedir, "1234567890");
    HFileContext meta = new HFileContextBuilder().withBlockSize(8 * 1024).build();
    // Make a store file and write data to it.
    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf, this.fs)
            .withOutputDir(dir)
            .withFileContext(meta)
            .build();

    List<KeyValue> kvList = getKeyValueSet(timestamps,numRows,
        family, qualifier);

    for (KeyValue kv : kvList) {
      writer.append(kv);
    }
    writer.appendMetadata(0, false);
    writer.close();

    StoreFile hsf = new StoreFile(this.fs, writer.getPath(), conf, cacheConf,
      BloomType.NONE);
    StoreFile.Reader reader = hsf.createReader();
    StoreFileScanner scanner = reader.getStoreFileScanner(false, false);
    TreeSet<byte[]> columns = new TreeSet<byte[]>(Bytes.BYTES_COMPARATOR);
    columns.add(qualifier);

    scan.setTimeRange(20, 100);
    assertTrue(scanner.shouldUseScanner(scan, columns, Long.MIN_VALUE));

    scan.setTimeRange(1, 2);
    assertTrue(scanner.shouldUseScanner(scan, columns, Long.MIN_VALUE));

    scan.setTimeRange(8, 10);
    assertTrue(scanner.shouldUseScanner(scan, columns, Long.MIN_VALUE));

    scan.setTimeRange(7, 50);
    assertTrue(scanner.shouldUseScanner(scan, columns, Long.MIN_VALUE));

    // This test relies on the timestamp range optimization
    scan.setTimeRange(27, 50);
    assertTrue(!scanner.shouldUseScanner(scan, columns, Long.MIN_VALUE));
  }

  public void testCacheOnWriteEvictOnClose() throws Exception {
    Configuration conf = this.conf;

    // Find a home for our files (regiondir ("7e0102") and familyname).
    Path baseDir = new Path(new Path(this.testDir, "7e0102"),"twoCOWEOC");

    // Grab the block cache and get the initial hit/miss counts
    BlockCache bc = new CacheConfig(conf).getBlockCache();
    assertNotNull(bc);
    CacheStats cs = bc.getStats();
    long startHit = cs.getHitCount();
    long startMiss = cs.getMissCount();
    long startEvicted = cs.getEvictedCount();

    // Let's write a StoreFile with three blocks, with cache on write off
    conf.setBoolean(CacheConfig.CACHE_BLOCKS_ON_WRITE_KEY, false);
    CacheConfig cacheConf = new CacheConfig(conf);
    Path pathCowOff = new Path(baseDir, "123456789");
    StoreFile.Writer writer = writeStoreFile(conf, cacheConf, pathCowOff, 3);
    StoreFile hsf = new StoreFile(this.fs, writer.getPath(), conf, cacheConf,
      BloomType.NONE);
    LOG.debug(hsf.getPath().toString());

    // Read this file, we should see 3 misses
    StoreFile.Reader reader = hsf.createReader();
    reader.loadFileInfo();
    StoreFileScanner scanner = reader.getStoreFileScanner(true, true);
    scanner.seek(KeyValue.LOWESTKEY);
    while (scanner.next() != null);
    assertEquals(startHit, cs.getHitCount());
    assertEquals(startMiss + 3, cs.getMissCount());
    assertEquals(startEvicted, cs.getEvictedCount());
    startMiss += 3;
    scanner.close();
    reader.close(cacheConf.shouldEvictOnClose());

    // Now write a StoreFile with three blocks, with cache on write on
    conf.setBoolean(CacheConfig.CACHE_BLOCKS_ON_WRITE_KEY, true);
    cacheConf = new CacheConfig(conf);
    Path pathCowOn = new Path(baseDir, "123456788");
    writer = writeStoreFile(conf, cacheConf, pathCowOn, 3);
    hsf = new StoreFile(this.fs, writer.getPath(), conf, cacheConf,
      BloomType.NONE);

    // Read this file, we should see 3 hits
    reader = hsf.createReader();
    scanner = reader.getStoreFileScanner(true, true);
    scanner.seek(KeyValue.LOWESTKEY);
    while (scanner.next() != null);
    assertEquals(startHit + 3, cs.getHitCount());
    assertEquals(startMiss, cs.getMissCount());
    assertEquals(startEvicted, cs.getEvictedCount());
    startHit += 3;
    scanner.close();
    reader.close(cacheConf.shouldEvictOnClose());

    // Let's read back the two files to ensure the blocks exactly match
    hsf = new StoreFile(this.fs, pathCowOff, conf, cacheConf,
      BloomType.NONE);
    StoreFile.Reader readerOne = hsf.createReader();
    readerOne.loadFileInfo();
    StoreFileScanner scannerOne = readerOne.getStoreFileScanner(true, true);
    scannerOne.seek(KeyValue.LOWESTKEY);
    hsf = new StoreFile(this.fs, pathCowOn, conf, cacheConf,
      BloomType.NONE);
    StoreFile.Reader readerTwo = hsf.createReader();
    readerTwo.loadFileInfo();
    StoreFileScanner scannerTwo = readerTwo.getStoreFileScanner(true, true);
    scannerTwo.seek(KeyValue.LOWESTKEY);
    Cell kv1 = null;
    Cell kv2 = null;
    while ((kv1 = scannerOne.next()) != null) {
      kv2 = scannerTwo.next();
      assertTrue(kv1.equals(kv2));
      KeyValue keyv1 = KeyValueUtil.ensureKeyValue(kv1);
      KeyValue keyv2 = KeyValueUtil.ensureKeyValue(kv2);
      assertTrue(Bytes.compareTo(
          keyv1.getBuffer(), keyv1.getKeyOffset(), keyv1.getKeyLength(), 
          keyv2.getBuffer(), keyv2.getKeyOffset(), keyv2.getKeyLength()) == 0);
      assertTrue(Bytes.compareTo(
          kv1.getValueArray(), kv1.getValueOffset(), kv1.getValueLength(),
          kv2.getValueArray(), kv2.getValueOffset(), kv2.getValueLength()) == 0);
    }
    assertNull(scannerTwo.next());
    assertEquals(startHit + 6, cs.getHitCount());
    assertEquals(startMiss, cs.getMissCount());
    assertEquals(startEvicted, cs.getEvictedCount());
    startHit += 6;
    scannerOne.close();
    readerOne.close(cacheConf.shouldEvictOnClose());
    scannerTwo.close();
    readerTwo.close(cacheConf.shouldEvictOnClose());

    // Let's close the first file with evict on close turned on
    conf.setBoolean("hbase.rs.evictblocksonclose", true);
    cacheConf = new CacheConfig(conf);
    hsf = new StoreFile(this.fs, pathCowOff, conf, cacheConf,
      BloomType.NONE);
    reader = hsf.createReader();
    reader.close(cacheConf.shouldEvictOnClose());

    // We should have 3 new evictions
    assertEquals(startHit, cs.getHitCount());
    assertEquals(startMiss, cs.getMissCount());
    assertEquals(startEvicted + 3, cs.getEvictedCount());
    startEvicted += 3;

    // Let's close the second file with evict on close turned off
    conf.setBoolean("hbase.rs.evictblocksonclose", false);
    cacheConf = new CacheConfig(conf);
    hsf = new StoreFile(this.fs, pathCowOn, conf, cacheConf,
      BloomType.NONE);
    reader = hsf.createReader();
    reader.close(cacheConf.shouldEvictOnClose());

    // We expect no changes
    assertEquals(startHit, cs.getHitCount());
    assertEquals(startMiss, cs.getMissCount());
    assertEquals(startEvicted, cs.getEvictedCount());
  }

  private Path splitStoreFile(final HRegionFileSystem regionFs, final HRegionInfo hri,
      final String family, final StoreFile sf, final byte[] splitKey, boolean isTopRef)
      throws IOException {
    FileSystem fs = regionFs.getFileSystem();
    Path path = regionFs.splitStoreFile(hri, family, sf, splitKey, isTopRef, null);
    if (null == path) {
      return null;
    }
    Path regionDir = regionFs.commitDaughterRegion(hri);
    return new Path(new Path(regionDir, family), path.getName());
  }

  private StoreFile.Writer writeStoreFile(Configuration conf,
      CacheConfig cacheConf, Path path, int numBlocks)
  throws IOException {
    // Let's put ~5 small KVs in each block, so let's make 5*numBlocks KVs
    int numKVs = 5 * numBlocks;
    List<KeyValue> kvs = new ArrayList<KeyValue>(numKVs);
    byte [] b = Bytes.toBytes("x");
    int totalSize = 0;
    for (int i=numKVs;i>0;i--) {
      KeyValue kv = new KeyValue(b, b, b, i, b);
      kvs.add(kv);
      // kv has memstoreTS 0, which takes 1 byte to store.
      totalSize += kv.getLength() + 1;
    }
    int blockSize = totalSize / numBlocks;
    HFileContext meta = new HFileContextBuilder().withBlockSize(blockSize)
                        .withChecksumType(CKTYPE)
                        .withBytesPerCheckSum(CKBYTES)
                        .build();
    // Make a store file and write data to it.
    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf, this.fs)
            .withFilePath(path)
            .withMaxKeyCount(2000)
            .withFileContext(meta)
            .build();
    // We'll write N-1 KVs to ensure we don't write an extra block
    kvs.remove(kvs.size()-1);
    for (KeyValue kv : kvs) {
      writer.append(kv);
    }
    writer.appendMetadata(0, false);
    writer.close();
    return writer;
  }

  /**
   * Check if data block encoding information is saved correctly in HFile's
   * file info.
   */
  public void testDataBlockEncodingMetaData() throws IOException {
    // Make up a directory hierarchy that has a regiondir ("7e0102") and familyname.
    Path dir = new Path(new Path(this.testDir, "7e0102"), "familyname");
    Path path = new Path(dir, "1234567890");

    DataBlockEncoding dataBlockEncoderAlgo =
        DataBlockEncoding.FAST_DIFF;
    HFileDataBlockEncoder dataBlockEncoder =
        new HFileDataBlockEncoderImpl(
            dataBlockEncoderAlgo);
    cacheConf = new CacheConfig(conf);
    HFileContext meta = new HFileContextBuilder().withBlockSize(BLOCKSIZE_SMALL)
        .withChecksumType(CKTYPE)
        .withBytesPerCheckSum(CKBYTES)
        .withDataBlockEncoding(dataBlockEncoderAlgo)
        .build();
    // Make a store file and write data to it.
    StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, cacheConf, this.fs)
            .withFilePath(path)
            .withMaxKeyCount(2000)
            .withFileContext(meta)
            .build();
    writer.close();

    StoreFile storeFile = new StoreFile(fs, writer.getPath(), conf,
      cacheConf, BloomType.NONE);
    StoreFile.Reader reader = storeFile.createReader();

    Map<byte[], byte[]> fileInfo = reader.loadFileInfo();
    byte[] value = fileInfo.get(HFileDataBlockEncoder.DATA_BLOCK_ENCODING);
    assertEquals(dataBlockEncoderAlgo.getNameInBytes(), value);
  }
}

