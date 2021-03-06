package com.fasterxml.clustermate.api.msg;

import java.util.List;

import com.fasterxml.clustermate.api.ListItemType;
import com.fasterxml.storemate.shared.StorableKey;

/**
 * Response message type for List requests.
 *<p>
 * NOTE: should be abstract, but due to some type resolution complications remains
 * concrete.
 * 
 * @param <T> Type of entries; either simple id ({@link StorableKey}),
 *    textual name ({@link java.lang.String}) or full {@link ListItem}.
 */
public class ListResponse<T> // not a CRUD request/response
{
    /**
     * Error message for failed requests
     */
    public String message;

    /**
     * Fetched items for successful requests
     */
    public List<T> items;

    /**
     * Id of the last item processed during iteration; usually that of the
     * last item (but could be another value if a later id can be safely used).
     */
    public StorableKey lastSeen;
    
    public ListResponse() { }
    public ListResponse(String msg) { message = msg; }
    public ListResponse(List<T> i, StorableKey ls) {
        items = i;
        lastSeen = ls;
    }

    public int size() {
        return (items == null) ? 0 : items.size();
    }
    
    public ListItemType type() { throw new IllegalStateException(); }
//    public abstract ListItemType type();
    
    public static final class IdListResponse extends ListResponse<StorableKey> {
        public IdListResponse() { }
        public IdListResponse(List<StorableKey> ids, StorableKey lastSeen) { super(ids, lastSeen); }
        @Override public ListItemType type() { return ListItemType.ids; }
    }

    public static final class NameListResponse extends ListResponse<String> {
        public NameListResponse() { }
        public NameListResponse(List<String> names, StorableKey lastSeen) { super(names, lastSeen); }
        @Override public ListItemType type() { return ListItemType.names; }
    }

    public static final class MinimalItemListResponse extends ListResponse<ListItem> {
        public MinimalItemListResponse() { }
        public MinimalItemListResponse(List<ListItem> entries, StorableKey lastSeen) { super(entries, lastSeen); }
        @Override public ListItemType type() { return ListItemType.minimalEntries; }
    }

    // 07-Feb-2013, tatu: Ought to work, but does NOT work due to Jackson (2.1.3) bug with
    //    generic type resolution
    /*
    public static final class FullItemListResponse extends ListResponse<ListItem> {
        public FullItemListResponse() { }
        public FullItemListResponse(List<ListItem> entries, StorableKey lastSeen) {
            super(entries, lastSeen);
        }
        @Override public ListItemType type() { return ListItemType.fullEntries; }
    }
    */
}
