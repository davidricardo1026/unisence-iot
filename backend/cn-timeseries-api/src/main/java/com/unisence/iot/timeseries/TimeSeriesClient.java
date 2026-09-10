package com.unisence.iot.timeseries;

/**
 * Stable domain-level contract shared by ingestion and management services.
 */
public interface TimeSeriesClient extends TimeSeriesReader, TimeSeriesWriter, TimeSeriesProvisioner {

    void observer(TimeSeriesObserver observer);

}
