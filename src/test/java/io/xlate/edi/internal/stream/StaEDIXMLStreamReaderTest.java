/*******************************************************************************
 * Copyright 2017 xlate.io LLC, http://www.xlate.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package io.xlate.edi.internal.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringWriter;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stax.StAXSource;
import javax.xml.transform.stream.StreamResult;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.DefaultComparisonFormatter;
import org.xmlunit.diff.Diff;

import io.xlate.edi.schema.Schema;
import io.xlate.edi.schema.SchemaFactory;
import io.xlate.edi.stream.EDIInputFactory;
import io.xlate.edi.stream.EDIStreamException;
import io.xlate.edi.stream.EDIStreamFilter;
import io.xlate.edi.stream.EDIStreamReader;

public class StaEDIXMLStreamReaderTest {

    static byte[] DUMMY_X12 = ("ISA*00*          *00*          *ZZ*ReceiverID     *ZZ*Sender         *050812*1953*^*00501*508121953*0*P*:~"
            + "S01*X~"
            + "S11*X~"
            + "S12*X~"
            + "S19*X~"
            + "S09*X~"
            + "IEA*1*508121953~").getBytes();

    static byte[] TINY_X12 = ("ISA*00*          *00*          *ZZ*ReceiverID     *ZZ*Sender         *050812*1953*^*00501*508121953*0*P*:~"
            + "IEA*1*508121953~").getBytes();

    XMLStreamReader getXmlReader(String resource) throws EDIStreamException, XMLStreamException {
        EDIInputFactory factory = EDIInputFactory.newFactory();
        ClassLoader loader = getClass().getClassLoader();
        InputStream stream = loader.getResourceAsStream(resource);
        EDIStreamReader ediReader = factory.createEDIStreamReader(stream);
        return new StaEDIXMLStreamReader(ediReader);
    }

    XMLStreamReader getXmlReader(byte[] bytes) throws EDIStreamException, XMLStreamException {
        EDIInputFactory factory = EDIInputFactory.newFactory();
        factory.setProperty(EDIInputFactory.EDI_VALIDATE_CONTROL_STRUCTURE, "false");
        InputStream stream = new ByteArrayInputStream(bytes);
        EDIStreamReader ediReader = factory.createEDIStreamReader(stream);
        return new StaEDIXMLStreamReader(ediReader);
    }

    static void skipEvents(XMLStreamReader reader, int eventCount) throws XMLStreamException {
        for (int i = 0; i < eventCount; i++) {
            reader.next();
        }
    }

    @Test
    public void testCreateEDIXMLStreamReader() throws EDIStreamException, XMLStreamException {
        XMLStreamReader xmlReader = getXmlReader("x12/simple997.edi");
        Assert.assertNotNull("xmlReader was null", xmlReader);
    }

    @Test
    public void testHasNext() throws Exception {
        XMLStreamReader xmlReader = getXmlReader(DUMMY_X12);
        Assert.assertTrue(xmlReader.hasNext());
    }

    private static void assertSegmentBoundaries(XMLStreamReader xmlReader, String tag, int elementCount)
                                                                                                         throws XMLStreamException {
        Assert.assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.next());
        Assert.assertEquals(tag, xmlReader.getLocalName());
        xmlReader.require(XMLStreamConstants.START_ELEMENT, null, tag);
        skipEvents(xmlReader, 3 * elementCount);
        Assert.assertEquals(XMLStreamConstants.END_ELEMENT, xmlReader.next());
        Assert.assertEquals(tag, xmlReader.getLocalName());
        xmlReader.require(XMLStreamConstants.END_ELEMENT, null, tag);
    }

    @Test
    public void testSegmentSequence() throws Exception {
        XMLStreamReader xmlReader = getXmlReader(DUMMY_X12);

        Assert.assertEquals(XMLStreamConstants.START_DOCUMENT, xmlReader.next());
        Assert.assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.next());
        Assert.assertEquals("INTERCHANGE", xmlReader.getLocalName());

        assertSegmentBoundaries(xmlReader, "ISA", 16);
        assertSegmentBoundaries(xmlReader, "S01", 1);
        assertSegmentBoundaries(xmlReader, "S11", 1);
        assertSegmentBoundaries(xmlReader, "S12", 1);
        assertSegmentBoundaries(xmlReader, "S19", 1);
        assertSegmentBoundaries(xmlReader, "S09", 1);
        assertSegmentBoundaries(xmlReader, "IEA", 2);

        Assert.assertEquals(XMLStreamConstants.END_ELEMENT, xmlReader.next());
        Assert.assertEquals("INTERCHANGE", xmlReader.getLocalName());
        Assert.assertEquals(XMLStreamConstants.END_DOCUMENT, xmlReader.next());
    }

    @Test
    public void testGetElementText() throws Exception {
        XMLStreamReader xmlReader = getXmlReader(DUMMY_X12);

        Assert.assertEquals(XMLStreamConstants.START_DOCUMENT, xmlReader.next());
        Assert.assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.next());
        Assert.assertEquals("INTERCHANGE", xmlReader.getLocalName());

        Assert.assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.next());
        Assert.assertEquals("ISA", xmlReader.getLocalName());
        Assert.assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.next()); // ISA01;
        Assert.assertEquals("00", xmlReader.getElementText());
        Assert.assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.next()); // ISA02;
        Assert.assertEquals("          ", xmlReader.getElementText());
        Assert.assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.next()); // ISA03;
        Assert.assertEquals("00", xmlReader.getElementText());
    }

    @SuppressWarnings("static-method")
    private void assertElement(XMLStreamReader xmlReader, String tag, String value) throws Exception {
        Assert.assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.next());
        Assert.assertEquals(tag, xmlReader.getLocalName());
        Assert.assertEquals(value, xmlReader.getElementText());
        Assert.assertEquals(XMLStreamConstants.END_ELEMENT, xmlReader.getEventType());
        Assert.assertEquals(tag, xmlReader.getLocalName());
    }

    @Test
    public void testElementEvents() throws Exception {
        XMLStreamReader xmlReader = getXmlReader(TINY_X12);
        Assert.assertEquals(XMLStreamConstants.START_DOCUMENT, xmlReader.next());

        Assert.assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.next());
        Assert.assertEquals("INTERCHANGE", xmlReader.getLocalName());

        Assert.assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.next());
        Assert.assertEquals("ISA", xmlReader.getLocalName());
        assertElement(xmlReader, "ISA01", "00");
        assertElement(xmlReader, "ISA02", "          ");
        assertElement(xmlReader, "ISA03", "00");
        assertElement(xmlReader, "ISA04", "          ");
        assertElement(xmlReader, "ISA05", "ZZ");
        assertElement(xmlReader, "ISA06", "ReceiverID     ");
        assertElement(xmlReader, "ISA07", "ZZ");
        assertElement(xmlReader, "ISA08", "Sender         ");
        assertElement(xmlReader, "ISA09", "050812");
        assertElement(xmlReader, "ISA10", "1953");
        assertElement(xmlReader, "ISA11", "^");
        assertElement(xmlReader, "ISA12", "00501");
        assertElement(xmlReader, "ISA13", "508121953");
        assertElement(xmlReader, "ISA14", "0");
        assertElement(xmlReader, "ISA15", "P");
        assertElement(xmlReader, "ISA16", ":");
        Assert.assertEquals(XMLStreamConstants.END_ELEMENT, xmlReader.next());
        Assert.assertEquals("ISA", xmlReader.getLocalName());

        Assert.assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.next());
        Assert.assertEquals("IEA", xmlReader.getLocalName());
        assertElement(xmlReader, "IEA01", "1");
        assertElement(xmlReader, "IEA02", "508121953");
        Assert.assertEquals(XMLStreamConstants.END_ELEMENT, xmlReader.next());
        Assert.assertEquals("IEA", xmlReader.getLocalName());

        Assert.assertEquals(XMLStreamConstants.END_ELEMENT, xmlReader.next());
        Assert.assertEquals("INTERCHANGE", xmlReader.getLocalName());
        Assert.assertEquals(XMLStreamConstants.END_DOCUMENT, xmlReader.next());

        Assert.assertFalse(xmlReader.hasNext());
        xmlReader.close();
    }

    @Test
    public void testWriteXml() throws Exception {
        XMLStreamReader xmlReader = getXmlReader("x12/extraDelimiter997.edi");
        xmlReader.next(); // Per StAXSource JavaDoc, put in START_DOCUMENT state
        TransformerFactory factory = TransformerFactory.newInstance();
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter result = new StringWriter();
        transformer.transform(new StAXSource(xmlReader), new StreamResult(result));
        String resultString = result.toString();
        Diff d = DiffBuilder.compare(Input.fromFile("src/test/resources/x12/extraDelimiter997.xml"))
                   .withTest(resultString).build();
        assertTrue("XML unexpectedly different:\n" + d.toString(new DefaultComparisonFormatter()), !d.hasDifferences());
    }

    @Test
    public void testSchemaValidatedInput() throws Exception {
        EDIInputFactory factory = EDIInputFactory.newFactory();
        InputStream stream = getClass().getClassLoader().getResourceAsStream("x12/simple997.edi");
        SchemaFactory schemaFactory = SchemaFactory.newFactory();
        Schema schema = schemaFactory.createSchema(getClass().getClassLoader().getResource("x12/EDISchema997.xml"));
        EDIStreamReader ediReader = factory.createEDIStreamReader(stream);
        EDIStreamFilter ediFilter = new EDIStreamFilter() {
            @Override
            public boolean accept(EDIStreamReader reader) {
                switch (reader.getEventType()) {
                case START_INTERCHANGE:
                case START_GROUP:
                case START_TRANSACTION:
                case START_LOOP:
                case START_SEGMENT:
                case END_SEGMENT:
                case END_LOOP:
                case END_TRANSACTION:
                case END_GROUP:
                case END_INTERCHANGE:
                    return true;
                default:
                    return false;
                }
            }
        };
        ediReader = factory.createFilteredReader(ediReader, ediFilter);
        XMLStreamReader xmlReader = new StaEDIXMLStreamReader(ediReader);

        assertEquals(XMLStreamConstants.START_DOCUMENT, xmlReader.next());
        assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.next());
        assertEquals("INTERCHANGE", xmlReader.getLocalName());

        assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.next());
        assertEquals("ISA", xmlReader.getLocalName());
        assertEquals(XMLStreamConstants.END_ELEMENT, xmlReader.next());
        assertEquals("ISA", xmlReader.getLocalName());

        assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.nextTag());
        assertEquals("GROUP", xmlReader.getLocalName());

        assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.nextTag());
        assertEquals("GS", xmlReader.getLocalName());
        assertEquals(XMLStreamConstants.END_ELEMENT, xmlReader.next());
        assertEquals("GS", xmlReader.getLocalName());

        assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.nextTag());
        assertEquals("ST", xmlReader.getLocalName());
        assertEquals(XMLStreamConstants.END_ELEMENT, xmlReader.next());
        assertEquals("ST", xmlReader.getLocalName());

        assertEquals(XMLStreamConstants.START_ELEMENT, xmlReader.nextTag());
        assertEquals("TRANSACTION", xmlReader.getLocalName());
        ediReader.setTransactionSchema(schema);

    }

    @Test
    public void testUnsupportedOperations() throws Exception {
        EDIStreamReader ediReader = Mockito.mock(EDIStreamReader.class);
        XMLStreamReader xmlReader = new StaEDIXMLStreamReader(ediReader);
        try {
            xmlReader.getNamespaceURI();
            fail("UnsupportedOperationExpected");
        } catch (UnsupportedOperationException e) {}
        try {
            xmlReader.getAttributeValue("", "");
            fail("UnsupportedOperationExpected");
        } catch (UnsupportedOperationException e) {}
        try {
            xmlReader.getAttributeName(0);
            fail("UnsupportedOperationExpected");
        } catch (UnsupportedOperationException e) {}
        try {
            xmlReader.getAttributeNamespace(0);
            fail("UnsupportedOperationExpected");
        } catch (UnsupportedOperationException e) {}
        try {
            xmlReader.getAttributeLocalName(0);
            fail("UnsupportedOperationExpected");
        } catch (UnsupportedOperationException e) {}
        try {
            xmlReader.getAttributePrefix(0);
            fail("UnsupportedOperationExpected");
        } catch (UnsupportedOperationException e) {}
        try {
            xmlReader.getAttributeType(0);
            fail("UnsupportedOperationExpected");
        } catch (UnsupportedOperationException e) {}
        try {
            xmlReader.getAttributeValue(0);
            fail("UnsupportedOperationExpected");
        } catch (UnsupportedOperationException e) {}
        try {
            xmlReader.isAttributeSpecified(0);
            fail("UnsupportedOperationExpected");
        } catch (UnsupportedOperationException e) {}
        try {
            xmlReader.getNamespacePrefix(0);
            fail("UnsupportedOperationExpected");
        } catch (UnsupportedOperationException e) {}
        try {
            xmlReader.getNamespaceURI(0);
            fail("UnsupportedOperationExpected");
        } catch (UnsupportedOperationException e) {}
        try {
            xmlReader.getNamespaceContext();
            fail("UnsupportedOperationExpected");
        } catch (UnsupportedOperationException e) {}
        try {
            xmlReader.getNamespaceURI("");
            fail("UnsupportedOperationExpected");
        } catch (UnsupportedOperationException e) {}
        try {
            xmlReader.getPITarget();
            fail("UnsupportedOperationExpected");
        } catch (UnsupportedOperationException e) {}
        try {
            xmlReader.getPIData();
            fail("UnsupportedOperationExpected");
        } catch (UnsupportedOperationException e) {}
    }

}
