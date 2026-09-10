package com.anticheat.managers.replay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 固定容量的环形缓冲。读多写少场景下使用 {@link ReentrantReadWriteLock} 做读写分离。
 * <p>
 * 容量满后，新元素会覆盖最旧的元素。
 * 内部维护 appendIdx（下一个写入位置）和 size（当前有效元素数）。
 *
 * @param <T> 元素类型
 */
public class RingBuffer<T> {

    private final T[] buffer;
    private final int capacity;
    private int size;         // 当前元素数，<= capacity
    private int writeIdx;     // 下一个要写入的位置（环形索引）

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @SuppressWarnings("unchecked")
    public RingBuffer(int capacity) {
        if (capacity <= 0) capacity = 16;
        this.capacity = capacity;
        this.buffer = (T[]) new Object[capacity];
        this.size = 0;
        this.writeIdx = 0;
    }

    public int capacity() {
        return capacity;
    }

    /**
     * 写入一个元素。容量满时覆盖最旧元素。
     */
    public void add(T item) {
        lock.writeLock().lock();
        try {
            buffer[writeIdx] = item;
            writeIdx = (writeIdx + 1) % capacity;
            if (size < capacity) {
                size++;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 当前有效元素数。
     */
    public int size() {
        lock.readLock().lock();
        try {
            return size;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 清空所有元素。
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            // help GC —— 显式置 null
            for (int i = 0; i < size; i++) {
                int idx = (writeIdx - size + i + capacity) % capacity;
                buffer[idx] = null;
            }
            size = 0;
            writeIdx = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 返回当前所有有效元素的有序拷贝（最旧在前，最新在后）。
     */
    public List<T> snapshot() {
        lock.readLock().lock();
        try {
            if (size == 0) return new ArrayList<>();
            List<T> result = new ArrayList<>(size);
            int startIdx = (writeIdx - size + capacity) % capacity;
            for (int i = 0; i < size; i++) {
                result.add(buffer[(startIdx + i) % capacity]);
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 返回第一个（最旧的）元素；空时返回 null。
     */
    public T getFirst() {
        lock.readLock().lock();
        try {
            if (size == 0) return null;
            int startIdx = (writeIdx - size + capacity) % capacity;
            return buffer[startIdx];
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 返回最后一个（最新的）元素；空时返回 null。
     */
    public T getLast() {
        lock.readLock().lock();
        try {
            if (size == 0) return null;
            int lastIdx = (writeIdx - 1 + capacity) % capacity;
            return buffer[lastIdx];
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 返回指定索引位置的元素（0 = 最旧，size-1 = 最新）。
     * 索引越界返回 null。
     */
    public T get(int index) {
        lock.readLock().lock();
        try {
            if (index < 0 || index >= size) return null;
            int startIdx = (writeIdx - size + capacity) % capacity;
            return buffer[(startIdx + index) % capacity];
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 返回最后一个（最新的）元素；空时返回 null。
     * 与 {@link #getLast()} 同义，语义化别名。
     */
    public T peekLast() {
        return getLast();
    }

    /**
     * 返回当前所有有效元素的有序拷贝（最旧在前，最新在后）。
     * 与 {@link #snapshot()} 同义，语义化别名。
     */
    public List<T> toList() {
        return snapshot();
    }
}
