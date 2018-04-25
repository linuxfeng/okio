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

import javax.annotation.Nullable;

/**
 * A segment of a buffer.
 *buffer的一个数据段
 * <p>Each segment in a buffer is a circularly-linked list node referencing the following and
 * preceding segments in the buffer.
 *在buffer中，数据段组成了一个环形链表，每个段是其中的一个节点
 * <p>Each segment in the pool is a singly-linked list node referencing the rest of segments in the
 * pool.
 *在对象池中，数据段组成了一个单向链表，这个对象池用于回收用过的对象，避免重复创建
 * <p>The underlying byte arrays of segments may be shared between buffers and byte strings. When a
 * segment's byte array is shared the segment may not be recycled, nor may its byte data be changed.
 * The lone exception is that the owner segment is allowed to append to the segment, writing data at
 * {@code limit} and beyond. There is a single owning segment for each byte array. Positions,
 * limits, prev, and next references are not shared.
 * byte数组，可以供buffer和byte string共享，当一个byte[]被共享时，它就不能被回收，
 * 只有数据段的owner被允许追加，限制内写入数据，每个数据段只有一个owner。Positions,
 * limits, prev, and next这些变量不允许共享
 */
final class Segment {
  /** The size of all segments in bytes. */
  static final int SIZE = 8192;

  //共享数据的最小值
  /** Segments will be shared when doing so avoids {@code arraycopy()} of this many bytes. */
  static final int SHARE_MINIMUM = 1024;

  final byte[] data;
   //可以读取数据的第一个位置。数据的存储是一个数组，可写数据位置和可读数据位置的中间就是存储的数据
  /** The next byte of application data byte to read in this segment. */
  int pos;
  //可以写入数据的第一个位置。
  /** The first byte of available data ready to be written to. */
  int limit;

  /** True if other segments or byte strings use the same byte array. */
  boolean shared;

  /** True if this segment owns the byte array and can append to it, extending {@code limit}. */
  boolean owner;

  /** Next segment in a linked or circularly-linked list. */
  Segment next;

  /** Previous segment in a circularly-linked list. */
  Segment prev;

  Segment() {
    this.data = new byte[SIZE];
    this.owner = true;
    this.shared = false;
  }

  Segment(byte[] data, int pos, int limit, boolean shared, boolean owner) {
    this.data = data;
    this.pos = pos;
    this.limit = limit;
    this.shared = shared;
    this.owner = owner;
  }

  /**
   * Returns a new segment that shares the underlying byte array with this. Adjusting pos and limit
   * are safe but writes are forbidden. This also marks the current segment as shared, which
   * prevents it from being pooled.
   *
   * 返回的Segment和this共享data数据，并且返回的Segment的share为false
   */
  Segment sharedCopy() {
    shared = true;
    return new Segment(data, pos, limit, true, false);
  }

  /**
   * Returns a new segment that its own private copy of the underlying byte array.
   * 不共享data
   * */
  Segment unsharedCopy() {
    return new Segment(data.clone(), pos, limit, false, true);
  }

  /**
   * Removes this segment of a circularly-linked list and returns its successor.
   * Returns null if the list is now empty.
   * 移除本数据段，返回值为它的后继者
   */
  public @Nullable Segment pop() {
    Segment result = next != this ? next : null;
    prev.next = next;
    next.prev = prev;
    next = null;
    prev = null;
    return result;
  }

  /**
   * Appends {@code segment} after this segment in the circularly-linked list.
   * Returns the pushed segment.
   */
  public Segment push(Segment segment) {
    segment.prev = this;
    segment.next = next;
    next.prev = segment;
    next = segment;
    return segment;
  }

  /**
   * Splits this head of a circularly-linked list into two segments. The first
   * segment contains the data in {@code [pos..pos+byteCount)}. The second
   * segment contains the data in {@code [pos+byteCount..limit)}. This can be
   * useful when moving partial segments from one buffer to another.
   *
   * <p>Returns the new head of the circularly-linked list.
   * 返回环形链表的头
   */
  public Segment split(int byteCount) {
    if (byteCount <= 0 || byteCount > limit - pos) throw new IllegalArgumentException();
    Segment prefix;

    // We have two competing performance goals:
    //  - Avoid copying data. We accomplish this by sharing segments.
    //  - Avoid short shared segments. These are bad for performance because they are readonly and
    //    may lead to long chains of short segments.
    // To balance these goals we only share segments when the copy will be large.
      //分割的数据个数大于最小值，两个Segment共享data[]部分数据
    if (byteCount >= SHARE_MINIMUM) {
      prefix = sharedCopy();
    } else {
        //要不，把this数据段拷贝部分到prefix
      prefix = SegmentPool.take();
      System.arraycopy(data, pos, prefix.data, 0, byteCount);
    }

    prefix.limit = prefix.pos + byteCount;
    pos += byteCount;
    prev.push(prefix);
    return prefix;
  }

  /**
   * Call this when the tail and its predecessor may both be less than half
   * full. This will copy data so that segments can be recycled.
   * 当本Segment的已写数据小于前一个Segment的可用数据时，把本Segment的数据拷贝到前一个Segment
   * 然后释放本Segment
   */
  public void compact() {
    if (prev == this) throw new IllegalStateException();
    if (!prev.owner) return; // Cannot compact: prev isn't writable.
    int byteCount = limit - pos;
    int availableByteCount = SIZE - prev.limit + (prev.shared ? 0 : prev.pos);
    if (byteCount > availableByteCount) return; // Cannot compact: not enough writable space.
    writeTo(prev, byteCount);
    pop();
    SegmentPool.recycle(this);
  }

  /** Moves {@code byteCount} bytes from this segment to {@code sink}. */
  public void writeTo(Segment sink, int byteCount) {
    if (!sink.owner) throw new IllegalArgumentException();
    if (sink.limit + byteCount > SIZE) {
        //如果当前可写的位置加上要写的个数大于Segment的SIZE
      // We can't fit byteCount bytes at the sink's current position. Shift sink first.
      if (sink.shared) throw new IllegalArgumentException();
      //如果空余的总个数小于byteCount，报错
      if (sink.limit + byteCount - sink.pos > SIZE) throw new IllegalArgumentException();
      //把已有的数据拷贝挪到数组的0位置
      System.arraycopy(sink.data, sink.pos, sink.data, 0, sink.limit - sink.pos);
      sink.limit -= sink.pos;
      sink.pos = 0;
    }

    System.arraycopy(data, pos, sink.data, sink.limit, byteCount);
    sink.limit += byteCount;
    pos += byteCount;
  }
}
