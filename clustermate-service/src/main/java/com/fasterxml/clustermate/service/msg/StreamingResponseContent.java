package com.fasterxml.clustermate.service.msg;

import java.io.*;

/**
 * Interface wrapped around content that is dynamically read and written
 * as part of Service response processing.
 */
public interface StreamingResponseContent
{
    public void writeContent(OutputStream out) throws IOException;

    /**
     * Method that may be called to check length of the content to stream,
     * if known. If length is not known, -1 will be returned; otherwise
     * non-negative length.
     */
    public long getLength();

    // // // Methods for helping testing
    
    public boolean hasFile();
    public boolean inline();
}
