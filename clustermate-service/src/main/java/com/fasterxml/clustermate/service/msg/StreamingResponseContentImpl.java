package com.fasterxml.clustermate.service.msg;

import java.io.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.compress.lzf.LZFInputStream;

import com.fasterxml.storemate.shared.ByteContainer;
import com.fasterxml.storemate.shared.ByteRange;
import com.fasterxml.storemate.shared.compress.Compression;
import com.fasterxml.storemate.shared.compress.Compressors;
import com.fasterxml.storemate.shared.util.BufferRecycler;
import com.fasterxml.storemate.store.StoreOperationThrottler;

/**
 * Simple (but not naive) {@link StreamingResponseContent} implementation used
 * for returning content for inlined entries.
 */
public class StreamingResponseContentImpl
    implements StreamingResponseContent
{
    private final Logger LOG = LoggerFactory.getLogger(getClass());

    /*
    /**********************************************************************
    /* Helper objects
    /**********************************************************************
     */
    
    /**
     * We can reuse read buffers as they are somewhat costly to
     * allocate, reallocate all the time.
     */
    final protected static BufferRecycler _bufferRecycler = new BufferRecycler(32000);

    final protected StoreOperationThrottler _throttler;
    
    /*
    /**********************************************************************
    /* Data to stream out
    /**********************************************************************
     */

    private final File _file;

    private final ByteContainer _data;
	
    private final long _dataOffset;
	
    private final long _dataLength;

    /*
    /**********************************************************************
    /* Metadata
    /**********************************************************************
     */

    private final Compression _compression;

    /**
     * Content length as reported when caller asks for it; -1 if not known.
     */
    private final long _contentLength;

    /**
     * When reading from a file, this indicates length of content before
     * processing (if any).
     */
    private final long _fileLength;

    /*
    /**********************************************************************
    /* Construction
    /**********************************************************************
     */
    
    public StreamingResponseContentImpl(ByteContainer data, ByteRange range,long contentLength)
    {
        _throttler = null;
        if (data == null) {
            throw new IllegalArgumentException();
        }
        _data = data;
        // Range request? let's tweak offsets if so...
        if (range == null) {
            _dataOffset = -1L;
            _dataLength = -1L;
            _contentLength = contentLength;
        } else {
            _dataOffset = range.getStart();
            _dataLength = range.calculateLength();
            _contentLength = _dataLength;
        }
        _file = null;
        _compression = null;
        _fileLength = 0L;
    }

    public StreamingResponseContentImpl(StoreOperationThrottler throttler,
            File f, Compression comp, ByteRange range,
            long contentLength, long rawFileLength)
    {
        _throttler = throttler;
        _data = null;
        if (range == null) {
            _dataOffset = -1L;
            _dataLength = -1L;
            _contentLength = contentLength;
        } else {
            // Range can be stored in offset..
            _dataOffset = range.getStart();
            _dataLength = range.calculateLength();
            _contentLength = _dataLength;
        }
        _file = f;
        _compression = comp;
        _fileLength = rawFileLength;
    }

    @Override
    public long getLength()
    {
        return _contentLength;
    }

    @Override
    public void writeContent(OutputStream out) throws IOException
    {
        /* Inline data is simple, because we have already decompressed it
         * if and as necessary; so all we do is just write is out.
         */
        if (_data != null) {
            if (_dataOffset <= 0L) {
                _data.writeBytes(out);
            } else { // casts are safe; inlined data relatively small
                _data.writeBytes(out, (int) _dataOffset, (int) _dataLength);
            }
            return;
        }
        _writeContentFromFile(out);
    }
    
    @SuppressWarnings("resource")
    protected void _writeContentFromFile(OutputStream out) throws IOException
    {
        InputStream in = new FileInputStream(_file);
        
        // First: LZF has special optimization to use, if we are to copy the whole thing:
        if ((_compression == Compression.LZF) && (_dataLength == -1)) {
    	        LZFInputStream lzfIn = new LZFInputStream(in);
    	        try {
    	            lzfIn.readAndWrite(out);
    	        } finally {
    	            _close(lzfIn);
    	        }
    	        return;
        }

        // otherwise default handling via explicit copying
        final BufferRecycler.Holder bufferHolder = _bufferRecycler.getHolder();        
        final byte[] copyBuffer = bufferHolder.borrowBuffer();

        in = Compressors.uncompressingStream(in, _compression);

        // First: anything to skip (only the case for range requests)?
        if (_dataOffset > 0) {
            long skipped = 0L;
            long toSkip = _dataOffset;

            while (toSkip > 0) {
                long count = in.skip(toSkip);
                if (count <= 0L) { // should not occur really...
                    throw new IOException("Failed to skip more than "+skipped+" bytes (needed to skip "+_dataOffset+")");
                }
                skipped += count;
                toSkip -= count;
            }
        }
        // Second: output the whole thing, or just subset?
        try {
            if (_dataLength < 0) { // all of it
                int count;
                while ((count = in.read(copyBuffer)) > 0) {
                    out.write(copyBuffer, 0, count);
                }
                return;
            }
            // Just some of it
            long left = _dataLength;
    	    
            while (left > 0) {
                int count = in.read(copyBuffer, 0, (int) Math.min(copyBuffer.length, left));
                if (count <= 0) {
                    break;
                }
                out.write(copyBuffer, 0, count);
                left -= count;
            }
            // Sanity check; can't fix or add headers as output has been written...
            if (left > 0) {
                LOG.error("Failed to write request Range %d-%d (from File {}): only wrote {} bytes",
    	                new Object[] { _dataOffset, _dataOffset+_dataLength+1, _file.getAbsolutePath(),
    	                _dataLength-left });
            }
        } finally {
            bufferHolder.returnBuffer(copyBuffer);
            _close(in);
        }
    }

    private final void _close(InputStream in)
    {
        try {
            in.close();
        } catch (IOException e) {
            LOG.warn("Failed to close file '{}': {}", _file, e.getMessage());
        }
    }
    
    /*
    /**********************************************************************
    /* Methods for helping testing
    /**********************************************************************
     */

    public boolean hasFile() { return _file != null; }
    public boolean inline() { return _data != null; }
}
