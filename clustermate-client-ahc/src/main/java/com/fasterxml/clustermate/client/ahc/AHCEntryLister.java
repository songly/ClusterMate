package com.fasterxml.clustermate.client.ahc;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.storemate.client.CallFailure;
import com.fasterxml.storemate.client.call.*;
import com.fasterxml.storemate.shared.EntryKey;
import com.fasterxml.storemate.shared.util.IOUtil;

import com.fasterxml.clustermate.client.ClusterServerNode;
import com.fasterxml.clustermate.client.StoreClientConfig;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;

public class AHCEntryLister<K extends EntryKey>
    extends AHCBasedAccessor<K>
    implements EntryLister<K>
{
    protected final ClusterServerNode _server;

    public AHCEntryLister(StoreClientConfig<K,?> storeConfig,
            AsyncHttpClient hc,
            ClusterServerNode server)
    {
        super(storeConfig, hc);
        _server = server;
    }

    @Override
    public <T> EntryListResult<T> tryList(CallConfig config, long endOfTime,
            K prefix, int maxResults, ContentConverter<T> converter)
    {
        // first: if we can't spend at least 10 msecs, let's give up:
        final long startTime = System.currentTimeMillis();
        final long timeout = Math.min(endOfTime - startTime, config.getDeleteCallTimeoutMsecs());
        if (timeout < config.getMinimumTimeoutMsecs()) {
            return failed(CallFailure.timeout(_server, startTime, startTime));
        }
        AHCPathBuilder path = _server.rootPath();
        path = _pathFinder.appendStoreEntryPath(path);
        path = _keyConverter.appendToPath(path, prefix);      
        BoundRequestBuilder reqBuilder = path.deleteRequest(_httpClient);

        try {
            Future<Response> futurama = _httpClient.executeRequest(reqBuilder.build());
            // First, see if we can get the answer without time out...
            Response resp;
            try {
                resp = futurama.get(timeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                return failed(CallFailure.timeout(_server, startTime, System.currentTimeMillis()));
            }
            // and if so, is it successful?
            int statusCode = resp.getStatusCode();
            // one thing first: handle standard headers, if any?
            handleHeaders(_server, resp, startTime);

            // call ok?
            if (!IOUtil.isHTTPSuccess(statusCode)) {
                // if not, why not? Any well-known problems? (besides timeout that was handled earlier)

                // then the default fallback
                String msg = getExcerpt(resp, config.getMaxExcerptLength());
                return failed(CallFailure.general(_server, statusCode, startTime, System.currentTimeMillis(), msg));
            }
            return null;
        } catch (Exception e) {
            return failed(CallFailure.clientInternal(_server,
                    startTime, System.currentTimeMillis(), _unwrap(e)));
        }
    }

    protected <T> EntryListResult<T> failed(CallFailure fail) {
        return new AHCEntryListResult<T>(fail);
    }
}
