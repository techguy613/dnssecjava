/*
 * dnssecjava - a DNSSEC validating stub resolver for Java
 * Copyright (c) 2013-2015 Ingo Bauersachs
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.jitsi.dnssec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Iterator;

import org.junit.Test;
import org.xbill.DNS.DClass;
import org.xbill.DNS.DNAMERecord;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

public class TestDNames extends TestBase {
    @Test
    public void testDNameToExistingIsValid() throws IOException {
        Message response = resolver.send(createMessage("www.alias.ingotronic.ch./A"));
        assertTrue("AD flag must be set", response.getHeader().getFlag(Flags.AD));
        assertEquals(Rcode.NOERROR, response.getRcode());
        assertNull(getReason(response));
    }

    @Test
    public void testDNameToNoDataIsValid() throws IOException {
        Message response = resolver.send(createMessage("www.alias.ingotronic.ch./MX"));
        assertTrue("AD flag must be set", response.getHeader().getFlag(Flags.AD));
        assertEquals(Rcode.NOERROR, response.getRcode());
        assertNull(getReason(response));
    }

    @Test
    public void testDNameToNxDomainIsValid() throws IOException {
        Message response = resolver.send(createMessage("x.alias.ingotronic.ch./A"));
        assertTrue("AD flag must be set", response.getHeader().getFlag(Flags.AD));
        assertEquals(Rcode.NXDOMAIN, response.getRcode());
        assertNull(getReason(response));
    }

    @Test
    public void testDNameDirectQueryIsValid() throws IOException {
        Message response = resolver.send(createMessage("alias.ingotronic.ch./DNAME"));
        assertTrue("AD flag must not set", response.getHeader().getFlag(Flags.AD));
        assertEquals(Rcode.NOERROR, response.getRcode());
        assertNull(getReason(response));
        for (RRset set : response.getSectionRRsets(Section.ANSWER)) {
            if (set.getType() == Type.DNAME) {
                DNAMERecord r = (DNAMERecord)set.first();
                assertEquals(Name.fromString("ingotronic.ch."), r.getTarget());
            }
        }
    }

    @Test
    public void testDNameWithFakedCnameIsInvalid() throws IOException {
        Message m = resolver.send(createMessage("www.alias.ingotronic.ch./A"));
        Message message = messageFromString(m.toString().replaceAll("(.*CNAME\\s+)(.*)", "$1 www.isc.org."));
        add("www.alias.ingotronic.ch./A", message);

        Message response = resolver.send(createMessage("www.alias.ingotronic.ch./A"));
        assertFalse("AD flag must not be set", response.getHeader().getFlag(Flags.AD));
        assertEquals(Rcode.SERVFAIL, response.getRcode());
        assertEquals("failed.synthesize.nomatch:www.isc.org.:www.ingotronic.ch.", getReason(response));
    }

    @Test
    public void testDNameWithNoCnameIsValid() throws IOException {
        Message m = resolver.send(createMessage("www.isc.ingotronic.ch./A"));
        Message message = messageFromString(m.toString().replaceAll("(.*CNAME.*)", "").replaceAll("\n\n", "\n"));
        add("www.isc.ingotronic.ch./A", message);

        Message response = resolver.send(createMessage("www.isc.ingotronic.ch./A"));
        assertTrue("AD flag must be set", response.getHeader().getFlag(Flags.AD));
        assertEquals(Rcode.NOERROR, response.getRcode());
        assertNull(getReason(response));
        Lookup l = new Lookup("www.isc.ingotronic.ch");
        l.setResolver(resolver);
        Record[] results = l.run();
        assertTrue(results != null);
        assertTrue(results.length >= 1);
    }

    @Test
    public void testDNameWithMultipleCnamesIsInvalid() throws IOException {
        Message m = resolver.send(createMessage("www.alias.ingotronic.ch./A"));
        Message message = messageFromString(m.toString().replaceAll("(.*CNAME.*)", "$1\n$1example.com."));
        add("www.alias.ingotronic.ch./A", message);

        Message response = resolver.send(createMessage("www.alias.ingotronic.ch./A"));
        assertFalse("AD flag must not be set", response.getHeader().getFlag(Flags.AD));
        assertEquals(Rcode.SERVFAIL, response.getRcode());
        assertEquals("failed.synthesize.multiple", getReason(response));
    }

    @Test
    public void testDNameWithTooLongCnameIsInvalid() throws IOException {
        Message m = resolver.send(createMessage("www.n3.ingotronic.ch./A"));
        Message message = messageFromString(m
                .toString()
                .replaceAll(
                        "(.*\\.)(.*CNAME)",
                        "IamAVeryLongNameThatExeceedsTheMaximumOfTheAllowedDomainNameSys.temSpecificationLengthByAny.NumberThatAHumanOfTheSeventiesCouldHaveImagined.InThisSmallMindedWorldThatIs.NowAfterTheMillennium.InhabitedByOverSeven.BillionPeopleInFiveConts.n3.ingotronic.ch. $2"));
        add("www.n3.ingotronic.ch./A", message);

        Message response = resolver.send(createMessage("www.n3.ingotronic.ch./A"));
        assertFalse("AD flag must not be set", response.getHeader().getFlag(Flags.AD));
        assertEquals(Rcode.SERVFAIL, response.getRcode());
        assertEquals("failed.synthesize.toolong", getReason(response));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testDNameInNsecIsUnderstood_Rfc6672_5_3_4_1() throws IOException {
        Message nsecs = resolver.send(createMessage("alias.ingotronic.ch./NS"));
        RRset nsecSet = null;
        for (RRset set : nsecs.getSectionRRsets(Section.AUTHORITY)) {
            if (set.getName().equals(Name.fromString("alias.ingotronic.ch."))) {
                nsecSet = set;
                break;
            }
        }

        Message message = new Message();
        message.getHeader().setRcode(Rcode.NXDOMAIN);
        message.addRecord(Record.newRecord(Name.fromString("www.alias.ingotronic.ch."), Type.A, DClass.IN), Section.QUESTION);
        Iterator<Record> rrs = nsecSet.rrs();
        while (rrs.hasNext()) {
            message.addRecord(rrs.next(), Section.AUTHORITY);
        }
        Iterator<Record> sigs = nsecSet.sigs();
        while (sigs.hasNext()) {
            message.addRecord(sigs.next(), Section.AUTHORITY);
        }

        add("www.alias.ingotronic.ch./A", message);

        Message response = resolver.send(createMessage("www.alias.ingotronic.ch./A"));
        assertFalse("AD flag must not be set", response.getHeader().getFlag(Flags.AD));
        assertEquals(Rcode.SERVFAIL, response.getRcode());
        assertEquals("failed.nxdomain.exists:www.alias.ingotronic.ch.", getReason(response));
    }

    @Test
    public void testDNameToExternal() throws IOException {
        Message response = resolver.send(createMessage("www.isc.ingotronic.ch./A"));
        assertTrue("AD flag must be set", response.getHeader().getFlag(Flags.AD));
        assertEquals(Rcode.NOERROR, response.getRcode());
        assertNull(getReason(response));
    }

    @Test
    public void testDNameChain() throws IOException {
        Message response = resolver.send(createMessage("www.alias.nsec3.ingotronic.ch./A"));
        assertTrue("AD flag must be set", response.getHeader().getFlag(Flags.AD));
        assertEquals(Rcode.NOERROR, response.getRcode());
        assertNull(getReason(response));
    }
}
