package com.backend.analysis;

public final class ClaimedJob {

    private final AnalysisJob job;
    private final SchedulerLease lease;

    public ClaimedJob(AnalysisJob job, SchedulerLease lease) {
        this.job = job;
        this.lease = lease;
    }

    public AnalysisJob getJob() {
        return job;
    }

    public SchedulerLease getLease() {
        return lease;
    }
}
