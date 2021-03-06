package com.fasterxml.clustermate.client.operation;

import java.util.*;

import com.fasterxml.clustermate.api.EntryKey;
import com.fasterxml.clustermate.api.ListItemType;
import com.fasterxml.clustermate.api.msg.ListResponse;
import com.fasterxml.clustermate.client.*;
import com.fasterxml.clustermate.client.call.CallFailure;
import com.fasterxml.clustermate.client.call.ListCallResult;
import com.fasterxml.clustermate.client.util.ContentConverter;
import com.fasterxml.storemate.shared.StorableKey;

/**
 * Value class that is used as result type for content list operation.
 * Unlike simple result classes like {@link GetOperationResult}, no calls
 * are yet made when this object is constructed; rather, it is returned
 * to be used for incrementally accessing contents to list.
 * This is necessary as list operations may return large number of entries,
 * and each individual operation can only return up to certain number of
 * entries.
 *
 * @param <K> Type of keys used for ClusterMate-based system
 * @param <T> Type of list items to return
 */
public class StoreEntryLister<K extends EntryKey,T>
{
    public final static int DEFAULT_MAX_ENTRIES = 100;
    
    protected final StoreClientConfig<K,?> _clientConfig;

    protected final ClusterViewByClient<K> _cluster;
    
    /**
     * Prefix of entries to list.
     */
    protected final K _prefix;
    
    /**
     * Type of items of the result list.
     */
    protected final ListItemType _itemType;

    protected final ContentConverter<ListResponse<T>> _converter;

    /**
     * Id of the last entry that was iterated over.
     */
    protected K _lastSeen;
    
    public StoreEntryLister(StoreClientConfig<K,?> config, ClusterViewByClient<K> cluster,
            K prefix, ListItemType itemType, ContentConverter<ListResponse<T>> converter,
            K initialLastSeen)
    {
        _clientConfig = config;
        _cluster = cluster;
        _prefix = prefix;
        _itemType = itemType;
        _converter = converter;
        _lastSeen = initialLastSeen;
    }

    public ListOperationResult<T> listMore() throws InterruptedException
    {
        return listMore(DEFAULT_MAX_ENTRIES);
    }

    public ListOperationResult<T> listMore(int maxToList) throws InterruptedException
    {
        ListOperationResult<T> result = _listMore(maxToList);
        if (result != null) {
            StorableKey raw = result.getLastSeen();
            if (raw != null) { // should this error out?
                _lastSeen = _clientConfig.getKeyConverter().rawToEntryKey(raw);
            }
        }
        return result;
    }

