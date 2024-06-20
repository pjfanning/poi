/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.poi.ooxml.extractor;

import org.apache.poi.util.XMLHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.stream.XMLResolver;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Utility functions for reading XML.
 */
public class ExtractorSAXUtils implements Serializable {

    /**
     * Default size for the pool of SAX Parsers
     * and the pool of DOM builders
     */
    public static final int DEFAULT_POOL_SIZE = 10;
    public static final int DEFAULT_MAX_ENTITY_EXPANSIONS = 20;
    /**
     * Serial version UID
     */
    private static final long serialVersionUID = 6110455808615143122L;
    private static final Logger LOG = LoggerFactory.getLogger(ExtractorSAXUtils.class);
    private static final String XERCES_SECURITY_MANAGER = "org.apache.xerces.util.SecurityManager";
    private static final String XERCES_SECURITY_MANAGER_PROPERTY =
            "http://apache.org/xml/properties/security-manager";

    private static final AtomicBoolean HAS_WARNED_STAX = new AtomicBoolean(false);
    private static final ContentHandler IGNORING_CONTENT_HANDLER = new DefaultHandler();
    private static final DTDHandler IGNORING_DTD_HANDLER = new DTDHandler() {
        @Override
        public void notationDecl(String name, String publicId, String systemId)
                throws SAXException {

        }

        @Override
        public void unparsedEntityDecl(String name, String publicId, String systemId,
                                       String notationName) throws SAXException {

        }
    };
    private static final ErrorHandler IGNORING_ERROR_HANDLER = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) throws SAXException {

        }

        @Override
        public void error(SAXParseException exception) throws SAXException {

        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {

        }
    };
    private static final String JAXP_ENTITY_EXPANSION_LIMIT_KEY = "jdk.xml.entityExpansionLimit";
    //TODO: figure out if the rw lock is any better than a simple lock
    private static final ReentrantReadWriteLock SAX_READ_WRITE_LOCK = new ReentrantReadWriteLock();
    private static final ReentrantReadWriteLock DOM_READ_WRITE_LOCK = new ReentrantReadWriteLock();
    private static final AtomicInteger POOL_GENERATION = new AtomicInteger();
    private static final EntityResolver IGNORING_SAX_ENTITY_RESOLVER =
            (publicId, systemId) -> new InputSource(new StringReader(""));
    private static final XMLResolver IGNORING_STAX_ENTITY_RESOLVER =
            (publicID, systemID, baseURI, namespace) -> "";
    /**
     * Parser pool size
     */
    private static int POOL_SIZE = DEFAULT_POOL_SIZE;
    private static long LAST_LOG = -1;
    private static volatile int MAX_ENTITY_EXPANSIONS = determineMaxEntityExpansions();
    private static ArrayBlockingQueue<PoolSAXParser> SAX_PARSERS =
            new ArrayBlockingQueue<>(POOL_SIZE);
    private static ArrayBlockingQueue<PoolDOMBuilder> DOM_BUILDERS =
            new ArrayBlockingQueue<>(POOL_SIZE);

    static {
        try {
            setPoolSize(POOL_SIZE);
        } catch (ExtractorException e) {
            throw new RuntimeException("problem initializing SAXParser and DOMBuilder pools", e);
        }
    }

    private static int determineMaxEntityExpansions() {
        String expansionLimit = System.getProperty(JAXP_ENTITY_EXPANSION_LIMIT_KEY);
        if (expansionLimit != null) {
            try {
                return Integer.parseInt(expansionLimit);
            } catch (NumberFormatException e) {
                LOG.warn(
                        "Couldn't parse an integer for the entity expansion limit: {}; " +
                                "backing off to default: {}",
                        expansionLimit, DEFAULT_MAX_ENTITY_EXPANSIONS);
            }
        }
        return DEFAULT_MAX_ENTITY_EXPANSIONS;
    }

    /**
     * Returns the SAX parser specified in this parsing context. If a parser
     * is not explicitly specified, then one is created using the specified
     * or the default SAX parser factory.
     * <p>
     * If you call reset() on the parser, make sure to replace the
     * SecurityManager which will be cleared by xerces2 on reset().
     * </p>
     *
     * @return SAX parser
     * @throws ExtractorException if a SAX parser could not be created
     * @since POI 5.2.3
     */
    static SAXParser getSAXParser() throws ExtractorException {
        try {
            SAXParser parser = XMLHelper.getSaxParserFactory().newSAXParser();
            trySetXercesSecurityManager(parser);
            return parser;
        } catch (ParserConfigurationException e) {
            throw new ExtractorException("Unable to configure a SAX parser", e);
        } catch (SAXException e) {
            throw new ExtractorException("Unable to create a SAX parser", e);
        }
    }

    /**
     * This checks context for a user specified {@link DocumentBuilder}.
     * If one is not found, this reuses a DocumentBuilder from the pool.
     *
     * @param is      InputStream to parse
     * @param context context to use
     * @return a document
     * @throws ExtractorException
     * @throws IOException
     * @throws SAXException
     * @since Apache Tika 1.19
     */
    public static Document buildDOM(InputStream is, ParseContext context)
            throws ExtractorException, IOException, SAXException {
        DocumentBuilder builder = context.get(DocumentBuilder.class);
        PoolDOMBuilder poolBuilder = null;
        if (builder == null) {
            poolBuilder = acquireDOMBuilder();
            builder = poolBuilder.getDocumentBuilder();
        }

        try {
            return builder.parse(is);
        } finally {
            if (poolBuilder != null) {
                releaseDOMBuilder(poolBuilder);
            }
        }
    }

    /**
     * Builds a Document with a DocumentBuilder from the pool
     *
     * @param path path to parse
     * @return a document
     * @throws ExtractorException
     * @throws IOException
     * @throws SAXException
     * @since Apache Tika 1.19.1
     */
    public static Document buildDOM(Path path) throws ExtractorException, IOException, SAXException {
        try (InputStream is = Files.newInputStream(path)) {
            return buildDOM(is);
        }
    }

    /**
     * Builds a Document with a DocumentBuilder from the pool
     *
     * @param uriString uriString to process
     * @return a document
     * @throws ExtractorException
     * @throws IOException
     * @throws SAXException
     * @since Apache Tika 1.19.1
     */
    public static Document buildDOM(String uriString)
            throws ExtractorException, IOException, SAXException {
        PoolDOMBuilder builder = acquireDOMBuilder();
        try {
            return builder.getDocumentBuilder().parse(uriString);
        } finally {
            releaseDOMBuilder(builder);
        }
    }

    /**
     * Builds a Document with a DocumentBuilder from the pool
     *
     * @return a document
     * @throws ExtractorException
     * @throws IOException
     * @throws SAXException
     * @since Apache Tika 1.19.1
     */
    public static Document buildDOM(InputStream is)
            throws ExtractorException, IOException, SAXException {
        PoolDOMBuilder builder = acquireDOMBuilder();
        try {
            return builder.getDocumentBuilder().parse(is);
        } finally {
            releaseDOMBuilder(builder);
        }
    }

    /**
     * This checks context for a user specified {@link SAXParser}.
     * If one is not found, this reuses a SAXParser from the pool.
     *
     * @param is             InputStream to parse
     * @param contentHandler handler to use; this wraps a {@link OfflineContentHandler}
     *                       to the content handler as an extra layer of defense against
     *                       external entity vulnerabilities
     * @param context        context to use
     * @return
     * @throws ExtractorException
     * @throws IOException
     * @throws SAXException
     * @since POI 5.2.3
     */
    public static void parseSAX(InputStream is, ContentHandler contentHandler, ParseContext context)
            throws ExtractorException, IOException, SAXException {
        SAXParser saxParser = context.get(SAXParser.class);
        PoolSAXParser poolSAXParser = null;
        if (saxParser == null) {
            poolSAXParser = acquireSAXParser();
            saxParser = poolSAXParser.getSAXParser();
        }
        try {
            saxParser.parse(is, new OfflineContentHandler(contentHandler));
        } finally {
            if (poolSAXParser != null) {
                releaseParser(poolSAXParser);
            }
        }
    }

    /**
     * Acquire a SAXParser from the pool.  Make sure to
     * {@link #releaseDOMBuilder(PoolDOMBuilder)} in
     * a <code>finally</code> block every time you call this.
     *
     * @return a DocumentBuilder
     * @throws ExtractorException
     */
    private static PoolDOMBuilder acquireDOMBuilder() throws ExtractorException {
        int waiting = 0;
        long lastWarn = -1;
        while (true) {
            PoolDOMBuilder builder = null;
            DOM_READ_WRITE_LOCK.readLock().lock();
            try {
                builder = DOM_BUILDERS.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                throw new ExtractorException("interrupted while waiting for DOMBuilder", e);
            } finally {
                DOM_READ_WRITE_LOCK.readLock().unlock();
            }
            if (builder != null) {
                return builder;
            }
            if (lastWarn < 0 || System.currentTimeMillis() - lastWarn > 1000) {
                //avoid spamming logs
                LOG.warn("Contention waiting for a DOMParser. " +
                        "Consider increasing the XMLReaderUtils.POOL_SIZE");
                lastWarn = System.currentTimeMillis();
            }
            waiting++;

            if (waiting > 3000) {
                //freshen the pool.  Something went very wrong...
                setPoolSize(POOL_SIZE);
                //better to get an exception than have permahang by a bug in one of our parsers
                throw new ExtractorException("Waited more than 5 minutes for a DocumentBuilder; " +
                        "This could indicate that a parser has not correctly released its " +
                        "DocumentBuilder. " +
                        "Please report this to the Tika team: dev@tika.apache.org");

            }
        }
    }

    /**
     * Return parser to the pool for reuse.
     *
     * @param builder builder to return
     */
    private static void releaseDOMBuilder(PoolDOMBuilder builder) {
        if (builder.getPoolGeneration() != POOL_GENERATION.get()) {
            return;
        }
        try {
            builder.reset();
        } catch (UnsupportedOperationException e) {
            //ignore
        }
        DOM_READ_WRITE_LOCK.readLock().lock();
        try {
            //if there are extra parsers (e.g. after a reset of the pool to a smaller size),
            // this parser will not be added and will then be gc'd
            boolean success = DOM_BUILDERS.offer(builder);
            if (!success) {
                LOG.warn(
                        "DocumentBuilder not taken back into pool.  If you haven't resized the " +
                                "pool, this could be a sign that there are more calls to " +
                                "'acquire' than to 'release'");
            }
        } finally {
            DOM_READ_WRITE_LOCK.readLock().unlock();
        }
    }

    /**
     * Acquire a SAXParser from the pool.  Make sure to
     * {@link #releaseParser(PoolSAXParser)} in
     * a <code>finally</code> block every time you call this.
     *
     * @return a SAXParser
     * @throws ExtractorException
     */
    private static PoolSAXParser acquireSAXParser() throws ExtractorException {
        int waiting = 0;
        long lastWarn = -1;
        while (true) {
            PoolSAXParser parser = null;
            SAX_READ_WRITE_LOCK.readLock().lock();
            try {
                parser = SAX_PARSERS.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                throw new ExtractorException("interrupted while waiting for SAXParser", e);
            } finally {
                SAX_READ_WRITE_LOCK.readLock().unlock();
            }
            if (parser != null) {
                return parser;
            }
            if (lastWarn < 0 || System.currentTimeMillis() - lastWarn > 1000) {
                //avoid spamming logs
                LOG.warn("Contention waiting for a SAXParser. " +
                        "Consider increasing the XMLReaderUtils.POOL_SIZE");
                lastWarn = System.currentTimeMillis();
            }
            waiting++;
            if (waiting > 3000) {
                //freshen the pool.  Something went very wrong...
                setPoolSize(POOL_SIZE);
                //better to get an exception than have permahang by a bug in one of our parsers
                throw new ExtractorException("Waited more than 5 minutes for a SAXParser; " +
                        "This could indicate that a parser has not correctly released its " +
                        "SAXParser. Please report this to the Tika team: dev@tika.apache.org");

            }
        }
    }

    /**
     * Return parser to the pool for reuse
     *
     * @param parser parser to return
     */
    private static void releaseParser(PoolSAXParser parser) {
        try {
            parser.reset();
        } catch (UnsupportedOperationException e) {
            //TIKA-3009 -- we really shouldn't have to do this... :(
        }
        //if this is a different generation, don't put it back
        //in the pool
        if (parser.getGeneration() != POOL_GENERATION.get()) {
            return;
        }
        SAX_READ_WRITE_LOCK.readLock().lock();
        try {
            //if there are extra parsers (e.g. after a reset of the pool to a smaller size),
            // this parser will not be added and will then be gc'd
            boolean success = SAX_PARSERS.offer(parser);
            if (!success) {
                LOG.warn(
                        "SAXParser not taken back into pool.  If you haven't resized the pool " +
                                "this could be a sign that there are more calls to 'acquire' " +
                                "than to 'release'");
            }
        } finally {
            SAX_READ_WRITE_LOCK.readLock().unlock();
        }
    }

    private static void trySetXercesSecurityManager(SAXParser parser) {
        //from POI
        // Try built-in JVM one first, standalone if not
        for (String securityManagerClassName : new String[]{
                //"com.sun.org.apache.xerces.internal.util.SecurityManager",
                XERCES_SECURITY_MANAGER}) {
            try {
                Object mgr = Class.forName(securityManagerClassName).newInstance();
                Method setLimit = mgr.getClass().getMethod("setEntityExpansionLimit", Integer.TYPE);
                setLimit.invoke(mgr, MAX_ENTITY_EXPANSIONS);

                parser.setProperty(XERCES_SECURITY_MANAGER_PROPERTY, mgr);
                // Stop once one can be setup without error
                return;
            } catch (ClassNotFoundException e) {
                // continue without log, this is expected in some setups
            } catch (Throwable e) {
                // NOSONAR - also catch things like NoClassDefError here
                // throttle the log somewhat as it can spam the log otherwise
                if (System.currentTimeMillis() > LAST_LOG + TimeUnit.MINUTES.toMillis(5)) {
                    LOG.warn(
                            "SAX Security Manager could not be setup [log suppressed for 5 " +
                                    "minutes]",
                            e);
                    LAST_LOG = System.currentTimeMillis();
                }
            }
        }

        // separate old version of Xerces not found => use the builtin way of setting the property
        try {
            parser.setProperty("http://www.oracle.com/xml/jaxp/properties/entityExpansionLimit",
                    MAX_ENTITY_EXPANSIONS);
        } catch (SAXException e) {     // NOSONAR - also catch things like NoClassDefError here
            // throttle the log somewhat as it can spam the log otherwise
            if (System.currentTimeMillis() > LAST_LOG + TimeUnit.MINUTES.toMillis(5)) {
                LOG.warn("SAX Security Manager could not be setup [log suppressed for 5 minutes]",
                        e);
                LAST_LOG = System.currentTimeMillis();
            }
        }
    }

    public static int getPoolSize() {
        return POOL_SIZE;
    }

    /**
     * Set the pool size for cached XML parsers.  This has a side
     * effect of locking the pool, and rebuilding the pool from
     * scratch with the most recent settings, such as {@link #MAX_ENTITY_EXPANSIONS}
     *
     * @param poolSize
     * @since Apache Tika 1.19
     */
    public static void setPoolSize(int poolSize) throws ExtractorException {
        //stop the world with a write lock.
        //parsers that are currently in use will be offered later (once the lock is released),
        //but not accepted and will be gc'd.  We have to do this locking and
        //the read locking in case one thread resizes the pool when the
        //parsers have already started.  We could have an NPE on SAX_PARSERS
        //if we didn't lock.
        SAX_READ_WRITE_LOCK.writeLock().lock();
        try {
            //free up any resources before emptying SAX_PARSERS
            for (PoolSAXParser parser : SAX_PARSERS) {
                parser.reset();
            }
            SAX_PARSERS.clear();
            SAX_PARSERS = new ArrayBlockingQueue<>(poolSize);
            int generation = POOL_GENERATION.incrementAndGet();
            for (int i = 0; i < poolSize; i++) {
                try {
                    SAX_PARSERS.offer(buildPoolParser(generation,
                            XMLHelper.getSaxParserFactory().newSAXParser()));
                } catch (SAXException | ParserConfigurationException e) {
                    throw new ExtractorException("problem creating sax parser", e);
                }
            }
        } finally {
            SAX_READ_WRITE_LOCK.writeLock().unlock();
        }

        DOM_READ_WRITE_LOCK.writeLock().lock();
        try {
            DOM_BUILDERS.clear();
            DOM_BUILDERS = new ArrayBlockingQueue<>(poolSize);
            for (int i = 0; i < poolSize; i++) {
                DOM_BUILDERS.offer(new PoolDOMBuilder(POOL_GENERATION.get(), XMLHelper.newDocumentBuilder()));
            }
        } finally {
            DOM_READ_WRITE_LOCK.writeLock().unlock();
        }
        POOL_SIZE = poolSize;
    }

    public static int getMaxEntityExpansions() {
        return MAX_ENTITY_EXPANSIONS;
    }

    /**
     * Set the maximum number of entity expansions allowable in SAX/DOM/StAX parsing.
     * <b>NOTE:</b>A value less than or equal to zero indicates no limit.
     * This will override the system property {@link #JAXP_ENTITY_EXPANSION_LIMIT_KEY}
     * and the {@link #DEFAULT_MAX_ENTITY_EXPANSIONS} value for allowable entity expansions
     * <p>
     * <b>NOTE:</b> To trigger a rebuild of the pool of parsers with this setting,
     * the client must call {@link #setPoolSize(int)} to rebuild the SAX and DOM parsers
     * with this setting.
     * </p>
     *
     * @param maxEntityExpansions -- maximum number of allowable entity expansions
     * @since Apache Tika 1.19
     */
    public static void setMaxEntityExpansions(int maxEntityExpansions) {
        MAX_ENTITY_EXPANSIONS = maxEntityExpansions;
    }

    /**
     * @param localName
     * @param atts
     * @return attribute value with that local name or <code>null</code> if not found
     */
    public static String getAttrValue(String localName, Attributes atts) {
        for (int i = 0; i < atts.getLength(); i++) {
            if (localName.equals(atts.getLocalName(i))) {
                return atts.getValue(i);
            }
        }
        return null;
    }

    private static PoolSAXParser buildPoolParser(int generation, SAXParser parser) {
        boolean canReset = false;
        try {
            parser.reset();
            canReset = true;
        } catch (UnsupportedOperationException e) {
            canReset = false;
        }
        boolean hasSecurityManager = false;
        try {
            Object mgr = Class.forName(XERCES_SECURITY_MANAGER).newInstance();
            Method setLimit = mgr.getClass().getMethod("setEntityExpansionLimit", Integer.TYPE);
            setLimit.invoke(mgr, MAX_ENTITY_EXPANSIONS);

            parser.setProperty(XERCES_SECURITY_MANAGER_PROPERTY, mgr);
            hasSecurityManager = true;
        } catch (SecurityException e) {
            //don't swallow security exceptions
            throw e;
        } catch (ClassNotFoundException e) {
            // continue without log, this is expected in some setups
        } catch (Throwable e) {
            // NOSONAR - also catch things like NoClassDefError here
            // throttle the log somewhat as it can spam the log otherwise
            if (System.currentTimeMillis() > LAST_LOG + TimeUnit.MINUTES.toMillis(5)) {
                LOG.warn("SAX Security Manager could not be setup [log suppressed for 5 minutes]",
                        e);
                LAST_LOG = System.currentTimeMillis();
            }
        }

        boolean canSetJaxPEntity = false;
        if (!hasSecurityManager) {
            // use the builtin way of setting the property
            try {
                parser.setProperty("http://www.oracle.com/xml/jaxp/properties/entityExpansionLimit",
                        MAX_ENTITY_EXPANSIONS);
                canSetJaxPEntity = true;
            } catch (SAXException e) {     // NOSONAR - also catch things like NoClassDefError here
                // throttle the log somewhat as it can spam the log otherwise
                if (System.currentTimeMillis() > LAST_LOG + TimeUnit.MINUTES.toMillis(5)) {
                    LOG.warn(
                            "SAX Security Manager could not be setup [log suppressed for 5 " +
                                    "minutes]",
                            e);
                    LAST_LOG = System.currentTimeMillis();
                }
            }
        }

        if (!canReset && hasSecurityManager) {
            return new XercesPoolSAXParser(generation, parser);
        } else if (canReset && hasSecurityManager) {
            return new Xerces2PoolSAXParser(generation, parser);
        } else if (canReset && !hasSecurityManager && canSetJaxPEntity) {
            return new BuiltInPoolSAXParser(generation, parser);
        } else {
            return new UnrecognizedPoolSAXParser(generation, parser);
        }

    }

    private static void clearReader(XMLReader reader) {
        if (reader == null) {
            return;
        }
        reader.setContentHandler(IGNORING_CONTENT_HANDLER);
        reader.setDTDHandler(IGNORING_DTD_HANDLER);
        reader.setEntityResolver(IGNORING_SAX_ENTITY_RESOLVER);
        reader.setErrorHandler(IGNORING_ERROR_HANDLER);
    }

    private static class PoolDOMBuilder {
        private final int poolGeneration;
        private final DocumentBuilder documentBuilder;

        PoolDOMBuilder(int poolGeneration, DocumentBuilder documentBuilder) {
            this.poolGeneration = poolGeneration;
            this.documentBuilder = documentBuilder;
        }

        public int getPoolGeneration() {
            return poolGeneration;
        }

        public DocumentBuilder getDocumentBuilder() {
            return documentBuilder;
        }

        public void reset() {
            documentBuilder.reset();
            documentBuilder.setEntityResolver(IGNORING_SAX_ENTITY_RESOLVER);
            documentBuilder.setErrorHandler(null);
        }
    }

    private abstract static class PoolSAXParser {
        final int poolGeneration;
        final SAXParser saxParser;

        PoolSAXParser(int poolGeneration, SAXParser saxParser) {
            this.poolGeneration = poolGeneration;
            this.saxParser = saxParser;
        }

        abstract void reset();

        public int getGeneration() {
            return poolGeneration;
        }

        public SAXParser getSAXParser() {
            return saxParser;
        }
    }

    private static class XercesPoolSAXParser extends PoolSAXParser {
        public XercesPoolSAXParser(int generation, SAXParser parser) {
            super(generation, parser);
        }

        @Override
        public void reset() {
            //don't do anything
            try {
                XMLReader reader = saxParser.getXMLReader();
                clearReader(reader);
            } catch (SAXException e) {
                //swallow
            }
        }
    }

    private static class Xerces2PoolSAXParser extends PoolSAXParser {
        public Xerces2PoolSAXParser(int generation, SAXParser parser) {
            super(generation, parser);
        }

        @Override
        void reset() {
            try {
                Object object = saxParser.getProperty(XERCES_SECURITY_MANAGER_PROPERTY);
                saxParser.reset();
                saxParser.setProperty(XERCES_SECURITY_MANAGER_PROPERTY, object);
            } catch (SAXException e) {
                LOG.warn("problem resetting sax parser", e);
            }
            try {
                XMLReader reader = saxParser.getXMLReader();
                clearReader(reader);
            } catch (SAXException e) {
                // ignored
            }
        }
    }

    private static class BuiltInPoolSAXParser extends PoolSAXParser {
        public BuiltInPoolSAXParser(int generation, SAXParser parser) {
            super(generation, parser);
        }

        @Override
        void reset() {
            saxParser.reset();
            try {
                XMLReader reader = saxParser.getXMLReader();
                clearReader(reader);
            } catch (SAXException e) {
                // ignored
            }
        }
    }

    private static class UnrecognizedPoolSAXParser extends PoolSAXParser {
        //if unrecognized, try to set all protections
        //and try to reset every time
        public UnrecognizedPoolSAXParser(int generation, SAXParser parser) {
            super(generation, parser);
        }

        @Override
        void reset() {
            try {
                saxParser.reset();
            } catch (UnsupportedOperationException e) {
                // ignored
            }
            try {
                XMLReader reader = saxParser.getXMLReader();
                clearReader(reader);
            } catch (SAXException e) {
                // ignored
            }
            trySetXercesSecurityManager(saxParser);
        }
    }
}