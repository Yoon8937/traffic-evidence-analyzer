package com.backend.analysis;

import java.util.UUID;

public final class SchedulerLease {

    private final UUID jobId;
    private final String owner;
    private final String task;
    private final String token;

    public SchedulerLease(UUID jobId, String owner, String task, String token) {
        this.jobId = jobId;
        this.owner = owner;
        this.task = task;
        this.token = token;
    }

    public UUID getJobId() {
        return jobId;
    }

    public String getOwner() {
        return owner;
    }

    public String getTask() {
        return task;
    }

    public String getToken() {
        return token;
    }
}
