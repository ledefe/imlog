package com.nd.imlog.sink;

import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang.StringUtils;
import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.BasicDBObject;
import com.mongodb.CommandResult;
import com.mongodb.DB;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteConcern;

public class MongoSink extends AbstractSink implements Configurable {

    private static Logger logger = LoggerFactory.getLogger(MongoSink.class);

    public static final String HOST = "host";

    public static final String PORT = "port";

    public static final String AUTHENTICATION_ENABLED = "authenticationEnabled";

    public static final String USERNAME = "username";

    public static final String PASSWORD = "password";

    public static final String MODEL = "model";

    public static final String DB_NAME = "db";

    public static final String COLLECTION_FIELD = "collectionField";

    public static final String COLLECTION = "collection";

    public static final String CHARSET = "charset";

    public static final String NAME_PREFIX = "MongSink_";

    public static final String BATCH_SIZE = "batch";

    public static final String DEFAULT_COLLECTION_FIELD = "biz";

    public static final String DEFAULT_COLLECTION = "events";

    public static final String DEFAULT_DB = "events";

    public static final boolean DEFAULT_AUTHENTICATION_ENABLED = false;

    public static final String DEFAULT_HOST = "localhost";

    public static final String DEFAULT_CHARSET = "UTF-8";

    public static final String DEFAULT_PORT = "27017";

    public static final int DEFAULT_BATCH = 100;

    public static final String DEFAULT_WRAP_FIELD = "log";

    public static final String DEFAULT_TIMESTAMP_FIELD = null;

    public static final char NAMESPACE_SEPARATOR = '.';

    private static AtomicInteger counter = new AtomicInteger();

    // 默认90天,7776000s
    public static final int DEFAULT_EXPIRE_TIME = 7776000;

    public static final String EXPIRE_TIME = "expireTime";

    private MongoClient mongo;

    private String host;

    private String port;

    private String charset;

    private String username;

    private String dbName;

    private String password;

    private String collectionName;

    private String collectionField;

    private int batchSize;

    private long expireAfterSeconds;

    @Override
    public void configure(Context context) {
        setName(NAME_PREFIX + counter.getAndIncrement());

        host = context.getString(HOST, DEFAULT_HOST);
        port = context.getString(PORT, DEFAULT_PORT);
        username = context.getString(USERNAME);
        password = context.getString(PASSWORD);
        charset = context.getString(CHARSET, DEFAULT_CHARSET);
        dbName = context.getString(DB_NAME, DEFAULT_DB);
        collectionField = context.getString(COLLECTION_FIELD, DEFAULT_COLLECTION_FIELD);
        collectionName = context.getString(COLLECTION, DEFAULT_COLLECTION);
        batchSize = context.getInteger(BATCH_SIZE, DEFAULT_BATCH);
        expireAfterSeconds = context.getInteger(EXPIRE_TIME, DEFAULT_EXPIRE_TIME).intValue();
        logger.info(
                "MongoSink {} context { host:{}, port:{},  username:{}, password:{},  db:{}, collection:{},  collectionField:{}, batch: {},expire:{}}",
                new Object[] { getName(), host, port, username, password, dbName, collectionName, collectionField,
                        batchSize, expireAfterSeconds });
    }

    @Override
    public synchronized void start() {
        logger.info("Starting {}...", getName());

        try {

            final String pattern = "\\s*,\\s*";
            final String[] arrayHost = host.trim().split(pattern);
            final String[] arrayPort = port.trim().split(pattern);
            final String[] arrayName = username.trim().split(pattern);
            final String[] arrayPas = password.trim().split(pattern);

            List<ServerAddress> addresses = new ArrayList<ServerAddress>();
            List<MongoCredential> credentialsList = new ArrayList<MongoCredential>();

            for (int i = 0, len = arrayHost.length; i < len; i++) {
                addresses.add(new ServerAddress(arrayHost[i], Integer.valueOf(arrayPort[i < arrayPort.length ? i
                        : (arrayPort.length - 1)])));

                credentialsList.add(MongoCredential.createCredential(arrayName[i < arrayName.length ? i
                        : (arrayName.length - 1)], dbName, arrayPas[i < arrayPas.length ? i : (arrayPas.length - 1)]
                        .toCharArray()));
            }

            mongo = new MongoClient(addresses, credentialsList);

        } catch (

        UnknownHostException e)

        {
            logger.error("Can't connect to mongoDB", e);
            return;
        }
        super.start();
        logger.info("Started {}.",

        getName());
    }

