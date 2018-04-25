/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okio;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

import static okio.Util.checkOffsetAndCount;

/**
 * This timeout uses a background thread to take action exactly when the timeout occurs. Use this to
 * implement timeouts where they aren't supported natively, such as to sockets that are blocked on
 * writing.
 *
 * 通过一个后台线程监控超时单向链表，这是因为比如socket在写的时候会阻塞。
 *
 * <p>Subclasses should override {@link #timedOut} to take action when a timeout occurs. This method
 * will be invoked by the shared watchdog thread so it should not do any long-running operations.
 * Otherwise we risk starving other timeouts from being triggered.
 *
 * 如果要用AsyncTimeout，必须实现timedOut方法，当超时发生的时候，timedOut方法将在后台线程里面调用，所以timedOut方法里
 * 不要做太耗时间的操作，这样会影响对超时的监控
 *
 * <p>Use {@link #sink} and {@link #source} to apply this timeout to a stream. The returned value
 * will apply the timeout to each operation on the wrapped stream.
 *
 * <p>Callers should call {@link #enter} before doing work that is subject to timeouts, and {@link
 * #exit} afterwards. The return value of {@link #exit} indicates whether a timeout was triggered.
 * Note that the call to {@link #timedOut} is asynchronous, and may be called after {@link #exit}.
 */
public class AsyncTimeout extends Timeout {
  /**
   * Don't write more than 64 KiB of data at a time, give or take a segment. Otherwise slow
   * connections may suffer timeouts even when they're making (slow) progress. Without this, writing
   * a single 1 MiB buffer may never succeed on a sufficiently slow connection.
   *
   * 不要一次写超过64k的数据否则可能会在慢连接中导致超时
   */
  private static final int TIMEOUT_WRITE_SIZE = 64 * 1024;

