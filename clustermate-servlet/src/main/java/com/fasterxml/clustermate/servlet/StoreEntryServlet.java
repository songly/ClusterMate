package com.fasterxml.clustermate.servlet;

import java.io.IOException;

import com.codahale.metrics.Timer.Context;

import com.fasterxml.jackson.databind.ObjectWriter;

import com.fasterxml.storemate.store.util.OperationDiagnostics;

import com.fasterxml.clustermate.api.EntryKey;
import com.fasterxml.clustermate.api.EntryKeyConverter;
import com.fasterxml.clustermate.service.SharedServiceStuff;
import com.fasterxml.clustermate.service.cfg.ServiceConfig;
import com.fasterxml.clustermate.service.cluster.ClusterViewByServer;
import com.fasterxml.clustermate.service.metrics.AllOperationMetrics;
import com.fasterxml.clustermate.service.metrics.ExternalOperationMetrics;
import com.fasterxml.clustermate.service.metrics.OperationMetrics;
import com.fasterxml.clustermate.service.store.StoreHandler;
import com.fasterxml.clustermate.service.store.StoredEntry;

/**
 * Servlet that handles basic CRUD operations for individual entries.
 */
@SuppressWarnings("serial")
public class StoreEntryServlet<K extends EntryKey, E extends StoredEntry<K>>
    extends ServletWithMetricsBase
{
    /*
    /**********************************************************************
    /* Helper objects
    /**********************************************************************
     */

    protected final SharedServiceStuff _stuff;
//    protected final ServiceConfig _serviceConfig;
    
    protected final StoreHandler<K,E,?> _storeHandler;

    protected final ObjectWriter _jsonWriter;

    protected final EntryKeyConverter<K> _keyConverter;

    /*
    /**********************************************************************
    /* Metrics info
    /**********************************************************************
     */

    protected final OperationMetrics _getMetrics;

    protected final OperationMetrics _putMetrics;

    protected final OperationMetrics _deleteMetrics;
    
    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */

    public StoreEntryServlet(SharedServiceStuff stuff, ClusterViewByServer clusterView,
            StoreHandler<K,E,?> storeHandler)
    {
        this(stuff, clusterView, storeHandler, false);
    }

    protected StoreEntryServlet(SharedServiceStuff stuff, ClusterViewByServer clusterView,
            StoreHandler<K,E,?> storeHandler, boolean handleRouting)
    {
        // null -> use servlet path base as-is
        super(stuff, clusterView, null);
        _stuff = stuff;
        _storeHandler = storeHandler;
        _jsonWriter = stuff.jsonWriter();
        _keyConverter = stuff.getKeyConverter();
        ServiceConfig serviceConfig = stuff.getServiceConfig();
        if (serviceConfig.metricsEnabled) {
            _getMetrics = OperationMetrics.forEntityOperation(serviceConfig, "entryGet");
            _putMetrics = OperationMetrics.forEntityOperation(serviceConfig, "entryPut");
            _deleteMetrics = OperationMetrics.forNonPayloadOperation(serviceConfig, "entryDelete");
        } else {
            _getMetrics = null;
            _putMetrics = null;
            _deleteMetrics = null;
        }
    }

    protected StoreEntryServlet(StoreEntryServlet<K,E> base,
            boolean copyMetrics)
    {
        super(base._stuff, base._clusterView, null);
        _stuff = base._stuff;
        _storeHandler = base._storeHandler;
        _jsonWriter = base._jsonWriter;
        _keyConverter = base._keyConverter;
        if (copyMetrics) {
            _getMetrics = base._getMetrics;
            _putMetrics = base._putMetrics;
            _deleteMetrics = base._deleteMetrics;
        } else {
            _getMetrics = null;
            _putMetrics = null;
            _deleteMetrics = null;
        }
    }
    
    /**
     * "Mutant factory" method used to create "routing" version of this servlet:
     * this will basically handle request locally (as t
     */
    public ServletBase createRoutingServlet() {
        return new RoutingEntryServlet<K,E>(this);
    }

    /*
    /**********************************************************************
    /* Access to metrics (AllOperationMetrics.Provider impl)
    /**********************************************************************
     */

    @Override
    public void fillOperationMetrics(AllOperationMetrics metrics)
    {
        metrics.GET = ExternalOperationMetrics.create(_getMetrics);
        metrics.PUT = ExternalOperationMetrics.create(_putMetrics);
        metrics.DELETE = ExternalOperationMetrics.create(_deleteMetrics);
        _storeHandler.augmentOperationMetrics(metrics);
    }
    
    /*
    /**********************************************************************
    /* Default implementations for key handling
    /**********************************************************************
     */

    protected K _findKey(ServletServiceRequest request, ServletServiceResponse response)
    {
        return _keyConverter.extractFromPath(request);
    }
    
    /*
    /**********************************************************************
    /* Main Verb handlers
    /**********************************************************************
     */

    @Override
    public void handleGet(ServletServiceRequest request, ServletServiceResponse response,
            OperationDiagnostics stats) throws IOException
    {
        final OperationMetrics metrics = _getMetrics;
        Context timer = (metrics == null) ? null : metrics.start();
        try {
            K key = _findKey(request, response);
            if (key != null) { // null means trouble; response has all we need
                response = _handleGet(request, response, stats, key);
            }
            response.writeOut(_jsonWriter);
        } finally {
            if (metrics != null) {
                 metrics.finish(timer, stats);
            }
        }
    }

    @Override
    public void handleHead(ServletServiceRequest request, ServletServiceResponse response,
            OperationDiagnostics stats) throws IOException
    {
        K key = _findKey(request, response);
        if (key != null) {
            response = _handleHead(request, response, stats, key);
        }
        // note: should be enough to just add headers; no content to write
    }

    // We'll allow POST as an alias to PUT
    @Override // NOTE: final since it should be aliased, not overridden
    public final void handlePost(ServletServiceRequest request, ServletServiceResponse response,
            OperationDiagnostics stats) throws IOException
    {
        handlePut(request, response, stats);
    }
    
    @Override
    public void handlePut(ServletServiceRequest request, ServletServiceResponse response,
            OperationDiagnostics stats) throws IOException
    {
        final OperationMetrics metrics = _putMetrics;
        Context timer = (metrics == null) ? null : metrics.start();

        try {
            K key = _findKey(request, response);
            if (key != null) {
                response = _handlePut(request, response, stats, key);
            }
            response.writeOut(_jsonWriter);
        } finally {
            if (metrics != null) {
                 metrics.finish(timer, stats);
            }
        }
    }

    @Override
    public void handleDelete(ServletServiceRequest request, ServletServiceResponse response,
            OperationDiagnostics stats) throws IOException
    {
        final OperationMetrics metrics = _deleteMetrics;
        Context timer = (metrics == null) ? null : metrics.start();

        try {
            K key = _findKey(request, response);
            if (key != null) {
                response = _handleDelete(request, response, stats, key);
            }
            response.writeOut(_jsonWriter);
        } finally {
            if (metrics != null) {
                metrics.finish(timer, stats);
            }
        }
    }

    /*
    /**********************************************************************
    /* Handlers for actual operations, overridable
    /**********************************************************************
     */

    protected ServletServiceResponse _handleGet(ServletServiceRequest request, ServletServiceResponse response,
            OperationDiagnostics stats, K key)
        throws IOException
    {
        response = (ServletServiceResponse) _storeHandler.getEntry(request, response, key, stats);
        _addStdHeaders(response);
        return response;
    }

    protected ServletServiceResponse _handleHead(ServletServiceRequest request, ServletServiceResponse response,
            OperationDiagnostics stats, K key)
        throws IOException
    {
        response = (ServletServiceResponse) _storeHandler.getEntryStats(request, response, key, stats);
        _addStdHeaders(response);
        return response;
    }

    protected ServletServiceResponse _handlePut(ServletServiceRequest request, ServletServiceResponse response,
            OperationDiagnostics stats, K key)
        throws IOException
    {
        response = (ServletServiceResponse) _storeHandler.putEntry(request, response, key, request.getInputStream(), stats);
        _addStdHeaders(response);
        return response;
    }

    protected ServletServiceResponse _handleDelete(ServletServiceRequest request, ServletServiceResponse response,
            OperationDiagnostics stats, K key)
        throws IOException
    {
        response = (ServletServiceResponse) _storeHandler.removeEntry(request, response, key, stats);
        _addStdHeaders(response);
        return response;
    }    
}
