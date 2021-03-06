package com.fasterxml.clustermate.client.operation;

import java.util.List;

import com.fasterxml.clustermate.client.ClusterServerNode;
import com.fasterxml.clustermate.client.call.ListCallResult;
import com.fasterxml.storemate.shared.StorableKey;

/**
 * Result value produced by {@link StoreEntryLister}, contains information
 * about success of individual call, as well as sequence of listed
 * entries in case of successful call.
 */
public class ListOperationResult<T> extends ReadOperationResult<ListOperationResult<T>>
{
    protected List<T> _items;
    
    protected StorableKey _lastSeen;
    
    public ListOperationResult(OperationConfig config)
    {
        super(config);
    }

    public ListOperationResult<T> setItems(ClusterServerNode server, ListCallResult<T> result)
    {
        if (_server != null) {
            throw new IllegalStateException("Already received successful response from "+_server+"; trying to override with "+server);
        }
        _server = server;
        _items = result.getItems();
        _lastSeen = result.getLastSeen();
        return this;
    }

    public List<T> getItems() { return _items; }
    public StorableKey getLastSeen() { return _lastSeen; }
}
