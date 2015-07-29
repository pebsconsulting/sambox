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
package org.sejda.sambox.output;

import static org.sejda.sambox.util.CharUtils.isDigit;
import static org.sejda.sambox.util.CharUtils.isLetter;
import static org.sejda.util.RequireUtils.requireNotNullArg;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import org.sejda.io.BufferedCountingChannelWriter;
import org.sejda.io.CountingWritableByteChannel;
import org.sejda.sambox.cos.COSArray;
import org.sejda.sambox.cos.COSBase;
import org.sejda.sambox.cos.COSBoolean;
import org.sejda.sambox.cos.COSDictionary;
import org.sejda.sambox.cos.COSDocument;
import org.sejda.sambox.cos.COSFloat;
import org.sejda.sambox.cos.COSInteger;
import org.sejda.sambox.cos.COSName;
import org.sejda.sambox.cos.COSNull;
import org.sejda.sambox.cos.COSStream;
import org.sejda.sambox.cos.COSString;
import org.sejda.sambox.cos.IndirectCOSObjectReference;
import org.sejda.sambox.util.Charsets;
import org.sejda.util.IOUtils;

/**
 * Default implementation of a {@link COSWriter} that writes COS objects to the given
 * {@link CountingWritableByteChannel} or {@link BufferedCountingChannelWriter}
 * 
 * @author Andrea Vacondio
 */
class DefaultCOSWriter implements COSWriter
{
    protected static final byte SPACE = 0x20;
    private static final byte[] CRLF = { '\r', '\n' };
    private static final byte SOLIDUS = 0x2F;
    private static final byte REVERSE_SOLIDUS = 0x5C;
    private static final byte NUMBER_SIGN = 0x23;
    private static final byte LESS_THEN = 0x3C;
    private static final byte GREATER_THEN = 0x3E;
    private static final byte LEFT_PARENTHESIS = 0x28;
    private static final byte RIGHT_PARENTHESIS = 0x29;
    private static final byte LEFT_SQUARE_BRACKET = 0x5B;
    private static final byte RIGHT_SQUARE_BRACKET = 0x5D;
    private static final byte[] STREAM = "stream".getBytes(Charsets.US_ASCII);
    private static final byte[] ENDSTREAM = "endstream".getBytes(Charsets.US_ASCII);
    private BufferedCountingChannelWriter writer;

    public DefaultCOSWriter(CountingWritableByteChannel channel)
    {
        requireNotNullArg(channel, "Cannot write to a null channel");
        this.writer = new BufferedCountingChannelWriter(channel);
    }

    public DefaultCOSWriter(BufferedCountingChannelWriter writer)
    {
        requireNotNullArg(writer, "Cannot write to a null writer");
        this.writer = writer;
    }

    @Override
    public void visit(COSArray value) throws IOException
    {
        writer.write(LEFT_SQUARE_BRACKET);
        for (Iterator<COSBase> i = value.iterator(); i.hasNext();)
        {
            COSBase current = i.next();
            Optional.ofNullable(current).orElse(COSNull.NULL).accept(this);
            if (i.hasNext())
            {
                writer.write(SPACE);
            }
        }
        writer.write(RIGHT_SQUARE_BRACKET);
        writeComplexObjectSeparator();
    }

    @Override
    public void visit(COSBoolean value) throws IOException
    {
        writer.write(value.toString());
    }

    @Override
    public void visit(COSDictionary dictionary) throws IOException
    {
        writer.write(LESS_THEN);
        writer.write(LESS_THEN);
        writer.writeEOL();
        for (Map.Entry<COSName, COSBase> entry : dictionary.entrySet())
        {
            COSBase value = entry.getValue();
            if (value != null)
            {
                entry.getKey().accept(this);
                writer.write(SPACE);
                entry.getValue().accept(this);
                writer.writeEOL();
            }
        }
        writer.write(GREATER_THEN);
        writer.write(GREATER_THEN);
        writeComplexObjectSeparator();
    }

    @Override
    public void visit(COSFloat value) throws IOException
    {
        writer.write(value.toString());
    }

    @Override
    public void visit(COSInteger value) throws IOException
    {
        writer.write(value.toString());
    }

    @Override
    public void visit(COSName value) throws IOException
    {
        writer.write(SOLIDUS);
        byte[] bytes = value.getName().getBytes(Charsets.US_ASCII);
        for (int i = 0; i < bytes.length; i++)
        {
            int current = bytes[i] & 0xFF;
            if (isLetter(current) || isDigit(current))
            {
                writer.write(bytes[i]);
            }
            else
            {
                writer.write(NUMBER_SIGN);
                writer.write(String.format("%02X", current).getBytes(Charsets.US_ASCII));
            }
        }
    }

    @Override
    public void visit(COSNull value) throws IOException
    {
        writer.write("null".getBytes(Charsets.US_ASCII));
    }

    @Override
    public void visit(COSStream value) throws IOException
    {
        value.setLong(COSName.LENGTH, value.getFilteredLength());
        visit((COSDictionary) value);
        writer.write(STREAM);
        writer.write(CRLF);
        writer.write(value.getFilteredStream());
        IOUtils.close(value);
        writer.write(CRLF);
        writer.write(ENDSTREAM);
        writeComplexObjectSeparator();
    }

    @Override
    public void visit(COSString value) throws IOException
    {
        if (value.isForceHexForm())
        {
            writer.write(LESS_THEN);
            writer.write(value.toHexString());
            writer.write(GREATER_THEN);
        }
        else
        {
            writer.write(LEFT_PARENTHESIS);
            for (byte b : value.getBytes())
            {
                switch (b)
                {
                case '\n':
                case '\r':
                case '\t':
                case '\b':
                case '\f':
                case '(':
                case ')':
                case '\\':
                    writer.write(REVERSE_SOLIDUS);
                    //$FALL-THROUGH$
                default:
                    writer.write(b);
                }
            }
            writer.write(RIGHT_PARENTHESIS);
        }
    }

    @Override
    public void visit(IndirectCOSObjectReference value) throws IOException
    {
        writer.write(value.toString());
    }

    @Override
    public void visit(COSDocument value)
    {
        // nothing to do
    }

    @Override
    public BufferedCountingChannelWriter writer()
    {
        return this.writer;
    }
}