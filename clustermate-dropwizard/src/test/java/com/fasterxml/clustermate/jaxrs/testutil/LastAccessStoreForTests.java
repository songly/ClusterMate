package com.fasterxml.clustermate.jaxrs.testutil;

import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.Environment;

import com.fasterxml.clustermate.service.bdb.BDBLastAccessStore;
import com.fasterxml.clustermate.service.store.StoredEntry;
import com.fasterxml.clustermate.service.store.StoredEntryConverter;
import com.fasterxml.storemate.backend.bdbje.util.BDBConverters;
import com.fasterxml.storemate.store.lastaccess.LastAccessConfig;
import com.fasterxml.storemate.store.lastaccess.LastAccessUpdateMethod;

public class LastAccessStoreForTests
    extends BDBLastAccessStore<TestKey, StoredEntry<TestKey>>
{
    public LastAccessStoreForTests(Environment env,
            StoredEntryConverter<TestKey,StoredEntry<TestKey>,?> conv, LastAccessConfig config)
    {
        super(env, conv, config);
    }

    @Override
    protected DatabaseEntry lastAccessKey(TestKey key, LastAccessUpdateMethod acc0)
    {
        FakeLastAccess acc = (FakeLastAccess) acc0;
        if (acc != null) {
            switch (acc) {
            case NONE:
                return null;
            case GROUPED: // important: not just group id, but also client id
                int cid = key.getCustomerId().asInt();
                byte[] b = new byte[] { (byte) (cid>>24), (byte)(cid>>16), (byte)(cid>>8), (byte)cid };
                return BDBConverters.simpleConverter.withBytes(b, 0, b.length);
            case INDIVIDUAL: // whole key, including client id, group id length
                return key.asStorableKey().with(BDBConverters.simpleConverter);
            }
        }
        return null;
    }
}