    protected ListOperationResult<T> _listMore(int maxToList) throws InterruptedException
    {
        final long startTime = System.currentTimeMillis();

        // First things first: find Server nodes to talk to:
        NodesForKey nodes = _cluster.getNodesFor(_prefix);
        // then result
        ListOperationResult<T> result = new ListOperationResult<T>(_clientConfig.getOperationConfig());
        
        // One sanity check: if not enough server nodes to talk to, can't succeed...
        int nodeCount = nodes.size();
        if (nodeCount < 1) {
            return result; // or Exception?
        }
        
        // Then figure out how long we have for the whole operation; use same timeout as GET
        final long endOfTime = startTime + _clientConfig.getOperationConfig().getGetOperationTimeoutMsecs();
        final long lastValidTime = endOfTime - _clientConfig.getCallConfig().getMinimumTimeoutMsecs();

        // Ok: first round; try List from every enabled store (or, if only one try, all)
        final boolean noRetries = !allowRetries();
        List<NodeFailure> retries = null;
        for (int i = 0; i < nodeCount; ++i) {
            ClusterServerNode server = nodes.node(i);
            if (!server.isDisabled() || noRetries) {
                ListCallResult<T> gotten = server.entryLister().tryList(_clientConfig.getCallConfig(), endOfTime,
                        _prefix, _lastSeen, _itemType, maxToList, _converter);
                if (gotten.failed()) {
                    CallFailure fail = gotten.getFailure();
                    if (fail.isRetriable()) {
                        retries = _add(retries, new NodeFailure(server, fail));
                    } else {
                        result.withFailed(new NodeFailure(server, fail));
                    }
                    continue;
                }
                return result.setItems(server, gotten);
            }
        }
        if (noRetries) { // if no retries, bail out quickly
            return result.withFailed(retries);
        }
        
        final long secondRoundStart = System.currentTimeMillis();
        // Do we need any delay in between?
        _doDelay(startTime, secondRoundStart, endOfTime);
        
        // Otherwise: go over retry list first, and if that's not enough, try disabled
        if (retries == null) {
            retries = new LinkedList<NodeFailure>();
        } else {
            Iterator<NodeFailure> it = retries.iterator();
            while (it.hasNext()) {
                NodeFailure retry = it.next();
                ClusterServerNode server = (ClusterServerNode) retry.getServer();
                ListCallResult<T> gotten = server.entryLister().tryList(_clientConfig.getCallConfig(), endOfTime,
                        _prefix, _lastSeen, _itemType, maxToList, _converter);
                if (gotten.succeeded()) {
                    return result.withFailed(retries).setItems(server, gotten);
                }
                CallFailure fail = gotten.getFailure();
                retry.addFailure(fail);
                if (!fail.isRetriable()) {
                    result.withFailed(retry);
                    it.remove();
                }
            }
        }
        // if no success, add disabled nodes in the mix
        for (int i = 0; i < nodeCount; ++i) {
            ClusterServerNode server = nodes.node(i);
            if (server.isDisabled()) {
                if (System.currentTimeMillis() >= lastValidTime) {
                    return result.withFailed(retries);
                }
                ListCallResult<T> gotten = server.entryLister().tryList(_clientConfig.getCallConfig(), endOfTime,
                        _prefix, _lastSeen, _itemType, maxToList, _converter);
                if (gotten.succeeded()) {
                    return result.withFailed(retries).setItems(server, gotten);
                }
                CallFailure fail = gotten.getFailure();
                if (fail.isRetriable()) {
                    retries.add(new NodeFailure(server, fail));
                } else {
                    result.withFailed(new NodeFailure(server, fail));
                }
            }
        }

        long prevStartTime = secondRoundStart;
        for (int i = 1; (i <= StoreClientConfig.MAX_RETRIES_FOR_GET) && !retries.isEmpty(); ++i) {
            final long currStartTime = System.currentTimeMillis();
            _doDelay(prevStartTime, currStartTime, endOfTime);
            Iterator<NodeFailure> it = retries.iterator();
            while (it.hasNext()) {
                if (System.currentTimeMillis() >= lastValidTime) {
                    return result.withFailed(retries);
                }
                NodeFailure retry = it.next();
                ClusterServerNode server = (ClusterServerNode) retry.getServer();
                ListCallResult<T> gotten = server.entryLister().tryList(_clientConfig.getCallConfig(), endOfTime,
                        _prefix, _lastSeen, _itemType, maxToList, _converter);
                if (gotten.succeeded()) {
                    return result.withFailed(retries).setItems(server, gotten);
                }
                CallFailure fail = gotten.getFailure();
                retry.addFailure(fail);
                if (!fail.isRetriable()) {
                    result.withFailed(retry);
                    it.remove();
                }
            }
        }
        // we are all done and this'll be a failure...
        return result.withFailed(retries);
    }

    /*
    /**********************************************************************
    /* Helper methods
    /**********************************************************************
     */

    protected boolean allowRetries() {
        return _clientConfig.getOperationConfig().getAllowRetries();
    }
    
    protected <T0> List<T0> _add(List<T0> list, T0 entry)
    {
        if (list == null) {
            list = new LinkedList<T0>();
        }
        list.add(entry);
        return list;
    }
    
    protected void _doDelay(long startTime, long currTime, long endTime)
        throws InterruptedException
    {
        long timeSpent = currTime - startTime;
        // only add delay if we have had quick failures (signaling overload)
        if (timeSpent < 1000L) {
            long timeLeft = endTime - currTime;
            // also, only wait if we still have some time; and then modest amount (250 mecs)
            if (timeLeft >= (4 * StoreClientConfig.DELAY_BETWEEN_RETRY_ROUNDS_MSECS)) {
                Thread.sleep(StoreClientConfig.DELAY_BETWEEN_RETRY_ROUNDS_MSECS);
            }
        }
    }
}
