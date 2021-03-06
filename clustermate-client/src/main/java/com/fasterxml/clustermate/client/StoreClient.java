package com.fasterxml.clustermate.client;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.clustermate.api.*;
import com.fasterxml.clustermate.api.msg.ItemInfo;
import com.fasterxml.clustermate.api.msg.ListItem;
import com.fasterxml.clustermate.api.msg.ListResponse;
import com.fasterxml.clustermate.client.call.*;
import com.fasterxml.clustermate.client.operation.*;
import com.fasterxml.clustermate.client.util.ContentConverter;
import com.fasterxml.clustermate.client.util.GenericContentConverter;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.storemate.shared.ByteRange;
import com.fasterxml.storemate.shared.util.ByteAggregator;

/**
 * Client used for accessing temporary store service.
 */
public abstract class StoreClient<K extends EntryKey,
    CONFIG extends StoreClientConfig<K, CONFIG>,
    I extends ItemInfo
>
    extends Loggable
{
    /**
     * Let's use Chunked Transfer-Encoding for larger payloads; cut-off
     * point is arbitrary, choose nice round number of 64k.
     */
    protected final static long MIN_LENGTH_FOR_CHUNKED = 64 * 1024;

    /*
    /**********************************************************************
    /* Configuration
    /**********************************************************************
     */

    protected final CONFIG _config;
    
    /*
    /**********************************************************************
    /* Helper objects
    /**********************************************************************
     */

    protected final NetworkClient<K> _httpClient;

    protected final ClusterStatusAccessor _statusAccessor;

    protected EntryKeyConverter<K> _keyConverter;
    
    protected final EnumMap<ListItemType, GenericContentConverter<?>> _listReaders;

    protected final ContentConverter<I> _infoConverter;

    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */

    /**
     * Processing thread used for maintaining cluster state information.
     */
    protected Thread _thread;

    protected final AtomicBoolean _stopRequested;

    /*
    /**********************************************************************
    /* State
    /**********************************************************************
     */
    
    protected final ClusterViewByClient<K> _clusterView;

    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */

    /**
     * @param config Client configuration to use
     * @param listItemType Concrete {@link ListItem} type for implementation
     */
    protected StoreClient(CONFIG config, Class<? extends ListItem> listItemType,
            ClusterStatusAccessor statusAccessor, ClusterViewByClient<K> clusterView,
            NetworkClient<K> httpClientImpl, ContentConverter<I> infoConverter)
    {
        super(StoreClient.class);
        _config = config;
        _keyConverter = config.getKeyConverter();
        _httpClient = httpClientImpl;
        
        _statusAccessor = statusAccessor;
        _clusterView = clusterView;

        _listReaders = new EnumMap<ListItemType, GenericContentConverter<?>>(ListItemType.class);
        ObjectMapper mapper = config.getJsonMapper();
        _listReaders.put(ListItemType.ids, new GenericContentConverter<ListResponse.IdListResponse>(mapper, ListResponse.IdListResponse.class));
        _listReaders.put(ListItemType.names, new GenericContentConverter<ListResponse.NameListResponse>(mapper, ListResponse.NameListResponse.class));
        _listReaders.put(ListItemType.minimalEntries,
                new GenericContentConverter<ListResponse.MinimalItemListResponse>(mapper,
                        ListResponse.MinimalItemListResponse.class));
        /* "full" ListItemType is trickier, since we need to use generic type definition
         * to parameterize appropriate Full 
         */
        // Ugh. Jackson's type resolution fails if using "FullItemListResponse"... need to work around it
        JavaType fullResponseType = config.getJsonMapper().getTypeFactory().constructParametricType(
                ListResponse.class, listItemType);
        _listReaders.put(ListItemType.fullEntries,
                new GenericContentConverter<ListResponse<ListItem>>(mapper, fullResponseType));

        _thread = null;
        _stopRequested = new AtomicBoolean(false);
        _infoConverter = infoConverter;
    }

    /**
     * Copy-constructor used when creating differently configured new instances
     */
    protected StoreClient(StoreClient<K,CONFIG,I> base, CONFIG config)
    {
        super(base);
        _config = config;
        _keyConverter = base._keyConverter;
        _httpClient = base._httpClient;
        
        _statusAccessor = base._statusAccessor;
        _clusterView = base._clusterView;
        _infoConverter = base._infoConverter;

        _listReaders = base._listReaders;

        _thread = base._thread;
        _stopRequested = base._stopRequested;
    }
    
    /**
     * Method called by {@link StoreClientBootstrapper} once bootstrapping
     * is complete to some degree.
     */
    protected synchronized void start()
    {
        if (_thread != null) {
            throw new IllegalStateException("Trying to call start() more than once");
        }
        _stopRequested.set(false);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                updateLoop();
            }
        });
        // make it daemon, so as not to block shutdowns
        t.setDaemon(runAsDaemon());
        _thread = t;
        t.start();
    }
    
    /**
     * Method that must be called to stop processing thread client has
     */
    public void stop()
    {
        _stopRequested.set(true);
        Thread t = _thread;
        if (t != null) {
            t.interrupt();
        }
        // Should we ask HTTP Client to shut down here, or within thread?
        _httpClient.shutdown();
//        _blockingHttpClient.getConnectionManager().shutdown();
    }

    public synchronized boolean isRunning() {
        return (_thread != null);
    }

    public boolean hasStopBeenRequested() {
        return _stopRequested.get();
    }
    
    /*
    /**********************************************************************
    /* Simple accessors
    /**********************************************************************
     */

    public CONFIG getConfig() {
        return _config;
    }

    public OperationConfig getOperationConfig() {
        return _config.getOperationConfig();
    }
    
    /**
     * Accessor for getting full cluster information
     */
    public ClusterViewByClient<K> getCluster() {
        return _clusterView;
    }

    public EntryKeyConverter<K> getKeyConverter() {
        return _keyConverter;
    }

    /**
     * Overridable internal accessor to define whether background thread used
     * for updates is to run as daemon or not; usually it should (and by default
     * does)
     */
    public boolean runAsDaemon() {
        return true;
    }
    
    /*
    /**********************************************************************
    /* Main update loop used for keeping up to date with Cluster Status
    /**********************************************************************
     */

    /**
     * Method that keeps on calling {@link #updateOnce} until Client is
     * requested to stop its operation.
     */
    protected void updateLoop()
    {
        try {
            while (!_stopRequested.get()) {
                final long startTime = System.currentTimeMillis();
                // throttle amount of work...
                final long nextCall = startTime + StoreClientConfig.MIN_DELAY_BETWEEN_STATUS_CALLS_MSECS;
                try {
                    updateOnce();
                } catch (Exception e) {
                    logWarn(e, "Problem during Client updateLoop: "+e.getMessage());
                }
                long delay = nextCall - System.currentTimeMillis();
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) { }
                }
            }
        } finally {
            // after done, let's clear thread reference...
            synchronized (this) {
                _thread = null;
            }
        }
    }

    /**
     * Method that tries to update state of cluster by making a single call
     * to "most deserving" server node.
     */
    protected void updateOnce() throws Exception
    {
        // !!! TODO:
        // (1) Figure out which server node to call (least recently called one with updates etc)
    }

    /*
    /**********************************************************************
    /* Client API: convenience wrappers for PUTs
    /**********************************************************************
     */

    /**
     * Convenience method for PUTting specified static content;
     * may be used if content need not be streamed from other sources.
     */
    public final PutOperation putContent(PutCallParameters params, K key, byte[] data)
    		throws InterruptedException
    {
        return putContent(params, key, data, 0, data.length);
    }

    /**
     * Convenience method for PUTting specified static content;
     * may be used if content need not be streamed from other sources.
     */
    public final PutOperation putContent(PutCallParameters params, K key,
    		byte[] data, int dataOffset, int dataLength)
            throws InterruptedException
    {
        return putContent(params, key, PutContentProviders.forBytes(data, dataOffset, dataLength));
    }
    
    /**
     * Convenience method for PUTting contents of specified File.
     */
    public final PutOperation putContent(PutCallParameters params, K key, File file)
    		throws InterruptedException {
        return putContent(params, key, PutContentProviders.forFile(file, file.length()));
    }

    /*
    /**********************************************************************
    /* Client API: convenience wrappers for GETs
    /**********************************************************************
     */

    /**
     * Convenience method for GETting specific content and aggregating it as a
     * byte array.
     * Note that failure to perform GET operation will be signaled with
     * {@link IllegalStateException}, whereas missing content is indicated by
     * null return value.
     * 
     * @return Array of bytes returned, if content exists; null if no such content
     *    exists (never PUT, or has been DELETEd)
     */
    public byte[] getContentAsBytes(ReadCallParameters params, K key)
            throws InterruptedException
    {
        GetContentProcessorForBytes processor = new GetContentProcessorForBytes();
        GetOperationResult<ByteAggregator> result = getContent(params, key, processor);
        if (result.failed()) { // failed to contact any server
            _handleGetFailure(params, key, result);
        }
        // otherwise, we either got content, or got 404 or deletion
        ByteAggregator aggr = result.getContents();
        return (aggr == null) ? null : aggr.toByteArray();
    }

    /**
     * Convenience method for GETting specific content and storing it in specified file.
     * Note that failure to perform GET operation will be signaled with
     * {@link IllegalStateException}, whereas missing content is indicated by
     * 'false' return value
     * 
     * @return Original result file, if content exists; null if content was not found but
     *   operation succeeded (throw exception if access operation itself fails)
     */
    public final File getContentAsFile(ReadCallParameters params, K key, File resultFile)
        throws InterruptedException
    {
        GetContentProcessorForFiles processor = new GetContentProcessorForFiles(resultFile);
        GetOperationResult<File> result = getContent(params, key, processor);
        if (result.failed()) { // failed to contact any server
            _handleGetFailure(params, key, result);
        }
        // otherwise, we either got content, or got 404 or deletion -- latter means we return null:
        return result.getContents();
    }

    /**
     * Convenience method for GETting part of specified resource
     * aggregated as a byte array.
     *<p>
     * Note that failure to perform GET operation will be signaled with
     * {@link IllegalStateException}, whereas missing content is indicated by
     * null return value.
     *<p>
     * Note that when accessing ranges, content will always be return uncompressed
     * (if server compressed it, or received pre-compressed content declared with
     * compression type).
     * 
     * @param range Specified byte range to access, using offsets in uncompressed content
     * 
     * @return Array of bytes returned, if content exists; null if no such content
     *    exists (never PUT, or has been DELETEd)
     */
    public final byte[] getPartialContentAsBytes(ReadCallParameters params, K key, ByteRange range)
        throws InterruptedException
    {
        GetContentProcessorForBytes processor = new GetContentProcessorForBytes();
        GetOperationResult<ByteAggregator> result = getContent(params, key, processor, range);
        if (result.failed()) { // failed to contact any server
            _handleGetFailure(params, key, result);
        }
        // otherwise, we either got content, or got 404 or deletion
        ByteAggregator aggr = result.getContents();
        return (aggr == null) ? null : aggr.toByteArray();
    }

    /**
     * Convenience method for GETting part of specified resource
     * stored as specified file (if existing, will be overwritten).
     *<p>
     * Note that failure to perform GET operation will be signaled with
     * {@link IllegalStateException}, whereas missing content is indicated by
     * null return value.
     *<p>
     * Note that when accessing ranges, content will always be return uncompressed
     * (if server compressed it, or received pre-compressed content declared with
     * compression type).
     * 
     * @param range Specified byte range to access, using offsets in uncompressed content
     * 
     * @return Original result file, if content exists; null if content was not found but
     *   operation succeeded (throw exception if access operation itself fails)
     */
    public final File getPartialContentAsFile(ReadCallParameters params, K key, File resultFile,
    		ByteRange range) throws InterruptedException
    {
        GetContentProcessorForFiles processor = new GetContentProcessorForFiles(resultFile);
        GetOperationResult<File> result = getContent(params, key, processor, range);
        if (result.failed()) { // failed to contact any server
            _handleGetFailure(params, key, result);
        }
        // otherwise, we either got content, or got 404 or deletion -- latter means we return null:
        return result.getContents();
    }
    
    /**
     * Convenience method for making HEAD request to figure out length of
     * the resource, if one exists (and -1 if not).
     * 
     * @return Length of entry in bytes, if entry exists: -1 if no such entry
     *    exists
     */
    public final long getContentLength(ReadCallParameters params, K key)
        throws InterruptedException
    {
        HeadOperationResult result = headContent(params, key);
        if (result.failed()) { // failed to contact any server
            NodeFailure nodeFail = result.getFirstFail();
            if (nodeFail != null) {
                CallFailure callFail = nodeFail.getFirstCallFailure();
                if (callFail != null) {
                    Throwable t = callFail.getCause();
                    if (t != null) {
                        throw new IllegalStateException("Failed to HEAD resource '"+key+"': tried and failed to access "
                                +result.getFailCount()+" server nodes; first failure due to: "+t);
                    }
                }
            }
            throw new IllegalStateException("Failed to HEAD resource '"+key+"': tried and failed to access "
                    +result.getFailCount()+" server nodes; first problem: "+result.getFirstFail());
        }
        return result.getContentLength();
    }
    
    protected void _handleGetFailure(ReadCallParameters params, K key, GetOperationResult<?> result)
    {
        NodeFailure nodeFail = result.getFirstFail();
        if (nodeFail != null) {
            CallFailure callFail = nodeFail.getFirstCallFailure();
            if (callFail != null) {
                Throwable t = callFail.getCause();
                if (t != null) {
                    throw new IllegalStateException("Failed to GET resource '"+key+"': tried and failed to access "
                            +result.getFailCount()+" server nodes; first failure due to: "+t);
                }
            }
        }
        throw new IllegalStateException("Failed to GET resource '"+key+"': tried and failed to access "
                +result.getFailCount()+" server nodes; first problem: "+result.getFirstFail());
    }
    
    /*
    /**********************************************************************
    /* Client API, low-level operations: PUT
    /**********************************************************************
     */

    /**
     * Method called to PUT specified content into appropriate server nodes.
     * 
     * @return Operation object that is used to actually perform PUT operation
     */
    public PutOperation putContent(PutCallParameters params, K key, PutContentProvider content) {
        return _putContent(params, key, content);
    }
    
    /**
     * Method called to PUT specified content into appropriate server nodes.
     * 
     * @return Operation object that is used to actually perform PUT operation
     */
    protected PutOperation _putContent(PutCallParameters params, K key, PutContentProvider content)
    {
        final long startTime = System.currentTimeMillis();
        final NodesForKey nodes = _clusterView.getNodesFor(key);

        return new PutOperationImpl<K,CONFIG>(_getConfig(params), startTime,
                nodes, key, params, content);
    }

    /*
    /**********************************************************************
    /* Actual Client API, low-level operations: GET.
    /* NOTE: division between streaming (incremental), full-read is ugly,
    /* but somewhat necessary for efficient operation
    /**********************************************************************
     */

    /**
     * Method called to GET specified content from an appropriate server node,
     * and to pass it to specified processor for actual handling such as
     * aggregating or writing to an output stream.
     *<p>
     * NOTE: definition of "success" for result object is whether operation succeeded
     * in finding entry iff one exists -- but if entry did not exist, operation may
     * still succeed. To check whether entry was fetched, you will need to use
     * {@link GetOperationResult#entryFound()}.
     * 
     * @return Result object that indicates state of the operation as whole,
     *   including information on servers that were accessed during operation.
     *   Caller is expected to check details from this object to determine
     *   whether operation was successful or not.
     */
    public final <T> GetOperationResult<T> getContent(ReadCallParameters params,
            K key, GetContentProcessor<T> processor)
        throws InterruptedException
    {
        return getContent(params, key, processor, null);
    }

    public <T> GetOperationResult<T> getContent(ReadCallParameters params,
            K key, GetContentProcessor<T> processor, ByteRange range)
        throws InterruptedException
    {
        final long startTime = System.currentTimeMillis();
        final CONFIG config = _getConfig(params);

        // First things first: find Server nodes to talk to:
        NodesForKey nodes = _clusterView.getNodesFor(key);
        // then result
        GetOperationResult<T> result = new GetOperationResult<T>(config.getOperationConfig());
        
        // One sanity check: if not enough server nodes to talk to, can't succeed...
        int nodeCount = nodes.size();
        if (nodeCount < 1) {
            return result; // or Exception?
        }
        
        // Then figure out how long we have for the whole operation
        final long endOfTime = startTime + config.getOperationConfig().getGetOperationTimeoutMsecs();
        final long lastValidTime = endOfTime - config.getCallConfig().getMinimumTimeoutMsecs();

        // Ok: first round; try GET from every enabled store
        final boolean noRetries = !_allowRetries(config);
        List<NodeFailure> retries = null;
        for (int i = 0; i < nodeCount; ++i) {
            ClusterServerNode server = nodes.node(i);
            if (!server.isDisabled() || noRetries) {
                ReadCallResult<T> gotten = server.entryGetter().tryGet(config.getCallConfig(),
                        params, endOfTime, key, processor, range);
                if (gotten.failed()) {
                    CallFailure fail = gotten.getFailure();
                    if (fail.isRetriable()) {
                        retries = _add(retries, new NodeFailure(server, fail));
                    } else {
                        result.withFailed(new NodeFailure(server, fail));
                    }
                    continue;
                }
                // did we get the thing?
                T entry = gotten.getResult();
                if (entry != null) {
                    return result.withFailed(retries).setContents(server, entry);
                }
                // it not, it's 404, missing entry. Neither fail nor really success...
                result = result.withMissing(server);
            }
        }
        if (noRetries) { // if we can't retry, don't:
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
                ReadCallResult<T> gotten = server.entryGetter().tryGet(config.getCallConfig(),
                        params, endOfTime, key, processor, range);
                if (gotten.succeeded()) {
                    T entry = gotten.getResult(); // got it?
                    if (entry != null) {
                        return result.withFailed(retries).setContents(server, entry);
                    }
                    // it not, it's 404, missing entry. Neither fail nor really success...
                    result = result.withMissing(server);
                    it.remove();
                } else {
                    CallFailure fail = gotten.getFailure();
                    retry.addFailure(fail);
                    if (!fail.isRetriable()) {
                        result.withFailed(retry);
                        it.remove();
                    }
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
                ReadCallResult<T> gotten = server.entryGetter().tryGet(config.getCallConfig(),
                        params, endOfTime, key, processor, range);
                if (gotten.succeeded()) {
                    T entry = gotten.getResult(); // got it?
                    if (entry != null) {
                        return result.withFailed(retries).setContents(server, entry);
                    }
                    // it not, it's 404, missing entry. Neither fail nor really success...
                    result = result.withMissing(server);
                } else {
                    CallFailure fail = gotten.getFailure();
                    if (fail.isRetriable()) {
                        retries.add(new NodeFailure(server, fail));
                    } else {
                        result.withFailed(new NodeFailure(server, fail));
                    }
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
                ReadCallResult<T> gotten = server.entryGetter().tryGet(config.getCallConfig(),
                        params, endOfTime, key, processor, range);
                if (gotten.succeeded()) {
                    T entry = gotten.getResult(); // got it?
                    if (entry != null) {
                        return result.withFailed(retries).setContents(server, entry);
                    }
                    // it not, it's 404, missing entry. Neither fail nor really success...
                    result = result.withMissing(server);
                    it.remove();
                } else {
                    CallFailure fail = gotten.getFailure();
                    retry.addFailure(fail);
                    if (!fail.isRetriable()) {
                        result.withFailed(retry);
                        it.remove();
                    }
                }
            }
        }
        // we are all done and this'll be a failure...
        return result.withFailed(retries);
    }

    /*
    /**********************************************************************
    /* Actual Client API, low-level operations, metadata access: HEAD, Info
    /**********************************************************************
     */

    public HeadOperationResult headContent(ReadCallParameters params, K key)
        throws InterruptedException
    {
        final long startTime = System.currentTimeMillis();
        final CONFIG config = _getConfig(params);

        // First things first: find Server nodes to talk to:
        NodesForKey nodes = _clusterView.getNodesFor(key);
        // then result
        HeadOperationResult result = new HeadOperationResult(config.getOperationConfig());
        
        // One sanity check: if not enough server nodes to talk to, can't succeed...
        int nodeCount = nodes.size();
        if (nodeCount < 1) {
            return result; // or Exception?
        }
        
        // Then figure out how long we have for the whole operation; use same timeout as GET
        final long endOfTime = startTime + config.getOperationConfig().getGetOperationTimeoutMsecs();
        final long lastValidTime = endOfTime - config.getCallConfig().getMinimumTimeoutMsecs();

        // Ok: first round; try HEAD from every enabled store (or, if only one try, all)
        final boolean noRetries = !_allowRetries(config);
        List<NodeFailure> retries = null;
        for (int i = 0; i < nodeCount; ++i) {
            ClusterServerNode server = nodes.node(i);
            if (!server.isDisabled() || noRetries) {
                HeadCallResult gotten = server.entryHeader().tryHead(config.getCallConfig(),
                        params, endOfTime, key);
                if (gotten.failed()) {
                    CallFailure fail = gotten.getFailure();
                    if (fail.isRetriable()) {
                        retries = _add(retries, new NodeFailure(server, fail));
                    } else {
                        result.withFailed(new NodeFailure(server, fail));
                    }
                    continue;
                }
                if (gotten.hasContentLength()) {
                    return result.withFailed(retries).setContentLength(server, gotten.getContentLength());
                }
                // it not, it's 404, missing entry. Neither fail nor really success...
                result = result.withMissing(server);
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
                HeadCallResult gotten = server.entryHeader().tryHead(config.getCallConfig(),
                        params, endOfTime, key);
                if (gotten.succeeded()) {
                    if (gotten.hasContentLength()) {
                        return result.withFailed(retries).setContentLength(server, gotten.getContentLength());
                    }
                    // it not, it's 404, missing entry. Neither fail nor really success...
                    result = result.withMissing(server);
                    it.remove();
                } else {
                    CallFailure fail = gotten.getFailure();
                    retry.addFailure(fail);
                    if (!fail.isRetriable()) {
                        result.withFailed(retry);
                        it.remove();
                    }
                }
            }
        }
        // if no success, add disabled nodes in the mix; try only once per each
        for (int i = 0; i < nodeCount; ++i) {
            ClusterServerNode server = nodes.node(i);
            if (server.isDisabled()) {
                if (System.currentTimeMillis() >= lastValidTime) {
                    return result.withFailed(retries);
                }
                HeadCallResult gotten = server.entryHeader().tryHead(config.getCallConfig(),
                        params, endOfTime, key);
                if (gotten.succeeded()) {
                    if (gotten.hasContentLength()) {
                        return result.withFailed(retries).setContentLength(server, gotten.getContentLength());
                    }
                    // it not, it's 404, missing entry. Neither fail nor really success...
                    result = result.withMissing(server);
                } else {
                    CallFailure fail = gotten.getFailure();
                    if (fail.isRetriable()) {
                        retries.add(new NodeFailure(server, fail));
                    } else {
                        result.withFailed(new NodeFailure(server, fail));
                    }
                }
            }
        }

        long prevStartTime = secondRoundStart;
        for (int i = 1; (i <= StoreClientConfig.MAX_RETRIES_FOR_GET) && !retries.isEmpty(); ++i) {
            final long currStartTime = System.currentTimeMillis();
            _doDelay(prevStartTime, currStartTime, endOfTime);
            prevStartTime = currStartTime;
            Iterator<NodeFailure> it = retries.iterator();

            while (it.hasNext()) {
                if (System.currentTimeMillis() >= lastValidTime) {
                    return result.withFailed(retries);
                }
                NodeFailure retry = it.next();
                ClusterServerNode server = (ClusterServerNode) retry.getServer();
                HeadCallResult gotten = server.entryHeader().tryHead(config.getCallConfig(),
                        params, endOfTime, key);
                if (gotten.succeeded()) {
                    if (gotten.hasContentLength()) {
                        return result.withFailed(retries).setContentLength(server, gotten.getContentLength());
                    }
                    // it not, it's 404, missing entry. Neither fail nor really success...
                    result = result.withMissing(server);
                    it.remove();
                } else {
                    CallFailure fail = gotten.getFailure();
                    retry.addFailure(fail);
                    if (!fail.isRetriable()) {
                        result.withFailed(retry);
                        it.remove();
                    }
                }
            }
        }
        // we are all done and this'll be a failure...
        return result.withFailed(retries);
    }

    public InfoOperationResult<I> findInfo(ReadCallParameters params, K key)
        throws InterruptedException
    {
        final long startTime = System.currentTimeMillis();
        final CONFIG config = _getConfig(params);

        NodesForKey nodes = _clusterView.getNodesFor(key);

        int nodeCount = nodes.size();
        InfoOperationResult<I> result = new InfoOperationResult<I>(config.getOperationConfig(), nodeCount);
        if (nodeCount < 1) {
            return result; // or Exception?
        }
        final long endOfTime = startTime + config.getOperationConfig().getGetOperationTimeoutMsecs();
        final long lastValidTime = endOfTime - config.getCallConfig().getMinimumTimeoutMsecs();

        // Ok: first round; try access from every store, enabled or not
        List<NodeFailure> retries = null;
        boolean canRetry = _allowRetries(config);
        for (int i = 0; i < nodeCount; ++i) {
            ClusterServerNode server = nodes.node(i);
            ReadCallResult<I> info = server.entryInspector().tryInspect(config.getCallConfig(),
                    params, endOfTime, key, _infoConverter);
            if (info.succeeded()) {
                result.withSuccess(info);
            } else {
                CallFailure fail = info.getFailure();
                if (fail == null) { // not found...
                    result.withMissing(info);
                } else if (canRetry && fail.isRetriable()) { // fail
                    result.withFailed(info);
                } else {
                    retries = _add(retries, new NodeFailure(server, fail));
                }
            }
        }
        if (!canRetry || retries == null) {
            return result;
        }

        long prevStartTime = startTime;
        
        main_loop:
        for (int i = 1; (i <= StoreClientConfig.MAX_RETRIES_FOR_GET) && !retries.isEmpty(); ++i) {
            final long currStartTime = System.currentTimeMillis();
            _doDelay(prevStartTime, currStartTime, endOfTime);
            prevStartTime = currStartTime;
            Iterator<NodeFailure> it = retries.iterator();
            while (it.hasNext()) {
                if (System.currentTimeMillis() >= lastValidTime) {
                    break main_loop;
                }
                NodeFailure retry = it.next();
                ClusterServerNode server = (ClusterServerNode) retry.getServer();
                ReadCallResult<I> info = server.entryInspector().tryInspect(config.getCallConfig(),
                        params, endOfTime, key, _infoConverter);

                if (info.succeeded()) {
                    result.withSuccess(info);
                } else {
                    CallFailure fail = info.getFailure();
                    if (fail == null) { // not found...
                        result.withMissing(info);
                    } else if (canRetry && fail.isRetriable()) { // fail
                        result.withFailed(info);
                    } else {
                        retry.addFailure(fail);
                        if (fail.isRetriable()) { // still retriable
                            continue;
                        }
                        result.withFailed(info);
                    }
                }
                it.remove();
            }
        }

        // Anything left in retry list?
        // !!! TODO
        if (!retries.isEmpty()) {
            Iterator<NodeFailure> it = retries.iterator();
            while (it.hasNext()) {
//                NodeFailure f = it.next();
                /*
                result.withFailed(callResult)
                InfoCallResult               
                */
            }
        }

        return result;
    }

    /*
    /**********************************************************************
    /* Actual Client API, low-level operations: List entry ids, metadata
    /**********************************************************************
     */

    /**
     * Method called to start iterating over entries with given key prefix.
     * Result object is basically an iterator, and no actual access occurs
     * before methods are called on iterator.
     */
    public <T> StoreEntryLister<K,T> listContent(ReadCallParameters params, K prefix, ListItemType itemType)
            throws InterruptedException
    {
        if (itemType == null) {
            throw new IllegalArgumentException("Can't pass null itemType");
        }
        @SuppressWarnings("unchecked")
        GenericContentConverter<ListResponse<T>> converter = (GenericContentConverter<ListResponse<T>>) _listReaders.get(itemType);
        if (converter == null) { // sanity check, should never occur
            throw new IllegalArgumentException("Unsupported item type: "+itemType);
        }
        final CONFIG config = _getConfig(params);
        return new StoreEntryLister<K,T>(config, _clusterView, prefix, itemType, converter, null);
    }
    
    /*
    /**********************************************************************
    /* Actual Client API, low-level operations: DELETE
    /**********************************************************************
     */
    
    /**
     * Method called to DELETE specified content from appropriate server nodes.
     * 
     * @return Result object that indicates state of the operation as whole,
     *   including information on servers that were accessed during operation.
     *   Caller is expected to check details from this object to determine
     *   whether operation was successful or not.
     */
    public DeleteOperationResult deleteContent(DeleteCallParameters params, K key)
        throws InterruptedException
    {
        final long startTime = System.currentTimeMillis();
        final CONFIG config = _getConfig(params);

        // First things first: find Server nodes to talk to:
        NodesForKey nodes = _clusterView.getNodesFor(key);
        DeleteOperationResult result = new DeleteOperationResult(config.getOperationConfig());

        // One sanity check: if not enough server nodes to talk to, can't succeed...
        int nodeCount = nodes.size();
        if (nodeCount < config.getOperationConfig().getMinimalOksToSucceed()) {
            return result; // or Exception?
        }
        // Then figure out how long we have for the whole operation
        final long endOfTime = startTime + config.getOperationConfig().getGetOperationTimeoutMsecs();
        final long lastValidTime = endOfTime - config.getCallConfig().getMinimumTimeoutMsecs();

        /* Ok: first round; try DETE from every enabled store, up to optimal number
         * of successes we expect.
         */
        final boolean noRetries = !_allowRetries(config);
        List<NodeFailure> retries = null;
        for (int i = 0; i < nodeCount; ++i) {
            ClusterServerNode server = nodes.node(i);
            if (server.isDisabled() && !noRetries) { // should be able to break, but let's double check
                break;
            }
            CallFailure fail = server.entryDeleter().tryDelete(config.getCallConfig(),
                    params, endOfTime, key);
            if (fail != null) {
                if (fail.isRetriable()) {
                    retries = _add(retries, new NodeFailure(server, fail));
                } else {
                    result.withFailed(new NodeFailure(server, fail));
                }
                continue;
            }
            result.addSucceeded(server);
            // first round: go to the max, if possible
            if (result.succeededMaximally()) {
                return result.withFailed(retries);
            }
        }
        if (noRetries) { // if no retries, bail out quickly
            return result.withFailed(retries);
        }
        
        /* If we got this far, let's accept 'just optimal'; but keep on trying for
         * optimal since deletion via expiration is much more costly than explicit
         * DELETEs.
         */
        final long secondRoundStart = System.currentTimeMillis();
        if (result.succeededOptimally() || secondRoundStart >= lastValidTime) {
            return result.withFailed(retries);
        }
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
                CallFailure fail = server.entryDeleter().tryDelete(config.getCallConfig(),
                        params, endOfTime, key);
                if (fail != null) {
                    retry.addFailure(fail);
                    if (!fail.isRetriable()) { // not worth retrying?
                        result.withFailed(retry);
                        it.remove();
                    }
                } else {
                    it.remove(); // remove now from retry list
                    result.addSucceeded(server);
                    if (result.succeededOptimally()) {
                        return result.withFailed(retries);
                    }
                }
            }
        }

        // if no success, add disabled nodes in the mix; but only if we don't have minimal success:
        for (int i = 0; i < nodeCount; ++i) {
            if (result.succeededMinimally() || System.currentTimeMillis() >= lastValidTime) {
                return result.withFailed(retries);
            }
            ClusterServerNode server = nodes.node(i);
            if (server.isDisabled()) {
                CallFailure fail = server.entryDeleter().tryDelete(config.getCallConfig(),
                        params, endOfTime, key);
                if (fail != null) {
                    if (fail.isRetriable()) {
                        retries.add(new NodeFailure(server, fail));
                    } else {
                        result.withFailed(new NodeFailure(server, fail));
                    }
                } else {
                    result.addSucceeded(server);
                }
            }
        }

        long prevStartTime = secondRoundStart;
        for (int i = 1; (i <= StoreClientConfig.MAX_RETRIES_FOR_DELETE) && !retries.isEmpty(); ++i) {
            final long currStartTime = System.currentTimeMillis();
            _doDelay(prevStartTime, currStartTime, endOfTime);
            // and off we go again...
            Iterator<NodeFailure> it = retries.iterator();
            while (it.hasNext()) {
                if (result.succeededMinimally() || System.currentTimeMillis() >= lastValidTime) {
                    return result.withFailed(retries);
                }
                NodeFailure retry = it.next();
                ClusterServerNode server = retry.getServer();
                CallFailure fail = server.entryDeleter().tryDelete(config.getCallConfig(),
                        params, endOfTime, key);
                if (fail != null) {
                    retry.addFailure(fail);
                    if (!fail.isRetriable()) {
                        result.withFailed(retry);
                        it.remove();
                    }
                } else {
                    result.addSucceeded(server);
                }
            }
            prevStartTime = currStartTime;
        }
        // we are all done, failed:
        return result.withFailed(retries);
    }
    
    /*
    /**********************************************************************
    /* Helper methods
    /**********************************************************************
     */

    @SuppressWarnings("unchecked")
    protected CONFIG _getConfig(CallParameters callParams)
    {
        if (callParams != null) {
            CONFIG cfg = (CONFIG) callParams.getClientConfig();
            if (cfg != null) {
                return cfg;
            }
        }
        return _config;
    }

    protected static <ITEM extends ItemInfo> ContentConverter<ITEM> _stdItemInfoConverter(
            StoreClientConfig<?,?> config, Class<ITEM> infoType)
    {
        return new GenericContentConverter<ITEM>(config.getJsonMapper(), infoType);
    }
    
    protected boolean _allowRetries(CONFIG config) {
        return config.getOperationConfig().getAllowRetries();
    }

    protected <T> List<T> _add(List<T> list, T entry) {
        if (list == null) {
            list = new LinkedList<T>();
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
