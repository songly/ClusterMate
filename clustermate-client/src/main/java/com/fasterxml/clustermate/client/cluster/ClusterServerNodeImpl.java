package com.fasterxml.clustermate.client.cluster;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.clustermate.api.EntryKey;
import com.fasterxml.clustermate.api.KeyHash;
import com.fasterxml.clustermate.api.KeyRange;
import com.fasterxml.clustermate.api.RequestPath;
import com.fasterxml.clustermate.api.RequestPathBuilder;
import com.fasterxml.clustermate.client.ClusterServerNode;
import com.fasterxml.clustermate.client.EntryAccessors;
import com.fasterxml.clustermate.client.NetworkClient;
import com.fasterxml.clustermate.client.call.ContentDeleter;
import com.fasterxml.clustermate.client.call.ContentGetter;
import com.fasterxml.clustermate.client.call.ContentHeader;
import com.fasterxml.clustermate.client.call.ContentPutter;
import com.fasterxml.clustermate.client.call.EntryInspector;
import com.fasterxml.clustermate.client.call.EntryLister;
import com.fasterxml.storemate.shared.IpAndPort;

/**
 * Value class used by {@link NetworkClient} to keep track of state
 * of a single cluster node.
 * Instances are mutable to a degree, and properly synchronized to allow
 * thread-safe use.
 */
