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
package org.sejda.sambox.input;

import static java.util.Objects.nonNull;
import static java.util.Optional.ofNullable;
import static org.sejda.io.CountingWritableByteChannel.from;
import static org.sejda.sambox.cos.DirectCOSObject.asDirectObject;
import static org.sejda.sambox.util.SpecVersionUtils.V1_4;
import static org.sejda.sambox.util.SpecVersionUtils.isAtLeast;
import static org.sejda.util.RequireUtils.requireNotBlank;
import static org.sejda.util.RequireUtils.requireNotNullArg;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.sejda.io.CountingWritableByteChannel;
import org.sejda.sambox.cos.COSArray;
import org.sejda.sambox.cos.COSDictionary;
import org.sejda.sambox.cos.COSName;
import org.sejda.sambox.cos.COSNull;
import org.sejda.sambox.cos.COSObjectKey;
import org.sejda.sambox.cos.COSObjectable;
import org.sejda.sambox.cos.DirectCOSObject;
import org.sejda.sambox.cos.IndirectCOSObjectIdentifier;
import org.sejda.sambox.cos.IndirectCOSObjectReference;
import org.sejda.sambox.output.IncrementablePDDocumentWriter;
import org.sejda.sambox.output.WriteOption;
import org.sejda.sambox.pdmodel.PDDocument;
import org.sejda.sambox.util.Version;
import org.sejda.sambox.xref.FileTrailer;
import org.sejda.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Andrea Vacondio
 *
 */
public class IncrementablePDDocument implements Closeable
{
    private static final Logger LOG = LoggerFactory.getLogger(IncrementablePDDocument.class);

    private Map<IndirectCOSObjectIdentifier, COSObjectable> replacements = new HashMap<>();
    private PDDocument incremented;
    public final COSParser parser;

    IncrementablePDDocument(PDDocument incremented, COSParser parser)
    {
        requireNotNullArg(incremented, "Incremented document cannot be null");
        this.incremented = incremented;
        this.parser = parser;
    }

    public PDDocument incremented()
    {
        return incremented;
    }

    public FileTrailer trailer()
    {
        return incremented.getDocument().getTrailer();
    }

    public InputStream incrementedAsStream() throws IOException
    {
        parser.source().position(0);
        return parser.source().asInputStream();
    }

    /**
     * @return the highest object reference in the document that is being incrementally updated
     */
    public COSObjectKey highestExistingReference()
    {
        return parser.provider().highestKey();
    }

    /**
     * @return a list of {@link IndirectCOSObjectReference} to be written as replacements for this incremental update
     */
    public List<IndirectCOSObjectReference> replacements()
    {

        return replacements.entrySet().stream()
                .map(e -> new IndirectCOSObjectReference(e.getKey().objectIdentifier.objectNumber(),
                        e.getKey().objectIdentifier.generation(), e.getValue().getCOSObject()))
                .collect(Collectors.toList());
    }

    /**
     * Replaces the object with the given {@link IndirectCOSObjectIdentifier} during the incremental update
     * 
     * @param toReplace
     * @param replacement
     */
    public void replace(IndirectCOSObjectIdentifier toReplace, COSObjectable replacement)
    {
        requireNotNullArg(replacement, "Missing id of the object to be replaced");
        replacements.put(toReplace, ofNullable(replacement).orElse(COSNull.NULL));
    }

    @Override
    public void close() throws IOException
    {
        incremented.close();
        IOUtils.close(parser.provider());
        IOUtils.close(parser);
    }

    private void requireOpen() throws IllegalStateException
    {
        if (!incremented.isOpen())
        {
            throw new IllegalStateException("The document is closed");
        }
    }

    /**
     * Writes the document to the given {@link File}. The document is closed once written.
     * 
     * @param file
     * @param options
     * @throws IOException
     */
    public void writeTo(File file, WriteOption... options) throws IOException
    {
        writeTo(from(file), options);
    }

    /**
     * Writes the document to the file corresponding the given file name. The document is closed once written.
     * 
     * @param filename
     * @param options
     * @throws IOException
     */
    public void writeTo(String filename, WriteOption... options) throws IOException
    {
        writeTo(from(filename), options);
    }

    /**
     * Writes the document to the given {@link WritableByteChannel}. The document is closed once written.
     * 
     * @param channel
     * @param options
     * @throws IOException
     */
    public void writeTo(WritableByteChannel channel, WriteOption... options) throws IOException
    {
        writeTo(from(channel), options);
    }

    /**
     * Writes the document to the given {@link OutputStream}. The document is closed once written.
     * 
     * @param out
     * @param options
     * @throws IOException
     */
    public void writeTo(OutputStream out, WriteOption... options) throws IOException
    {
        writeTo(from(out), options);
    }

    private void writeTo(CountingWritableByteChannel output, WriteOption... options)
            throws IOException
    {
        requireOpen();
        // TODO what if the doc has no update?
        updateDocumentInformation();
        updateId(output.toString().getBytes(StandardCharsets.ISO_8859_1));

        try (IncrementablePDDocumentWriter writer = new IncrementablePDDocumentWriter(output,
                options))
        {
            writer.write(this);
        }
        finally
        {
            IOUtils.close(this);
        }
    }

    /**
     * Updates the file identifier as defined in the chap 14.4 PDF 32000-1:2008
     * 
     * @param bytes
     */
    private void updateId(byte[] bytes)
    {
        DirectCOSObject id = asDirectObject(incremented.generateFileIdentifier(bytes));
        COSArray existingId = incremented.getDocument().getTrailer().getCOSObject()
                .getDictionaryObject(COSName.ID, COSArray.class);
        if (nonNull(existingId) && existingId.size() == 2)
        {
            existingId.set(1, id);
            if (existingId.hasId())
            {
                replacements.put(existingId.id(), existingId);
            }
        }
        else
        {
            incremented.getDocument().getTrailer().getCOSObject().setItem(COSName.ID,
                    asDirectObject(new COSArray(id, id)));
        }
    }

    private void updateDocumentInformation()
    {
        incremented.getDocumentInformation()
                .setProducer("SAMBox " + Version.getVersion() + " (www.sejda.org)");
        incremented.getDocumentInformation().setModificationDate(Calendar.getInstance());
        COSDictionary info = incremented.getDocumentInformation().getCOSObject();
        if (info.hasId())
        {
            replacements.put(info.id(), info);
        }
    }

    public void requireMinVersion(String version)
    {
        if (!isAtLeast(incremented.getVersion(), version))
        {
            LOG.debug("Minimum spec version required is {}", version);
            setVersion(version);
        }
    }

    public void setVersion(String newVersion)
    {
        requireOpen();
        requireNotBlank(newVersion, "Spec version cannot be blank");
        int compare = incremented.getVersion().compareTo(newVersion);
        if (compare > 0)
        {
            LOG.info("Spec version downgrade not allowed");
        }
        else if (compare < 0 && isAtLeast(newVersion, V1_4))
        {
            COSDictionary catalog = incremented.getDocument().getCatalog();
            catalog.setName(COSName.VERSION, newVersion);
            replacements.put(catalog.id(), catalog);
        }
        else
        {
            LOG.warn(
                    "Sepc version must be at least 1.4 to be set as catalog entry in an incremental update");
        }
    }

}
