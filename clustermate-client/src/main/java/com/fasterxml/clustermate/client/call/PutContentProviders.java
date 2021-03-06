package com.fasterxml.clustermate.client.call;

import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.storemate.shared.ByteContainer;
import com.fasterxml.storemate.shared.compress.Compression;
import com.fasterxml.storemate.shared.hash.HashConstants;

/**
 * Helper class used for creating one of standard {@link PutContentProvider}
 * implementations.
 */
public class PutContentProviders
{
    public static StdPutContentProvider forFile(File f, long length) {
        return new FileBacked(f, length);
    }

    public static StdPutContentProvider forBytes(byte[] bytes) {
        return forBytes(bytes, 0, bytes.length);
    }

    public static StdPutContentProvider forBytes(byte[] bytes, Compression comp, long origLen) {
        return forBytes(ByteContainer.simple(bytes, 0, bytes.length), comp, origLen);
    }

    public static StdPutContentProvider forBytes(byte[] bytes, int offset, int len) {
        return new ByteBacked(ByteContainer.simple(bytes, offset, len));
    }

    public static StdPutContentProvider forBytes(ByteContainer bytes, Compression comp, long origLen) {
        return new ByteBacked(bytes, comp, origLen);
    }
    
    /**
     * Intermediate base class used for building actual {@link PutContentProvider} instances.
     */
    public abstract static class StdPutContentProvider
        implements PutContentProvider
    {
        protected final Compression _existingCompression;
        protected final long _uncompressedLength;

        protected final AtomicInteger _contentHash = new AtomicInteger(HashConstants.NO_CHECKSUM);

        protected StdPutContentProvider() {
            _existingCompression = null;
            _uncompressedLength = 0L;
        }

        protected StdPutContentProvider(Compression comp, long uncompressedLength) {
            _existingCompression = comp;
            _uncompressedLength = uncompressedLength;
        }

        protected StdPutContentProvider(StdPutContentProvider base) {
            _existingCompression = base._existingCompression;
            _uncompressedLength = base._uncompressedLength;
        }
        
        public abstract StdPutContentProvider withCompression(Compression comp, long uncompLen);
        
        /**
         * Default implementation does not require any clean up, so implementation
         * is empty.
         */
        @Override
        public void release() { }
        
        @Override
        public int getContentHash() {
            return _contentHash.get();
        }

        @Override
        public void setContentHash(int hash) {
            // Should get same value always, but just to make sure we never
            // change value, or overwrite with "no hash"
            _contentHash.compareAndSet(HashConstants.NO_CHECKSUM, hash);
        }

        @Override
        public Compression getExistingCompression() {
            return _existingCompression;
        }

        @Override
        public long uncompressedLength() {
            return _uncompressedLength;
        }
    }

    /*
    /**********************************************************************
    /* Simple standard implementation for byte[] backed
    /**********************************************************************
     */

    /**
     * Simple {@link PutContentProvider} implementation that is backed by
     * a raw byte array.
     */
    protected static class ByteBacked extends StdPutContentProvider
    {
        protected final ByteContainer _bytes;

        public ByteBacked(ByteContainer data) {
            super();
            _bytes = data;
        }

        public ByteBacked(ByteContainer data,
                Compression comp, long originalLength) {
            super(comp, originalLength);
            _bytes = data;
        }

        @Override
        public ByteBacked withCompression(Compression comp, long uncompLen) {
            return new ByteBacked(_bytes, comp, uncompLen);
        }
        
        @Override public long length() { return (long) _bytes.byteLength(); }
        @Override public ByteContainer contentAsBytes() { return _bytes; }
        @Override public File contentAsFile() { return null; }
        @Override public InputStream contentAsStream() { return null; }
        @Override public Object rawSource() { return _bytes; }
    }

    /*
    /**********************************************************************
    /* Simple standard implementations for providers
    /**********************************************************************
     */
    
    /**
     * Simple {@link PutContentProvider} implementation that is backed by
     * a {@link java.io.File}.
     */
    protected static class FileBacked
        extends StdPutContentProvider
    {
        protected final File _file;
        protected final long _length;

        public FileBacked(File file, long length) {
            this(file, length, null, 0L);
        }

        public FileBacked(File file, long length,
                Compression existingCompression, long uncompressedLength) {
            super(existingCompression, uncompressedLength);
            _file = file;
            _length = length;
        }

        @Override
        public FileBacked withCompression(Compression comp, long uncompLen) {
            return new FileBacked(_file, _length, comp, uncompLen);
        }
        
        @Override public long length() { return _length; }
        @Override public ByteContainer contentAsBytes() { return null; }
        @Override public File contentAsFile() { return _file; }
        @Override public InputStream contentAsStream() { return null; }
        @Override public Object rawSource() { return _file; }
    }
}
