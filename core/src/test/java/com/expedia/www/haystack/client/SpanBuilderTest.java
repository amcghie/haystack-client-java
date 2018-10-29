/*
 * Copyright 2018 Expedia, Inc.
 *
 *       Licensed under the Apache License, Version 2.0 (the "License");
 *       you may not use this file except in compliance with the License.
 *       You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *       Unless required by applicable law or agreed to in writing, software
 *       distributed under the License is distributed on an "AS IS" BASIS,
 *       WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *       See the License for the specific language governing permissions and
 *       limitations under the License.
 *
 */
package com.expedia.www.haystack.client;

import com.expedia.www.haystack.client.dispatchers.Dispatcher;
import com.expedia.www.haystack.client.dispatchers.NoopDispatcher;
import com.expedia.www.haystack.client.metrics.NoopMetricsRegistry;
import io.opentracing.References;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SpanBuilderTest {

    private Dispatcher dispatcher;
    private Tracer tracer;

    @Before
    public void setUp() throws Exception {
        dispatcher = new NoopDispatcher();
        tracer = new Tracer.Builder(new NoopMetricsRegistry(), "TestService", dispatcher).build();
    }

    @Test
    public void testBasic() {
        Span span = tracer.buildSpan("test-operation").start();

        Assert.assertEquals("test-operation", span.getOperatioName());
    }


    @Test
    public void testReferences() {
        Span parent = tracer.buildSpan("parent").start();
        Span following = tracer.buildSpan("following").start();

        Span child = tracer.buildSpan("child")
                .asChildOf(parent)
                .addReference(References.FOLLOWS_FROM, following.context())
                .start();

        Assert.assertEquals(2, child.getReferences().size());
        Assert.assertEquals(child.getReferences().get(0), new Reference(References.CHILD_OF, parent.context()));
        Assert.assertEquals(child.getReferences().get(1), new Reference(References.FOLLOWS_FROM, following.context()));
    }

    @Test
    public void testChildOfWithDualSpanType() {
        //create a client span
        final Tracer clientTracer = new Tracer.Builder(new NoopMetricsRegistry(),
                                                       "ClientService",
                                                       dispatcher).withDualSpanMode().build();
        final Span clientSpan = clientTracer.buildSpan("Api_call")
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
                .start();
        final MapBackedTextMap wireData = new MapBackedTextMap();
        clientTracer.inject(clientSpan.context(), Format.Builtin.TEXT_MAP, wireData);

        //create a server
        final Tracer serverTracer = new Tracer.Builder(new NoopMetricsRegistry(),
                                                       "ServerService",
                                                       dispatcher).withDualSpanMode().build();
        final SpanContext wireContext = serverTracer.extract(Format.Builtin.TEXT_MAP, wireData);
        final Span serverSpan = serverTracer.buildSpan("Api")
                .asChildOf(wireContext)
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
                .start();

        Assert.assertEquals("trace-ids are not matching",
                            clientSpan.context().getTraceId().toString(),
                            serverSpan.context().getTraceId().toString());
        Assert.assertEquals("server's parent id - client's span id do not match",
                            clientSpan.context().getSpanId().toString(),
                            serverSpan.context().getParentId().toString());
    }

    @Test
    public void testChildOfWithSingleSpanType() {
        //create a client span
        final Tracer clientTracer = new Tracer.Builder(new NoopMetricsRegistry(),
                                                       "ClientService",
                                                       dispatcher).build();
        final Span clientSpan = clientTracer.buildSpan("Api_call")
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
                .start();
        final MapBackedTextMap wireData = new MapBackedTextMap();
        clientTracer.inject(clientSpan.context(), Format.Builtin.TEXT_MAP, wireData);

        //create a server
        final Tracer serverTracer = new Tracer.Builder(new NoopMetricsRegistry(),
                                                       "ServerService",
                                                       dispatcher).build();
        final SpanContext wireContext = serverTracer.extract(Format.Builtin.TEXT_MAP, wireData);
        final Span serverSpan = serverTracer.buildSpan("Api")
                .asChildOf(wireContext)
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
                .start();

        Assert.assertEquals("trace-ids are not matching",
                            clientSpan.context().getTraceId().toString(),
                            serverSpan.context().getTraceId().toString());
        Assert.assertEquals("server - client spans do not match",
                            clientSpan.context().getSpanId().toString(),
                            serverSpan.context().getSpanId().toString());
    }


    @Test
    public void testChildOfWithSingleSpanTypeAndExtractedContext() {
        //create a client span
        final Tracer clientTracer = new Tracer.Builder(new NoopMetricsRegistry(),
                                                       "ClientService",
                                                       dispatcher).build();
        final Span clientSpan = clientTracer.buildSpan("Api_call")
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
                .start();
        final MapBackedTextMap wireData = new MapBackedTextMap();
        clientTracer.inject(clientSpan.context(), Format.Builtin.TEXT_MAP, wireData);

        //create a server
        final Tracer serverTracer = new Tracer.Builder(new NoopMetricsRegistry(),
                                                       "ServerService",
                                                       dispatcher).build();
        final SpanContext wireContext = serverTracer.extract(Format.Builtin.TEXT_MAP, wireData);
        final Span serverSpan = serverTracer.buildSpan("Api")
                .asChildOf(wireContext)
                .start();

        Assert.assertEquals("trace-ids are not matching",
                            clientSpan.context().getTraceId().toString(),
                            serverSpan.context().getTraceId().toString());
        Assert.assertEquals("server - client spans do not match",
                            clientSpan.context().getSpanId().toString(),
                            serverSpan.context().getSpanId().toString());
    }

    @Test
    public void testWithTags() {
        Span child = tracer.buildSpan("child")
                .withTag("string-key", "string-value")
                .withTag("boolean-key", false)
                .withTag("number-key", 1l)
                .start();

        Map<String, ?> tags = child.getTags();

        Assert.assertEquals(3, tags.size());
        Assert.assertTrue(tags.containsKey("string-key"));
        Assert.assertEquals("string-value", tags.get("string-key"));
        Assert.assertTrue(tags.containsKey("boolean-key"));
        Assert.assertEquals(false, tags.get("boolean-key"));
        Assert.assertTrue(tags.containsKey("number-key"));
        Assert.assertEquals(1l, tags.get("number-key"));
    }

    private class MapBackedTextMap implements TextMap {
        private final Map<String, String> map = new HashMap<>();

        @Override
        public Iterator<Map.Entry<String, String>> iterator() {
            return map.entrySet().iterator();
        }

        @Override
        public void put(String key, String value) {
            map.put(key, value);
        }

        public Map<String, String> getMap() {
            return map;
        }
    }
}
