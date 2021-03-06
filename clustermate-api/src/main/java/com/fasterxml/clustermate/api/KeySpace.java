package com.fasterxml.clustermate.api;

import java.util.BitSet;

import com.fasterxml.clustermate.api.KeyHash;
import com.fasterxml.clustermate.api.KeyRange;
import com.fasterxml.clustermate.api.KeySpace;

/**
 * Entity that represents subset of key hashes used for finding nodes
 * that are responsible for handling piece of content with given 
 * key hash. Space is defined with a single integer value, length,
 * covering indexes [0, length-1[.
 *<p>
 * Logically key space is circular, meaning that all {@link KeyRange}s
 * are consecutive by using wrapping at the end of key hash range.
 */
public final class KeySpace
{
    /**
     * We shall use a nicely divisible default length of 360; also
     * conceptually nice analogy to degrees (since we use circular
     * space definition).
     */
    public final static int DEFAULT_LENGTH = 360;

    protected final KeyRange _emptyRange;
	
    /**
     * Length of key space; used for modulo calculations.
     */
    protected final int _length;

    /*
    /**********************************************************************
    /* Construction, factory methods
    /**********************************************************************
     */

    /**
     * Standard constructor that 
     */
    public KeySpace() {
        this(DEFAULT_LENGTH);
    }
	
    /**
     * Basic constructor that creates key space with specific length.
     */
    public KeySpace(int length) {
        _length = length;
        _emptyRange = new KeyRange(this, 0, 0);
    }

    /**
     * Factory method used for constructing {@link KeyRange} instances
     * to use for key hashes in this {@link KeySpace}.
     * 
     * @param from Starting point of the range (inclusive)
     * @param length Length of range; between 0 and {@link #getLength} (inclusive)
     * 
     * @throws IllegalArgumentException if arguments are outside range of this
     *    {@link KeySpace}
     */
    public KeyRange range(int from, int length)
    {
        if (from < 0 || from >= _length) {
            throw new IllegalArgumentException("Invalid 'from' argument, "+from+"; must be [0, "
                    +_length+"[");
        }
        if (length < 0 || length > _length) {
            throw new IllegalArgumentException("Invalid 'length' argument, "+length+"; must be [0, "
                    +_length+"]");
        }
        return new KeyRange(this, from, length);
    }

    /**
     * Factory method for constructing a {@link KeyRange} within this
     * key space.
     */
    public KeyRange range(String ref) throws IllegalArgumentException
    {
        return KeyRange.valueOf(this, ref);
    }

    public KeyRange emptyRange() {
        return _emptyRange;
    }

    /**
     * Factory method for testing
     */
    public KeyRange fullRange() {
        return new KeyRange(this, 0, _length);
    }
    
    /**
     * Factory method for constructing {@link KeyHash} instances that
     * are used in place of raw hash values (to reduce chance that "raw"
     * and modulo hash values are incorrectly used)
     */
    public KeyHash hash(int fullHash) {
        return new KeyHash(fullHash, _length);
    }


    /**
     * Helper method called to calculate a key range of this space as defined by
     * slicing properties.
     * Key space will be divided in <code>segmentCount</code> segments,
     * and each node will cover C segments, where C is number of copies desired.
     * The first segment to cover is from index. The only slight complication is that
     * of handling of key ranges not divisible evenly by number of nodes.
     * 
     * @param index Zero-based index of the node
     * @param segmentCount Number of segments created in the keyspace
     * @param copies Number of copies of data that must exist; determines span (and overlap)
     *   of each segment
     * 
     * @return Range to allocate
     */
    public KeyRange calcSegment(int index, int segmentCount, int copies)
    {
        // First trivial case of a full coverage for a single node
        if (segmentCount <= 1) {
            return fullRange();
        }
        /* but note: with other full coverage cases (2 copies over 2 segments),
         * we can NOT simply return full range, since starting point must
         * vary.
         */
        
        // Then see if space is neatly divisible:
        if ((getLength() % segmentCount) == 0) {
            int simpleLength = getLength() / segmentCount;
            return range(index * simpleLength, copies * simpleLength);
        }
        // If not, calculate with floating point
        double segLen = (double) getLength() / segmentCount;
        int start = (int) Math.round(segLen * index);
        int end = (int) Math.round(segLen * ((index + copies) % segmentCount));

        if (end < start) { // wrap-around
            end += getLength();
        }
        return range(start, end-start);
    }
    
    /*
    /**********************************************************************
    /* Simple accessors
    /**********************************************************************
     */

    // We will actually serialize KeySpace simply as an int that defines its length
    public int getLength() {
        return _length;
    }

    /*
    /**********************************************************************
    /* Advanced accessors
    /**********************************************************************
     */

    /**
     * Method that can be used to calculate coverage of given ranges
     * over this key space: will be <code>[0, getLength()]</code>
     * between "no coverage" and "full coverage"
     * 
     * @return Number of 'slots' covered by given ranges
     */
    public int getCoverage(Iterable<KeyRange> ranges)
    {
        BitSet slices = new BitSet(_length);
        for (KeyRange range : ranges) {
            range.fill(slices);
        }
        return slices.cardinality();
    }

    /*
    /**********************************************************************
    /* Overrides
    /**********************************************************************
     */

    @Override
    public boolean equals(Object o)
    {
        if (o == this) return true;
        if (o == null) return false;
        if (o.getClass() != getClass()) return false;
        return ((KeySpace) o)._length == _length;
    }

    @Override
    public int hashCode() { return _length; }

    @Override
    public String toString() {
        return "[0,+"+_length+"]";
    }
}