    @Override
    public Status process() throws EventDeliveryException {
        logger.debug("{} start to process event", getName());

        Status status = Status.READY;
        try {
            status = parseEvents();
        } catch (Exception e) {
            logger.error("can't process events", e);
        }
        logger.debug("{} processed event", getName());
        return status;
    }

    private void saveEvents(Map<String, List<DBObject>> eventMap) {
        if (eventMap.isEmpty()) {
            logger.debug("eventMap is empty");
            return;
        }

        for (String eventCollection : eventMap.keySet()) {
            List<DBObject> docs = eventMap.get(eventCollection);
            if (logger.isDebugEnabled()) {
                logger.debug("collection: {}, length: {}", eventCollection, docs.size());
            }
            int separatorIndex = eventCollection.indexOf(NAMESPACE_SEPARATOR);
            String eventDb = eventCollection.substring(0, separatorIndex);
            String collectionName = eventCollection.substring(separatorIndex + 1);

            // Warning: please change the WriteConcern level if you need high
            // datum consistence.
            DB db = mongo.getDB(eventDb);

            // 设置文档记录过期时间
            if (!db.collectionExists(collectionName)) {

                db.getCollection(collectionName).createIndex(new BasicDBObject("time", 1),
                        new BasicDBObject("expireAfterSeconds", expireAfterSeconds));

            }

            CommandResult result = db.getCollection(collectionName).insert(docs, WriteConcern.NORMAL).getLastError();
            if (result.ok()) {
                String errorMessage = result.getErrorMessage();
                if (errorMessage != null) {
                    logger.error("can't insert documents with error: {} ", errorMessage);
                    logger.error("with exception", result.getException());
                    throw new MongoException(errorMessage);
                }
            }
            else {
                logger.error("can't get last error");
            }
        }
    }

    private Status parseEvents() throws EventDeliveryException {
        Status status = Status.READY;
        Channel channel = getChannel();
        Transaction tx = null;
        Map<String, List<DBObject>> eventMap = new HashMap<String, List<DBObject>>();
        try {
            tx = channel.getTransaction();
            tx.begin();

            for (int i = 0; i < batchSize; i++) {
                Event event = channel.take();
                if (event == null) {
                    status = Status.BACKOFF;
                    break;
                }
                else {
                    putDynamicEvent(eventMap, event);
                }
            }
            if (!eventMap.isEmpty()) {
                saveEvents(eventMap);
            }

            tx.commit();
        } catch (Exception e) {
            logger.error("can't process events, drop it!", e);
            if (tx != null) {
                tx.commit();// commit to drop bad event, otherwise it will enter
                            // dead loop.
            }

            throw new EventDeliveryException(e);
        } finally {
            if (tx != null) {
                tx.close();
            }
        }
        return status;
    }

    private void putDynamicEvent(Map<String, List<DBObject>> eventMap, Event event) {
        String eventCollection;
        Map<String, String> headers = event.getHeaders();
        String collectionFiledValue = headers.get(collectionField);

        if (!StringUtils.isEmpty(collectionFiledValue)) {
            eventCollection = dbName + NAMESPACE_SEPARATOR + collectionFiledValue;
        }
        else {
            eventCollection = dbName + NAMESPACE_SEPARATOR + collectionName;
        }

        if (!eventMap.containsKey(eventCollection)) {
            eventMap.put(eventCollection, new ArrayList<DBObject>());
        }

        List<DBObject> documents = eventMap.get(eventCollection);
        addEventToList(documents, event);
    }

    private List<DBObject> addEventToList(List<DBObject> documents, Event event) {
        if (documents == null) {
            documents = new ArrayList<DBObject>(batchSize);
        }

        byte[] body = event.getBody();
        Map<String, String> headers = event.getHeaders();

        DBObject eventJson = new BasicDBObject("body", new String(body, Charset.forName(charset)));
        if (headers != null && headers.size() > 0) {
            for (String key : headers.keySet()) {

                if (!key.equals("guId") && collectionField != null && !key.equals(collectionField)) {
                    if (key.toLowerCase().equals("timestamp")) {
                        eventJson.put("time", new Timestamp(Long.valueOf(headers.get(key))));
                    }
                    else {
                        eventJson.put(key, headers.get(key));
                    }

                }
            }
        }

        documents.add(eventJson);

        return documents;
    }

}
