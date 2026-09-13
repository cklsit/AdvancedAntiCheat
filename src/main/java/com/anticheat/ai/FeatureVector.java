package com.anticheat.ai;

import java.util.Arrays;

/**
 * 特征向量：固定 {@link FeatureDimensions#DIMS} 维 + 采样时间戳。
 * 不可变语义（values 数组仅在构造时填充），线程间安全传递。
 */
public final class FeatureVector {

    private final long timestamp;
    private final double[] values;

    public FeatureVector(long timestamp, double[] values) {
        if (values == null || values.length != FeatureDimensions.DIMS) {
            throw new IllegalArgumentException("feature vector dim mismatch: "
                    + (values == null ? "null" : values.length));
        }
        this.timestamp = timestamp;
        this.values = values;
    }

    /** 零向量（未知特征占位）。 */
    public static FeatureVector zero(long ts) {
        return new FeatureVector(ts, new double[FeatureDimensions.DIMS]);
    }

    public long getTimestamp() {
        return timestamp;
    }

    public double[] getValues() {
        return values;
    }

    public double get(int dim) {
        return values[dim];
    }

    public int getDim() {
        return values.length;
    }

    /** 深拷贝 values。 */
    public double[] copyValues() {
        return Arrays.copyOf(values, values.length);
    }
}