  /**
   * watchdog线程关闭自己之前空闲的时间
   * Duration for the watchdog thread to be idle before it shuts itself down. */
  private static final long IDLE_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);
  private static final long IDLE_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(IDLE_TIMEOUT_MILLIS);

  /**
   * The watchdog thread processes a linked list of pending timeouts, sorted in the order to be
   * triggered. This class synchronizes on AsyncTimeout.class. This lock guards the queue.
   *
   * watchdog线程监控一个链表，这个链表以超时发生的先后顺序排序，通过AsyncTimeout.class保证链表的同步
   *
   * <p>Head's 'next' points to the first element of the linked list. The first element is the next
   * node to time out, or null if the queue is empty. The head is null until the watchdog thread is
   * started and also after being idle for {@link #IDLE_TIMEOUT_MILLIS}.
   */
  static @Nullable AsyncTimeout head;

  /** True if this node is currently in the queue. */
  private boolean inQueue;

  /** The next node in the linked list. */
  private @Nullable AsyncTimeout next;

  /** If scheduled, this is the time that the watchdog should time this out. */
  private long timeoutAt;

  public final void enter() {
      //如果已经在链表里，则退出
    if (inQueue) throw new IllegalStateException("Unbalanced enter/exit");
    long timeoutNanos = timeoutNanos();
    boolean hasDeadline = hasDeadline();
    if (timeoutNanos == 0 && !hasDeadline) {
      return; // No timeout and no deadline? Don't bother with the queue.
    }
    inQueue = true;
    scheduleTimeout(this, timeoutNanos, hasDeadline);
  }

  private static synchronized void scheduleTimeout(
      AsyncTimeout node, long timeoutNanos, boolean hasDeadline) {
    // Start the watchdog thread and create the head node when the first timeout is scheduled.
      //当head为空时，new 一个head,也就是说链表头head是一个空的，不参数超时计算和超时时间比较
      // 开始watchdog监控线程
    if (head == null) {
      head = new AsyncTimeout();
      new Watchdog().start();
    }

    long now = System.nanoTime();
    if (timeoutNanos != 0 && hasDeadline) {
      // Compute the earliest event; either timeout or deadline. Because nanoTime can wrap around,
      // Math.min() is undefined for absolute values, but meaningful for relative ones.
      node.timeoutAt = now + Math.min(timeoutNanos, node.deadlineNanoTime() - now);
    } else if (timeoutNanos != 0) {
      node.timeoutAt = now + timeoutNanos;
    } else if (hasDeadline) {
      node.timeoutAt = node.deadlineNanoTime();
    } else {
      throw new AssertionError();
    }

    // Insert the node in sorted order.
    long remainingNanos = node.remainingNanos(now);
    //超时时间从小到大排列插入
    for (AsyncTimeout prev = head; true; prev = prev.next) {
      if (prev.next == null || remainingNanos < prev.next.remainingNanos(now)) {
            node.next = prev.next;
            prev.next = node;
            if (prev == head) {
                //插入的node在head之后，就唤醒watchdog
                AsyncTimeout.class.notify(); // Wake up the watchdog when inserting at the front.
            }
            break;
        }
    }
  }

  /** Returns true if the timeout occurred. */
  public final boolean exit() {
    if (!inQueue) return false;
    inQueue = false;
    return cancelScheduledTimeout(this);
  }

  /**
   * 从链表里删除对应node，如果找到，删除并返回false
   * 如果没有找到，说明超时已经发生，返回true
   *
   * Returns true if the timeout occurred. */
  private static synchronized boolean cancelScheduledTimeout(AsyncTimeout node) {
    // Remove the node from the linked list.
    for (AsyncTimeout prev = head; prev != null; prev = prev.next) {
      if (prev.next == node) {
        prev.next = node.next;
        node.next = null;
        return false;
      }
    }

    // The node wasn't found in the linked list: it must have timed out!
    return true;
  }

  /**
   * Returns the amount of time left until the time out. This will be negative if the timeout has
   * elapsed and the timeout should occur immediately.
   */
  private long remainingNanos(long now) {
    return timeoutAt - now;
  }

  /**
   * Invoked by the watchdog thread when the time between calls to {@link #enter()} and {@link
   * #exit()} has exceeded the timeout.
   */
  protected void timedOut() {
  }

  /**
   * Returns a new sink that delegates to {@code sink}, using this to implement timeouts. This works
   * best if {@link #timedOut} is overridden to interrupt {@code sink}'s current operation.
   */
  public final Sink sink(final Sink sink) {
    return new Sink() {
      @Override public void write(Buffer source, long byteCount) throws IOException {
        checkOffsetAndCount(source.size, 0, byteCount);

        while (byteCount > 0L) {
          // Count how many bytes to write. This loop guarantees we split on a segment boundary.
            //计算有多少个字节需要写：1，toWrite 要小于TIMEOUT_WRITE_SIZE。
          long toWrite = 0L;
          for (Segment s = source.head; toWrite < TIMEOUT_WRITE_SIZE; s = s.next) {
            int segmentSize = s.limit - s.pos;
            toWrite += segmentSize;
            //如果可写的大于byteCount，去byteCount
            if (toWrite >= byteCount) {
              toWrite = byteCount;
              break;
            }
          }

          // Emit one write. Only this section is subject to the timeout.
          boolean throwOnTimeout = false;
          //向监控队列中加入this
          enter();
          try {
            sink.write(source, toWrite);
            byteCount -= toWrite;
            throwOnTimeout = true;
          } catch (IOException e) {
              //异常，this移除监控队列
            throw exit(e);
          } finally {
              //this移除监控队列
            exit(throwOnTimeout);
          }
        }
      }

      @Override public void flush() throws IOException {
        boolean throwOnTimeout = false;
        enter();
        try {
          sink.flush();
          throwOnTimeout = true;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public void close() throws IOException {
        boolean throwOnTimeout = false;
        enter();
        try {
          sink.close();
          throwOnTimeout = true;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public Timeout timeout() {
        return AsyncTimeout.this;
      }

      @Override public String toString() {
        return "AsyncTimeout.sink(" + sink + ")";
      }
    };
  }

  /**
   * Returns a new source that delegates to {@code source}, using this to implement timeouts. This
   * works best if {@link #timedOut} is overridden to interrupt {@code sink}'s current operation.
   */
  public final Source source(final Source source) {
    return new Source() {
      @Override public long read(Buffer sink, long byteCount) throws IOException {
        boolean throwOnTimeout = false;
        enter();
        try {
          long result = source.read(sink, byteCount);
          throwOnTimeout = true;
          return result;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public void close() throws IOException {
        boolean throwOnTimeout = false;
        try {
          source.close();
          throwOnTimeout = true;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public Timeout timeout() {
        return AsyncTimeout.this;
      }

      @Override public String toString() {
        return "AsyncTimeout.source(" + source + ")";
      }
    };
  }

  /**
   * Throws an IOException if {@code throwOnTimeout} is {@code true} and a timeout occurred. See
   * {@link #newTimeoutException(java.io.IOException)} for the type of exception thrown.
   */
  final void exit(boolean throwOnTimeout) throws IOException {
    boolean timedOut = exit();
    if (timedOut && throwOnTimeout) throw newTimeoutException(null);
  }

  /**
   * Returns either {@code cause} or an IOException that's caused by {@code cause} if a timeout
   * occurred. See {@link #newTimeoutException(java.io.IOException)} for the type of exception
   * returned.
   */
  final IOException exit(IOException cause) throws IOException {
    if (!exit()) return cause;
    return newTimeoutException(cause);
  }

  /**
   * Returns an {@link IOException} to represent a timeout. By default this method returns {@link
   * java.io.InterruptedIOException}. If {@code cause} is non-null it is set as the cause of the
   * returned exception.
   */
  protected IOException newTimeoutException(@Nullable IOException cause) {
    InterruptedIOException e = new InterruptedIOException("timeout");
    if (cause != null) {
      e.initCause(cause);
    }
    return e;
  }

  /**
   * WatchDog线程在后台进行监听超时，里面的run方法执行的就是核心的超时判断，
   * 之所以在socket写时采取异步超时，这完全是由socket自身的性质决定的，socket经常会阻塞自己，导致下面的事情执行不了。
   * AsyncTimeout继承于Timeout类，可以覆写里面的timeout方法，这个方法会在watchdog的线程中调用，
   * 所以不能执行长时间的操作，否则就会引发其他的超时
   */
  private static final class Watchdog extends Thread {
    Watchdog() {
      super("Okio Watchdog");
      setDaemon(true);
    }

    public void run() {
      while (true) {
        try {
          AsyncTimeout timedOut;
          synchronized (AsyncTimeout.class) {
            timedOut = awaitTimeout();

            // Didn't find a node to interrupt. Try again.
            if (timedOut == null) continue;

            // The queue is completely empty. Let this thread exit and let another watchdog thread
            // get created on the next call to scheduleTimeout().
            if (timedOut == head) {
              head = null;
              return;
            }
          }

          // Close the timed out node.
            //超时发生后，所做的处理
          timedOut.timedOut();
        } catch (InterruptedException ignored) {
        }
      }
    }
  }

  /**
   * Removes and returns the node at the head of the list, waiting for it to time out if necessary.
   * This returns {@link #head} if there was no node at the head of the list when starting, and
   * there continues to be no node after waiting {@code IDLE_TIMEOUT_NANOS}. It returns null if a
   * new node was inserted while waiting. Otherwise this returns the node being waited on that has
   * been removed.
   *
   * 返回和移除一个在链表头的node，
   */
  static @Nullable AsyncTimeout awaitTimeout() throws InterruptedException {
    // Get the next eligible node.
    AsyncTimeout node = head.next;

    // The queue is empty. Wait until either something is enqueued or the idle timeout elapses.
      //如果队列是空的，就等待IDLE_TIMEOUT_MILLIS，在等待的过程中，被唤醒了，说明有node插入，返回null，
      //如果没唤醒，返回head
    if (node == null) {
      long startNanos = System.nanoTime();
      AsyncTimeout.class.wait(IDLE_TIMEOUT_MILLIS);
      return head.next == null && (System.nanoTime() - startNanos) >= IDLE_TIMEOUT_NANOS
          ? head  // The idle timeout elapsed.
          : null; // The situation has changed.
    }

    long waitNanos = node.remainingNanos(System.nanoTime());

    //队列的第一个还没超时，等待，等待结束后，返回null，继续遍历,在下次的遍历中
      //处理发生超时的node
    // The head of the queue hasn't timed out yet. Await that.
    if (waitNanos > 0) {
      // Waiting is made complicated by the fact that we work in nanoseconds,
      // but the API wants (millis, nanos) in two arguments.
      long waitMillis = waitNanos / 1000000L;
      waitNanos -= (waitMillis * 1000000L);
      AsyncTimeout.class.wait(waitMillis, (int) waitNanos);
      return null;
    }
    //队列的第一个已经超时，则移除队列，并返回超时的node.
    // The head of the queue has timed out. Remove it.
    head.next = node.next;
    node.next = null;
    return node;
  }
}
