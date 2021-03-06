package com.fasterxml.clustermate.client.ahc;

import com.fasterxml.clustermate.api.ClusterMateConstants;
import com.fasterxml.clustermate.client.ClusterServerNode;
import com.fasterxml.clustermate.client.call.CallFailure;
import com.fasterxml.clustermate.client.call.ReadCallResult;
import com.ning.http.client.HttpResponseHeaders;

/**
 * Container for results of a single GET call to a server node.
 * Note that at most one of '_fail' and '_result' can be non-null; however,
 * it is possible for both to be null: this occurs in cases where
 * communication to server(s) succeeds, but no content was found
 * (either 404, or deleted content).
 */
public final class AHCReadCallResult<T> extends ReadCallResult<T>
{
    protected HttpResponseHeaders _headers;

    /*
    /**********************************************************************
    /* Construction, initialization
    /**********************************************************************
     */

    public AHCReadCallResult(ClusterServerNode server, T result) {
        super(server, result);
    }

    public AHCReadCallResult(ClusterServerNode server, int failStatus) {
        super(server, failStatus);
    }

    public AHCReadCallResult(CallFailure fail) {
        super(fail);
    }

    public static <T> AHCReadCallResult<T> notFound(ClusterServerNode server) {
        return new AHCReadCallResult<T>(server, ClusterMateConstants.HTTP_STATUS_NOT_FOUND);
    }

    public void setHeaders(HttpResponseHeaders h) {
        _headers = h;
    }

    /*
    /**********************************************************************
    /* Accessors
    /**********************************************************************
     */

    @Override
    public String getHeaderValue(String key)
    {
        HttpResponseHeaders h = _headers;
        return (h == null) ? null : h.getHeaders().getFirstValue(key);
    }
}
