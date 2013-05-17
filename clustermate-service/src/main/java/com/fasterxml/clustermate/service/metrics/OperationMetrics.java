package com.fasterxml.clustermate.service.metrics;

import java.util.concurrent.TimeUnit;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.*;

import com.fasterxml.clustermate.service.OperationDiagnostics;
import com.fasterxml.clustermate.service.cfg.ServiceConfig;
import com.fasterxml.storemate.store.Storable;

/**
 * Helper class for aggregating sets of internal CRUD endpoint
 * metrics.
 */
public class OperationMetrics
{
    protected final ServiceConfig _serviceConfig;

    /*
    /**********************************************************************
    /* Actual metric aggregators
    /**********************************************************************
     */

    protected final Counter _metricInFlight;

    protected final Timer _metricTimes;

    // // Size metrics (optional)

    protected final Histogram _metricSizes;

    protected final Histogram _metricEntryCounts;

    /*
    /**********************************************************************
    /* Public API for JSON serialization
    /**********************************************************************
     */

    public Counter getInFlight() {
        return _metricInFlight;
    }

    public Timer getTimes() {
        return _metricTimes;
    }

    public Histogram getSizes() {
        return _metricSizes;
    }

    public Histogram getEntryCounts() {
        return _metricEntryCounts;
    }
    
    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */

    private OperationMetrics(ServiceConfig serviceConfig, String operationName,
            boolean includeSizes, boolean includeEntryCounts)
    {
        _serviceConfig = serviceConfig;
        String metricGroup = serviceConfig.metricsJmxRoot;

        // and then create metrics
        
        // first: in-flight counter, "active" requests
        _metricInFlight = Metrics.newCounter(new MetricName(metricGroup, operationName, "active"));
        _metricTimes = Metrics.newTimer(new MetricName(metricGroup, operationName, "times"),
                TimeUnit.MILLISECONDS, TimeUnit.SECONDS);

        _metricSizes = includeSizes ?
                Metrics.newHistogram(new MetricName(metricGroup, operationName, "sizes"), true)
                : null;
        _metricEntryCounts = includeEntryCounts ?
                Metrics.newHistogram(new MetricName(metricGroup, operationName, "counts"), true)
                : null;
    }

    public static OperationMetrics forEntityOperation(ServiceConfig serviceConfig, String operationName)
    {
        return new OperationMetrics(serviceConfig, operationName, true, false);
    }

    public static OperationMetrics forListingOperation(ServiceConfig serviceConfig, String operationName)
    {
        return new OperationMetrics(serviceConfig, operationName, false, true);
    }

    public static OperationMetrics forNonPayloadOperation(ServiceConfig serviceConfig, String operationName)
    {
        return new OperationMetrics(serviceConfig, operationName, false, false);
    }
    
    public TimerContext start()
    {
        if (!_serviceConfig.metricsEnabled) {
            return null;
        }
        _metricInFlight.inc();
        return _metricTimes.time();
    }

    public void finish(TimerContext timer, OperationDiagnostics opStats)
    {
        _metricInFlight.dec();
        if (timer != null) {
            timer.stop();
        }
        if (opStats != null) {
            if (_metricSizes != null) {
                Storable entity = opStats.getEntry();
                if (entity != null) {
                    _metricSizes.update(entity.getActualUncompressedLength());
                }
            }
            if (_metricEntryCounts != null) {
                _metricEntryCounts.update(opStats.getItemCount());
            }
        }
    }

}
