package com.nd.imlog.sink;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

import java.io.IOException;
import java.util.Map;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.conf.ComponentConfiguration;
import org.apache.flume.sink.elasticsearch2.ContentBuilderUtil;
import org.apache.flume.sink.elasticsearch2.ElasticSearchEventSerializer;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import com.fasterxml.jackson.core.JsonParseException;

public class ImElasticSearchDynamicSerializer implements ElasticSearchEventSerializer {

    @Override
    public void configure(Context context) {
        // NO-OP...
    }

    @Override
    public void configure(ComponentConfiguration conf) {
        // NO-OP...
    }

    @Override
    public XContentBuilder getContentBuilder(Event event) throws IOException {
        XContentBuilder builder = jsonBuilder().startObject();
        try {
            appendBody(builder, event);
        } catch (JsonParseException e) {
            // fixed to JsonParseException
            ContentBuilderUtil.addSimpleField(builder, "body", event.getBody());
        }
        appendHeaders(builder, event);
        return builder;
    }

    private void appendBody(XContentBuilder builder, Event event) throws IOException {
        ContentBuilderUtil.appendField(builder, "body", event.getBody());
    }

    public final static DateTimeFormatter defaultDatePrinter = ISODateTimeFormat.dateTime().withZone(DateTimeZone.UTC);

    private void appendHeaders(XContentBuilder builder, Event event) throws IOException {
        Map<String, String> headers = event.getHeaders();
        for (String key : headers.keySet()) {
            if (key != null && key.toLowerCase().equals("timestamp")) {
                ContentBuilderUtil.appendField(builder, "timestamp",
                        (ISODateTimeFormat.dateTime().print(Long.valueOf(headers.get(key)))).getBytes(charset));
            }
            else {
                ContentBuilderUtil.appendField(builder, key, headers.get(key).getBytes(charset));
            }

        }
    }
}
