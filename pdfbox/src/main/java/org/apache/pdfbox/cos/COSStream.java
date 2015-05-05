/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pdfbox.cos;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.pdfbox.filter.DecodeResult;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccess;
import org.apache.pdfbox.io.RandomAccessBuffer;
import org.apache.pdfbox.io.RandomAccessFileInputStream;
import org.apache.pdfbox.io.RandomAccessFileOutputStream;

/**
 * This class represents a stream object in a PDF document.
 *
 * @author Ben Litchfield
 */
public class COSStream extends COSDictionary implements Closeable
{
    private static final int BUFFER_SIZE = 16384;

    private final RandomAccess buffer = new RandomAccessBuffer();
    private RandomAccessFileOutputStream filteredStream;
    private RandomAccessFileOutputStream unFilteredStream;
    private DecodeResult decodeResult;

    public COSStream()
    {
    }

    /**
     * @param dictionary The dictionary that is associated with this stream.
     */
    public COSStream(COSDictionary dictionary)
    {
        super(dictionary);
    }

    /**
     * This will get the stream with all of the filters applied.
     *
     * @return the bytes of the physical (encoded) stream
     *
     * @throws IOException when encoding/decoding causes an exception
     */
    public InputStream getFilteredStream() throws IOException
    {
        if (buffer.isClosed())
        {
            throw new IOException("COSStream has been closed and cannot be read. "
                    + "Perhaps its enclosing PDDocument has been closed?");
        }

        if (filteredStream == null)
        {
            doEncode();
        }
        long position = filteredStream.getPosition();
        long length = filteredStream.getLength();

        RandomAccessFileInputStream input = new RandomAccessFileInputStream(buffer, position,
                length);
        return new BufferedInputStream(input, BUFFER_SIZE);
    }

    /**
     * This will get the length of the encoded stream.
     * 
     * @return the length of the encoded stream as long
     *
     * @throws IOException
     */
    public long getFilteredLength() throws IOException
    {
        if (filteredStream == null)
        {
            doEncode();
        }
        return filteredStream.getLength();
    }

    /**
     * @return the bytes of the logical (decoded) stream
     *
     * @throws IOException when encoding/decoding causes an exception
     */
    public InputStream getUnfilteredStream() throws IOException
    {
        if (buffer.isClosed())
        {
            throw new IOException("COSStream has been closed and cannot be read. "
                    + "Perhaps its enclosing PDDocument has been closed?");
        }

        InputStream retval;
        if (unFilteredStream == null)
        {
            doDecode();
        }

        // if unFilteredStream is still null then this stream has not been
        // created yet, so we should return null.
        if (unFilteredStream != null)
        {
            long position = unFilteredStream.getPosition();
            long length = unFilteredStream.getLength();
            RandomAccessFileInputStream input = new RandomAccessFileInputStream(buffer, position,
                    length);
            retval = new BufferedInputStream(input, BUFFER_SIZE);
        }
        else
        {

            retval = new ByteArrayInputStream(new byte[0]);
        }
        return retval;
    }

    /**
     * Returns the repaired stream parameters dictionary.
     *
     * @return the repaired stream parameters dictionary
     * @throws IOException when encoding/decoding causes an exception
     */
    public DecodeResult getDecodeResult() throws IOException
    {
        if (unFilteredStream == null)
        {
            doDecode();
        }

        if (unFilteredStream == null || decodeResult == null)
        {
            StringBuilder filterInfo = new StringBuilder();
            COSBase filters = getFilters();
            if (filters != null)
            {
                filterInfo.append(" - filter: ");
                if (filters instanceof COSName)
                {
                    filterInfo.append(((COSName) filters).getName());
                }
                else if (filters instanceof COSArray)
                {
                    COSArray filterArray = (COSArray) filters;
                    for (int i = 0; i < filterArray.size(); i++)
                    {
                        if (filterArray.size() > 1)
                        {
                            filterInfo.append(", ");
                        }
                        filterInfo.append(((COSName) filterArray.get(i)).getName());
                    }
                }
            }
            String subtype = getNameAsString(COSName.SUBTYPE);
            throw new IOException(subtype + " stream was not read" + filterInfo);
        }
        return decodeResult;
    }

    @Override
    public void accept(COSVisitor visitor) throws IOException
    {
        visitor.visit(this);
    }

    /**
     * This will decode the physical byte stream applying all of the filters to the stream.
     *
     * @throws IOException If there is an error applying a filter to the stream.
     */
    private void doDecode() throws IOException
    {
        // FIXME: We shouldn't keep the same reference?
        unFilteredStream = filteredStream;

        COSBase filters = getFilters();
        if (filters == null)
        {
            // then do nothing
            decodeResult = DecodeResult.DEFAULT;
        }
        else if (filters instanceof COSName)
        {
            doDecode((COSName) filters, 0);
        }
        else if (filters instanceof COSArray)
        {
            COSArray filterArray = (COSArray) filters;
            for (int i = 0; i < filterArray.size(); i++)
            {
                COSName filterName = (COSName) filterArray.get(i);
                doDecode(filterName, i);
            }
        }
        else
        {
            throw new IOException("Unknown filter type:" + filters);
        }
    }