public class ClusterServerNodeImpl
    implements ClusterServerNode
{
    /**
     * Address (ip number and port) used for communicating with the
     * node. Note that this is resolved end point in case server
     * node has been configured to use "localhost" (resolution can
     * be done since client has remote address for starting points)
     */
    private final IpAndPort _address;

    /**
     * Reference to the root path of the server node this object represents;
     * used for constructing references to item entry points and node
     * status accessor.
     */
    private final RequestPath _pathBase;
    
    private KeyRange _activeRange;
    private KeyRange _passiveRange;
    private KeyRange _totalRange;
    
    protected final AtomicBoolean _disabled = new AtomicBoolean(false);

    /**
     * Time when last request was sent specifically for this server node
     * (i.e. not updated when we get indirect updates)
     */
    private final AtomicLong _lastRequestSent = new AtomicLong(0L);

    /**
     * Time when last request was sent specifically from this server node
     * (i.e. not updated when we get indirect updates)
     */
    private final AtomicLong _lastResponseReceived = new AtomicLong(0L);

    /**
     * Timestamp of last update for information regarding this node; regardless
     * of whether directly or indirectly.
     */
    private long _lastNodeUpdateFetched = 0L;

    /**
     * Timestamp of last version of cluster update from this server node
     * (i.e. not applicable for indirect updates)
     */
    private long _lastClusterUpdateFetched = 0L;

    /**
     * Timestamp of last version of cluster update that this server node
     * might have; received indirectly via one of GET, PUT or DELETE
     * operations.
     */
    private final AtomicLong _lastClusterUpdateAvailable = new AtomicLong(1L);

    /*
    /**********************************************************************
    /* Entry accessor handling
    /**********************************************************************
     */
    
    protected final ContentPutter<?> _entryPutter;
    protected final ContentGetter<?> _entryGetter;
    protected final ContentHeader<?> _entryHeader;
    protected final ContentDeleter<?> _entryDeleter;
    protected final EntryLister<?> _entryLister;
    protected final EntryInspector<?> _entryInspector;
    
    /*
    /**********************************************************************
    /* Instance creation
    /**********************************************************************
     */

    public ClusterServerNodeImpl(RequestPath pathBase,
            IpAndPort address, KeyRange activeRange, KeyRange passiveRange,
            EntryAccessors<?> entryAccessors)
    {
        _pathBase = pathBase;
        _address = address;
        _activeRange = activeRange;
        _passiveRange = passiveRange;
        _totalRange = _activeRange.union(_passiveRange);

        _entryPutter = entryAccessors.entryPutter(this);
        _entryGetter = entryAccessors.entryGetter(this);
        _entryHeader = entryAccessors.entryHeader(this);
        _entryDeleter = entryAccessors.entryDeleter(this);
        _entryLister = entryAccessors.entryLister(this);
        _entryInspector = entryAccessors.entryInspector(this);
    }

    // only for test usage
    private ClusterServerNodeImpl(RequestPath pathBase,
            IpAndPort address, KeyRange activeRange, KeyRange passiveRange)
    {
        _pathBase = pathBase;
        _address = address;
        _activeRange = activeRange;
        _passiveRange = passiveRange;
        _totalRange = _activeRange.union(_passiveRange);

        _entryPutter = null;
        _entryGetter = null;
        _entryHeader = null;
        _entryDeleter = null;
        _entryLister = null;
        _entryInspector = null;
    }
    
    protected static ClusterServerNodeImpl forTesting(KeyRange range) {
        return forTesting(range, range);
    }

    protected static ClusterServerNodeImpl forTesting(KeyRange rangeActive, KeyRange rangePassive) {
        return new ClusterServerNodeImpl(null, // client not needed for kinds of tests
        		new IpAndPort("localhost:"+rangeActive.getStart()),
                rangeActive, rangePassive);
    }

    /*
    /**********************************************************************
    /* Mutations
    /**********************************************************************
     */
    
    /**
     * @return True if ranges changed
     */
    public boolean updateRanges(KeyRange activeRange, KeyRange passiveRange)
    {
        if (_activeRange.equals(activeRange) && _passiveRange.equals(passiveRange)) {
            return false;
        }
        _activeRange = activeRange;
        _passiveRange = passiveRange;
        _totalRange = activeRange.union(passiveRange);
        return true;
    }

    /**
     * @return True if state changed
     */
    public boolean updateDisabled(boolean state) {
        boolean old = _disabled.getAndSet(state);
        return (old != state);
    }

    /**
     * Method called to update cluster info timestamps, based on header
     * received during regular CRUD operations.
     * 
     * @return True if state changed
     */
    public boolean updateLastClusterUpdateAvailable(long requestTime, long responseTime,
            long timestamp)
    {
        if (_lastResponseReceived.get() < responseTime) {
            _lastRequestSent.set(requestTime);
            _lastResponseReceived.set(responseTime);
            long old = _lastClusterUpdateAvailable.getAndSet(timestamp);
            return (old != timestamp);
        }
        return false;
    }
    
    public void setLastRequestSent(long timestamp) {
        _lastRequestSent.set(timestamp);
    }

    public void setLastResponseReceived(long timestamp) {
        _lastResponseReceived.set(timestamp);
    }

    public void setLastNodeUpdateFetched(long timestamp) {
        _lastNodeUpdateFetched = timestamp;
    }

    public void setLastClusterUpdateFetched(long timestamp) {
        _lastClusterUpdateFetched = timestamp;
    }

    public void setLastClusterUpdateAvailable(long timestamp) {
        _lastClusterUpdateAvailable.set(timestamp);
    }

    /*
    /**********************************************************************
    /* ReadOnlyServerNodeState implementation (public accessors)
    /**********************************************************************
     */

    @Override
    public IpAndPort getAddress() { return _address; }

    @Override
    public KeyRange getActiveRange() { return _activeRange; }

    @Override
    public KeyRange getPassiveRange() { return _passiveRange; }
    @Override
    public KeyRange getTotalRange() { return _totalRange; }

    @Override
    public boolean isDisabled() { return _disabled.get(); }

    @Override
    public long getLastRequestSent() { return _lastRequestSent.get(); }
    @Override
    public long getLastResponseReceived() { return _lastResponseReceived.get(); }

    @Override
    public long getLastNodeUpdateFetched() { return _lastNodeUpdateFetched; }
    @Override
    public long getLastClusterUpdateFetched() { return _lastClusterUpdateFetched; }

    @Override
    public long getLastClusterUpdateAvailable() { return _lastClusterUpdateAvailable.get(); }
    
    /*
    /**********************************************************************
    /* Advanced accessors
    /**********************************************************************
     */

    /**
     * Method for calculating distance metrics to use for sorting server nodes
     * based on distance. In addition to basic clock-wise distance from start
     * point we also consider disabled nodes to be further away than any
     * enabled nodes; this is done by adding full length of key space to basic
     * clock-wise distance.         * 

     */
    public int calculateSortingDistance(KeyHash keyHash)
    {
        /* Note: while we include things based on passive range, distance
         * should be based on active range; this to make passive range
         * more useful (can catch up with larger passive range; then enable
         * larger range once caught up)
         */
        KeyRange range = getActiveRange();
        int distance = range.clockwiseDistance(keyHash);
        if (isDisabled()) {
            distance += range.getKeyspace().getLength();
        }
        return distance;
    }

    /*
    /**********************************************************************
    /* Call accessors, paths etc
    /**********************************************************************
     */
    
    /*
    @SuppressWarnings("unchecked")
    @Override
    public <P extends RequestPathBuilder> P resourceEndpoint() {
        return (P) _pathBase.builder();
 
    }
    */

    @Override
    public <B extends RequestPathBuilder<B>> B rootPath() {
        return _pathBase.builder();
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public <K extends EntryKey> ContentPutter<K> entryPutter() {
        return (ContentPutter<K>) _entryPutter;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <K extends EntryKey> ContentGetter<K> entryGetter() {
        return (ContentGetter<K>) _entryGetter;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <K extends EntryKey> ContentHeader<K> entryHeader() {
        return (ContentHeader<K>) _entryHeader;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <K extends EntryKey> ContentDeleter<K> entryDeleter() {
        return (ContentDeleter<K>) _entryDeleter;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <K extends EntryKey> EntryLister<K> entryLister() {
        return (EntryLister<K>) _entryLister;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <K extends EntryKey> EntryInspector<K> entryInspector() {
        return (EntryInspector<K>) _entryInspector;
    }
}
