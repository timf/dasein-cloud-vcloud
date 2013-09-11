package org.dasein.cloud.vcloud;

import junit.framework.TestCase;

public class TimestampTest extends TestCase {

    public void testSimpleTimestamp() throws Exception {
        assertEquals(1377374396243L, vCloud.parseIsoDate("2013-08-24T12:59:56.243-07:00"));
    }

    public void testTimeZones() throws Exception {
        assertEquals(1378861998412L, vCloud.parseIsoDate("2013-09-10T20:13:18.412-05:00"));
        assertEquals(1378861998412L, vCloud.parseIsoDate("2013-09-11T01:13:18.412Z"));
        assertEquals(1378861998412L, vCloud.parseIsoDate("2013-09-11T01:13:18.412-00:00"));
        assertEquals(1378861998412L, vCloud.parseIsoDate("2013-09-11T01:13:18.412+00:00"));

        assertEquals(1377374396243L, vCloud.parseIsoDate("2013-08-24T12:59:56.243-07:00"));
        assertEquals(1377370796243L, vCloud.parseIsoDate("2013-08-24T12:59:56.243-06:00"));
        assertNotSame(1377370796243L, vCloud.parseIsoDate("2013-08-24T12:59:56.243+05:00"));

        assertEquals(1378825998000L, vCloud.parseIsoDate("2013-09-10T20:13:18+05:00"));
    }

    public void testMillisecondHandling() throws Exception {
        assertEquals(1377374396243L, vCloud.parseIsoDate("2013-08-24T12:59:56.243-07:00"));
        assertEquals(1377374396000L, vCloud.parseIsoDate("2013-08-24T12:59:56-07:00"));
        assertEquals(1378861998412L, vCloud.parseIsoDate("2013-09-11T01:13:18.412Z"));
        assertEquals(1378861998000L, vCloud.parseIsoDate("2013-09-11T01:13:18Z"));
    }
}
