package com.anticheat.profiles;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class NormalDistribution implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final AtomicReference<Double> mean;
    private final AtomicReference<Double> variance;
    private final AtomicInteger sampleCount;
    
    private static final int MIN_SAMPLES_FOR_STATS = 5;
    private static final double MIN_VARIANCE = 0.0001;
    
    public NormalDistribution() {
        this.mean = new AtomicReference<>(0.0);
        this.variance = new AtomicReference<>(0.0);
        this.sampleCount = new AtomicInteger(0);
    }
    
    public synchronized void addSample(double value) {
        int n = sampleCount.get();
        double currentMean = mean.get();
        double currentVariance = variance.get();
        
        n++;
        double newMean = currentMean + (value - currentMean) / n;
        
        if (n == 1) {
            variance.set(0.0);
        } else {
            double newVariance = ((n - 2) * currentVariance + 
                (value - currentMean) * (value - newMean)) / (n - 1);
            variance.set(Math.max(newVariance, MIN_VARIANCE));
        }
        
        mean.set(newMean);
        sampleCount.incrementAndGet();
    }
    
    public double getMean() {
        return mean.get();
    }
    
    public double getStandardDeviation() {
        double var = variance.get();
        if (var < MIN_VARIANCE) {
            return 0.01;
        }
        return Math.sqrt(var);
    }
    
    public double getVariance() {
        return variance.get();
    }
    
    public int getSampleCount() {
        return sampleCount.get();
    }
    
    public boolean hasEnoughSamples() {
        return sampleCount.get() >= MIN_SAMPLES_FOR_STATS;
    }
    
    public double calculateZScore(double value) {
        double stdDev = getStandardDeviation();
        if (stdDev < 0.01) {
            return 0.0;
        }
        double currentMean = mean.get();
        return (value - currentMean) / stdDev;
    }
    
    public boolean isOutlier(double value, double threshold) {
        if (!hasEnoughSamples()) {
            return false;
        }
        
        double zScore = calculateZScore(value);
        return Math.abs(zScore) > threshold;
    }
    
    public void reset() {
        mean.set(0.0);
        variance.set(0.0);
        sampleCount.set(0);
    }
    
    public double calculateProbabilityDensity(double x) {
        if (!hasEnoughSamples()) {
            return 0.0;
        }
        
        double m = mean.get();
        double s = getStandardDeviation();
        
        if (s < 0.01) {
            return x == m ? 1.0 : 0.0;
        }
        
        double coefficient = 1.0 / (s * Math.sqrt(2 * Math.PI));
        double exponent = -Math.pow(x - m, 2) / (2 * s * s);
        
        return coefficient * Math.exp(exponent);
    }
    
    public double getPercentile(double percentile) {
        return mean.get();
    }
}