    /**
     * This will decode applying a single filter on the stream.
     *
     * @param filterName The name of the filter.
     * @param filterIndex The index of the current filter.
     *
     * @throws IOException If there is an error parsing the stream.
     */
    private void doDecode(COSName filterName, int filterIndex) throws IOException
    {
        Filter filter = FilterFactory.INSTANCE.getFilter(filterName);

        boolean done = false;
        IOException exception = null;
        long position = unFilteredStream.getPosition();
        long length = unFilteredStream.getLength();
        // in case we need it later
        long writtenLength = unFilteredStream.getLength();

        if (length == 0 && writtenLength == 0)
        {
            // if the length is zero then don't bother trying to decode
            // some filters don't work when attempting to decode
            // with a zero length stream. See zlib_error_01.pdf
            IOUtils.closeQuietly(unFilteredStream);
            unFilteredStream = new RandomAccessFileOutputStream(buffer);
            done = true;
        }
        else
        {
            // ok this is a simple hack, sometimes we read a couple extra
            // bytes that shouldn't be there, so we encounter an error we will just
            // try again with one less byte.
            for (int tryCount = 0; length > 0 && !done && tryCount < 5; tryCount++)
            {
                try
                {
                    attemptDecode(position, length, filter, filterIndex);
                    done = true;
                }
                catch (IOException io)
                {
                    length--;
                    exception = io;
                }
            }
            if (!done)
            {
                // if no good stream was found then lets try again but with the
                // length of data that was actually read and not length
                // defined in the dictionary
                length = writtenLength;
                for (int tryCount = 0; !done && tryCount < 5; tryCount++)
                {
                    try
                    {
                        attemptDecode(position, length, filter, filterIndex);
                        done = true;
                    }
                    catch (IOException io)
                    {
                        length--;
                        exception = io;
                    }
                }
            }
        }
        if (!done && exception != null)
        {
            throw exception;
        }
    }

    // attempts to decode the stream at the given position and length
    private void attemptDecode(long position, long length, Filter filter, int filterIndex)
            throws IOException
    {
        InputStream input = null;
        try
        {
            input = new BufferedInputStream(new RandomAccessFileInputStream(buffer, position,
                    length), BUFFER_SIZE);
            IOUtils.closeQuietly(unFilteredStream);
            unFilteredStream = new RandomAccessFileOutputStream(buffer);
            decodeResult = filter.decode(input, unFilteredStream, this, filterIndex);
        }
        finally
        {
            IOUtils.closeQuietly(input);
        }
    }

    /**
     * This will encode the logical byte stream applying all of the filters to the stream.
     *
     * @throws IOException If there is an error applying a filter to the stream.
     */
    private void doEncode() throws IOException
    {
        filteredStream = unFilteredStream;

        COSBase filters = getFilters();
        if (filters == null)
        {
            // there is no filter to apply
        }
        else if (filters instanceof COSName)
        {
            doEncode((COSName) filters, 0);
        }
        else if (filters instanceof COSArray)
        {
            // apply filters in reverse order
            COSArray filterArray = (COSArray) filters;
            for (int i = filterArray.size() - 1; i >= 0; i--)
            {
                COSName filterName = (COSName) filterArray.get(i);
                doEncode(filterName, i);
            }
        }
    }

    /**
     * This will encode applying a single filter on the stream.
     *
     * @param filterName The name of the filter.
     * @param filterIndex The index to the filter.
     *
     * @throws IOException If there is an error parsing the stream.
     */
    private void doEncode(COSName filterName, int filterIndex) throws IOException
    {
        Filter filter = FilterFactory.INSTANCE.getFilter(filterName);

        InputStream input = new BufferedInputStream(new RandomAccessFileInputStream(buffer,
                filteredStream.getPosition(), filteredStream.getLength()), BUFFER_SIZE);
        IOUtils.closeQuietly(filteredStream);
        filteredStream = new RandomAccessFileOutputStream(buffer);
        filter.encode(input, filteredStream, this, filterIndex);
        IOUtils.closeQuietly(input);
    }

    /**
     * This will return the filters to apply to the byte stream. The method will return - null if no filters are to be
     * applied - a COSName if one filter is to be applied - a COSArray containing COSNames if multiple filters are to be
     * applied
     *
     * @return the COSBase object representing the filters
     */
    public COSBase getFilters()
    {
        return getDictionaryObject(COSName.FILTER);
    }

    /**
     * Creates a new stream for which filtered byte should be written to. You probably don't want this but want to use
     * the createUnfilteredStream, which is used to write raw bytes to.
     *
     * @return A stream that can be written to.
     * @throws IOException If there is an error creating the stream.
     */
    public OutputStream createFilteredStream() throws IOException
    {
        IOUtils.closeQuietly(unFilteredStream);
        unFilteredStream = null;
        IOUtils.closeQuietly(filteredStream);
        filteredStream = new RandomAccessFileOutputStream(buffer);
        return new BufferedOutputStream(filteredStream, BUFFER_SIZE);
    }

    /**
     * set the filters to be applied to the stream.
     *
     * @param filters The filters to set on this stream.
     * @throws IOException If there is an error clearing the old filters.
     */
    public void setFilters(COSBase filters) throws IOException
    {
        if (unFilteredStream == null)
        {
            // don't lose stream contents
            doDecode();
        }
        setItem(COSName.FILTER, filters);
        // kill cached filtered streams
        IOUtils.closeQuietly(filteredStream);
        filteredStream = null;
    }

    /**
     * This will create an output stream that can be written to.
     *
     * @return An output stream which raw data bytes should be written to.
     *
     * @throws IOException If there is an error creating the stream.
     */
    public OutputStream createUnfilteredStream() throws IOException
    {
        IOUtils.closeQuietly(filteredStream);
        filteredStream = null;
        IOUtils.closeQuietly(unFilteredStream);
        unFilteredStream = new RandomAccessFileOutputStream(buffer);
        return new BufferedOutputStream(unFilteredStream, BUFFER_SIZE);
    }

    @Override
    public void close() throws IOException
    {
        IOUtils.close(buffer);
        IOUtils.close(filteredStream);
        IOUtils.close(unFilteredStream);
    }
}
