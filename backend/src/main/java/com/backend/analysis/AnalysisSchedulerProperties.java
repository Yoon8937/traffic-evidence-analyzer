package com.backend.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.analysis")
public class AnalysisSchedulerProperties {

    private int processingTimeoutMinutes = 30;
    private final Scheduler scheduler = new Scheduler();

    public int getProcessingTimeoutMinutes() {
        return processingTimeoutMinutes;
    }

    public void setProcessingTimeoutMinutes(int processingTimeoutMinutes) {
        this.processingTimeoutMinutes = processingTimeoutMinutes;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public static class Scheduler {
        private boolean enabled = false;
        private long dispatchIntervalMs = 1000;
        private long recoveryIntervalMs = 10000;
        private long timeoutIntervalMs = 30000;
        private int batchSize = 20;
        private int leaseSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getDispatchIntervalMs() {
            return dispatchIntervalMs;
        }

        public void setDispatchIntervalMs(long dispatchIntervalMs) {
            this.dispatchIntervalMs = dispatchIntervalMs;
        }

        public long getRecoveryIntervalMs() {
            return recoveryIntervalMs;
        }

        public void setRecoveryIntervalMs(long recoveryIntervalMs) {
            this.recoveryIntervalMs = recoveryIntervalMs;
        }

        public long getTimeoutIntervalMs() {
            return timeoutIntervalMs;
        }

        public void setTimeoutIntervalMs(long timeoutIntervalMs) {
            this.timeoutIntervalMs = timeoutIntervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(int leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }
    }
}
