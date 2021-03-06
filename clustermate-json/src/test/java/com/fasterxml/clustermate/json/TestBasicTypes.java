package com.fasterxml.clustermate.json;

import com.fasterxml.clustermate.api.KeyRange;
import com.fasterxml.clustermate.api.KeySpace;
import com.fasterxml.clustermate.json.ClusterMateTypesModule;
import com.fasterxml.jackson.databind.ObjectMapper;

public class TestBasicTypes extends JsonTestBase
{
    protected final ObjectMapper MAPPER;
    
    public TestBasicTypes() {
        MAPPER = new ObjectMapper();
        MAPPER.registerModule(new ClusterMateTypesModule(false));
    }

    final static String KEYRANGE_STRING = "{\"keyspace\":1024,\"start\":256,\"length\":512}";
    
    public void testSerialization() throws Exception
    {
        KeySpace keyspace = new KeySpace(1024);
        assertEquals("1024", MAPPER.writeValueAsString(keyspace));
        assertEquals(KEYRANGE_STRING,
                MAPPER.writeValueAsString(keyspace.range(256, 512)));
    }

    public void testDeserialization() throws Exception
    {
        KeySpace keyspace = MAPPER.readValue("1024", KeySpace.class);
        assertEquals(new KeySpace(1024), keyspace);
        assertEquals(keyspace.range(256, 512),
                MAPPER.readValue(KEYRANGE_STRING, KeyRange.class));
    }
}
