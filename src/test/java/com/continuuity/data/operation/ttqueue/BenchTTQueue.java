package com.continuuity.data.operation.ttqueue;

import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

import com.continuuity.common.conf.CConfiguration;
import com.continuuity.data.engine.memory.oracle.MemoryStrictlyMonotonicTimeOracle;
import com.continuuity.data.operation.executor.omid.TimestampOracle;
import com.continuuity.data.operation.executor.omid.memory.MemoryReadPointer;
import com.continuuity.data.table.ReadPointer;

public abstract class BenchTTQueue {

  protected static TimestampOracle timeOracle =
      new MemoryStrictlyMonotonicTimeOracle();

  private TTQueue createQueue() {
    return createQueue(new CConfiguration());
  }

  private BenchConfig config = getConfig();
  
  protected abstract TTQueue createQueue(CConfiguration conf);

  protected abstract BenchConfig getConfig();
  
  protected static class BenchConfig {
    protected int numJustEnqueues = 1000;
    protected int queueEntrySize = 1024;
    protected int numEnqueuesThenSyncDequeueAckFinalize = 1000;
  }
  
  protected static final Random r = new Random();
  
  @Test
  public void benchJustEnqueues() throws Exception {
    TTQueue queue = createQueue();
    int iterations = config.numJustEnqueues;
    long start = now();
    
    byte [] data = new byte[config.queueEntrySize];
    long last = start;
    for (int i=0; i<iterations; i++) {
      r.nextBytes(data);
      assertTrue(queue.enqueue(data, timeOracle.getTimestamp()).isSuccess());
      last = printStat(i, last, 1000);
    }
    
    long end = now();
    printReport(start, end, iterations);
  }
  
  @Test
  public void benchEnqueuesThenSyncDequeueAckFinalize() throws Exception {
    TTQueue queue = createQueue();
    int iterations = config.numEnqueuesThenSyncDequeueAckFinalize;
    long start = now();
    
    log("Enqueueing " + iterations + " entries");
    byte [] data = new byte[config.queueEntrySize];
    long last = start;
    for (int i=0; i<iterations; i++) {
      r.nextBytes(data);
      assertTrue(queue.enqueue(data, timeOracle.getTimestamp()).isSuccess());
      last = printStat(i, last, 1000);
    }
    long end = now();
    printReport(start, end, iterations);
    log("Done enqueueing\n");
    
    log("Dequeueing " + iterations + " entries");
    long dstart = now();
    last = dstart;
    QueueConsumer consumer = new QueueConsumer(0, 0, 1);
    QueueConfig config = new QueueConfig(
        new QueuePartitioner.RandomPartitioner(), true);
    ReadPointer rp = new MemoryReadPointer(timeOracle.getTimestamp());
    for (int i=0; i<iterations; i++) {
      DequeueResult result = queue.dequeue(consumer, config, rp);
      assertTrue(result.isSuccess());
      assertTrue(queue.ack(result.getEntryPointer(), consumer));
      assertTrue(queue.finalize(result.getEntryPointer(), consumer));
      last = printStat(i, last, 1000);
    }
    long dend = now();
    printReport(dstart, dend, iterations);
    log("Done dequeueing\n");
    
  }
  
  private long printStat(int i, long last, int perline) {
    i++;
    if (i % (perline/10) == 0) System.out.print(".");
    if (i % perline == 0) {
      System.out.println(" " + i + " : Last " + perline + " finished in " +
          timeReport(last, now(), perline));
      return now();
    }
    return last;
  }

  private void printReport(long start, long end, int iterations) {
    log("Finished " + iterations + " iterations in " +
        timeReport(start, end, iterations));
  }
  
  private String timeReport(long start, long end, int iterations) {
    return "" + format(end-start) + " (" +
        format(end-start, iterations) + "/iteration)";
  }

  private String format(long time, int iterations) {
    return "" + (time/(float)iterations) + "ms";
  }

  private String format(long time) {
    if (time < 1000) return "" + time + "ms";
    if (time < 60000) return "" + (time/(float)1000) + "sec";
    long min = time / 60000;
    float sec = (time - (min*60000)) / (float)1000;
    return "" + min + "min " + sec + "sec";
  }

  protected void log(String msg) {
    System.out.println("" + now() + " : " + msg);
  }
  
  protected long now() {
    return System.currentTimeMillis();
  }
}
