package com.unisence.iot.timeseries;

/**
 * Normalized storage failure used by ingestion retry/DLQ decisions.
 */
public class TimeSeriesStorageException extends RuntimeException {

    private final boolean retryable;

    public TimeSeriesStorageException(boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
