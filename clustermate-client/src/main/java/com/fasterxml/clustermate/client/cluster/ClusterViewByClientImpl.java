package com.fasterxml.clustermate.client.cluster;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import com.fasterxml.clustermate.api.EntryKeyConverter;
import com.fasterxml.clustermate.api.KeyHash;
import com.fasterxml.clustermate.api.KeySpace;
import com.fasterxml.clustermate.api.NodeState;
import com.fasterxml.clustermate.api.RequestPath;
import com.fasterxml.clustermate.api.RequestPathBuilder;
import com.fasterxml.clustermate.client.ClusterViewByClient;
import com.fasterxml.clustermate.client.NetworkClient;
import com.fasterxml.clustermate.client.NodesForKey;
import com.fasterxml.clustermate.client.cluster.ClusterServerNodeImpl;
import com.fasterxml.clustermate.client.impl.StoreClientConfig;
import com.fasterxml.storemate.shared.EntryKey;
import com.fasterxml.storemate.shared.IpAndPort;

/**
 * Class that encapsulates view of the cluster, as whole, from
 * client perspective.
 */
public class ClusterViewByClientImpl<K extends EntryKey>
    extends ClusterViewByClient<K>
{
	/**
	 * Reference to the underlying network client, so that we can
	 * construct paths for requests.
	 */
	private final NetworkClient<K> _client;

	private final KeySpace _keyspace;
    
	private final EntryKeyConverter<K> _keyConverter;
    
	private final Map<IpAndPort, ClusterServerNodeImpl> _nodes = new LinkedHashMap<IpAndPort, ClusterServerNodeImpl>();

    /**
     * Since we will need to iterate over server node 
     */
    private AtomicReference<ClusterServerNodeImpl[]> _states = new AtomicReference<ClusterServerNodeImpl[]>(
            new ClusterServerNodeImpl[0]);

    /**
     * Monotonically increasing counter we use for lazily constructing
     * and invalidating routing information, mapping from key hashes
     * to {@link NodesForKey} objects.
     */
    private final AtomicInteger _version = new AtomicInteger(1);

    private final AtomicReferenceArray<NodesForKey> _routing;

    private final EntryAccessors<K> _entryAccessors;

    /*
    /**********************************************************************
    /* Construction
    /**********************************************************************
     */
    
    /**
     * Path segments for the root path
     */
    private final String[] _rootPathSegments;

    public ClusterViewByClientImpl(StoreClientConfig<K,?> storeConfig,
            NetworkClient<K> client, KeySpace keyspace)
    {
        _keyspace = keyspace;
        _routing = new AtomicReferenceArray<NodesForKey>(keyspace.getLength());
        if (client == null) {
            _client = null;
            _keyConverter = null;
            _entryAccessors = null;
        } else {
            _client = client;
            _keyConverter = client.getKeyConverter();
            _entryAccessors = client.getEntryAccessors();
        }
        if (storeConfig == null) {
            _rootPathSegments = new String[0];
        } else {
            _rootPathSegments = storeConfig.getBasePath();
        }
    }
    
    public static <K extends EntryKey> ClusterViewByClientImpl<K> forTesting(KeySpace keyspace)
    {
        return new ClusterViewByClientImpl<K>(null, null, keyspace);
    }

    /*
    protected String[] _splitPath(String base)
    {
        base = base.trim();
        if (base.startsWith("/")) {
            base = base.substring(1);
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length()-1);
        }
        if (base.length() == 0) {
            return new String[0];
        }
        return base.split("/");
    }
    */
    
    /*
    /**********************************************************************
    /* Accessors
    /**********************************************************************
     */

    @Override
    public int getServerCount() {
        return _nodes.size();
    }

    @Override
    public boolean isFullyAvailable() {
        return getCoverage() == _keyspace.getLength();
    }

    @Override
    public int getCoverage() {
        return _getCoverage(_states());
    }

    // separate method for testing:
    protected int _getCoverage(ClusterServerNodeImpl[] states)
    {
        BitSet slices = new BitSet(_keyspace.getLength());
        for (ClusterServerNodeImpl state : states) {
            state.getTotalRange().fill(slices);
        }
        return slices.cardinality();
    }

    @Override
    public NodesForKey getNodesFor(K key)
    {
        int fullHash = _keyConverter.routingHashFor(key);
        KeyHash hash = new KeyHash(fullHash, _keyspace.getLength());
        int currVersion = _version.get();
        int modulo = hash.getModuloHash();
        NodesForKey nodes = _routing.get(modulo);
        // fast (and common) case: pre-calculated, valid info exists:
        if (nodes != null && nodes.version() == currVersion) {
            return nodes;
        }
        NodesForKey newNodes = _calculateNodes(currVersion, hash);
        _routing.compareAndSet(modulo, nodes, newNodes);
        return newNodes;
    }
    
    /*
    /**********************************************************************
    /* Updating state
    /**********************************************************************
     */
    
    /**
     * Method called to add information directly related to node that served
     * the request.
     */
    public synchronized void updateDirectState(IpAndPort byNode, NodeState stateInfo,
            long requestTime, long responseTime,
            long clusterInfoVersion)
    {
        ClusterServerNodeImpl localState = _nodes.get(byNode);
        if (localState == null) { // new info 
            localState = new ClusterServerNodeImpl(_rootPathFor(byNode),
            		byNode, stateInfo.getRangeActive(), stateInfo.getRangePassive(),
                    _entryAccessors);
            _addNode(byNode, localState);
        }
        boolean needInvalidate = localState.updateRanges(stateInfo.getRangeActive(),
                stateInfo.getRangePassive());
        if (localState.updateDisabled(stateInfo.isDisabled())) {
            needInvalidate = true;
        }
        if (needInvalidate) {
            invalidateRouting();
        }
        localState.setLastRequestSent(requestTime);
        localState.setLastResponseReceived(responseTime);
        localState.setLastNodeUpdateFetched(stateInfo.getLastUpdated());
        localState.setLastClusterUpdateFetched(clusterInfoVersion);
    }
    
    /**
     * Method called to add information obtained indirectly; i.e. "gossip".
     */
    public synchronized void updateIndirectState(IpAndPort byNode, NodeState stateInfo)
    {
        // First: ensure references are properly resolved (eliminate "localhost" if need be):
        IpAndPort ip = stateInfo.getAddress();
        if (ip.isLocalReference()) {
            ip = byNode.withPort(ip.getPort());
        }
        final long nodeInfoTimestamp = stateInfo.getLastUpdated();
        // otherwise pretty simple:
        ClusterServerNodeImpl state = _nodes.get(ip);
        if (state == null) { // new info 
            state = new ClusterServerNodeImpl(_rootPathFor(ip),
            		ip, stateInfo.getRangeActive(), stateInfo.getRangePassive(),
                    _entryAccessors);
            _addNode(ip, state);
        } else {
            // quick check to ensure info we get is newer: if not, skip
            if (nodeInfoTimestamp <= state.getLastNodeUpdateFetched()) {
                return;
            }
        }
        state.setLastNodeUpdateFetched(nodeInfoTimestamp);
        boolean needInvalidate = state.updateRanges(stateInfo.getRangeActive(),
                stateInfo.getRangePassive());
        if (state.updateDisabled(stateInfo.isDisabled())) {
            needInvalidate = true;
        }
        if (needInvalidate) {
            invalidateRouting();
        }
    }
    
    /*
    /**********************************************************************
    /* Internal methods
    /**********************************************************************
     */

    protected RequestPath _rootPathFor(IpAndPort serverAddress)
    {
        RequestPathBuilder builder = _client.pathBuilder(serverAddress);
        for (String component : _rootPathSegments) {
            builder = builder.addPathSegment(component);
        }
        return builder.build();
    }
    
    private void _addNode(IpAndPort key, ClusterServerNodeImpl state)
    {
        _nodes.put(key, state);
        _states.set(_nodes.values().toArray(new ClusterServerNodeImpl[_nodes.size()]));
    }
    
    private ClusterServerNodeImpl[] _states() {
        return _states.get();
    }

    /**
     * Method called when server node state information changes in a way that
     * may affect routing of requests.
     */
    private void invalidateRouting()
    {
        _version.addAndGet(1);
    }

    /**
     * Helper method that actually calculates routing information for specific
     * part of key space.
     */
    protected NodesForKey _calculateNodes(int version, KeyHash keyHash) {
        return _calculateNodes(version, keyHash, _states());
    }

    // separate method for testing
    protected NodesForKey _calculateNodes(int version, KeyHash keyHash,
            ClusterServerNodeImpl[] allNodes)
    {
        final int allCount = allNodes.length;
        // First: simply collect all applicable nodes:
        ArrayList<ClusterServerNodeImpl> appl = new ArrayList<ClusterServerNodeImpl>();
        for (int i = 0; i < allCount; ++i) {
            ClusterServerNodeImpl state = allNodes[i];
            if (state.getTotalRange().contains(keyHash)) {
                appl.add(state);
            }
        }
        return _sortNodes(version, keyHash, appl);
    }

    protected NodesForKey _sortNodes(int version, KeyHash keyHash,
            Collection<ClusterServerNodeImpl> appl)
    {
        // edge case: no matching
        if (appl.isEmpty()) {
            return NodesForKey.empty(version);
        }
        // otherwise need to sort
        ClusterServerNodeImpl[] matching = appl.toArray(new ClusterServerNodeImpl[appl.size()]);
        Arrays.sort(matching, 0, appl.size(), new NodePriorityComparator(keyHash));
        return new NodesForKey(version, matching);
    }
        
    /*
    /**********************************************************************
    /* Helper classes
    /**********************************************************************
     */

    /**
     * Comparator that orders server in decreasing priority, that is, starts with
     * the closest enabled match, ending with disabled entries.
     */
    private final static class NodePriorityComparator implements Comparator<ClusterServerNodeImpl>
    {
        private final KeyHash _keyHash;

        public NodePriorityComparator(KeyHash keyHash) {
            _keyHash = keyHash;
        }
        
        @Override
        public int compare(ClusterServerNodeImpl node1, ClusterServerNodeImpl node2)
        {
            return node1.calculateSortingDistance(_keyHash) - node2.calculateSortingDistance(_keyHash);
        }
    }
}
