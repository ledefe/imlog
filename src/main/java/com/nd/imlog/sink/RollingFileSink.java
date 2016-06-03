package com.nd.imlog.sink;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class RollingFileSink extends AbstractSink implements Configurable {

    private static final Logger logger = LoggerFactory.getLogger(RollingFileSink.class);

    private static class FileManger {
        String filePath;

        OutputStream outputStream;

        boolean newFile = false;
    }

    private HashMap<String, FileManger> files;

    private String directory;

    private long maxSize;

    private long rollInterval;

    private static final long defaultRollInterval = 30;

    private ScheduledExecutorService rollService;

    private String bizFieldName = "biz";

    private String DEFAULT_DIR_NAME = "default";

    @Override
    public void configure(Context context) {
        directory = context.getString("sink.directory");
        maxSize = context.getLong("sink.maxSize") != null ? context.getLong("sink.maxSize") : 0;
        rollInterval = context.getLong("sink.rollInterval") == null ? defaultRollInterval : context
                .getLong("sink.rollInterval");
        Preconditions.checkArgument(directory != null, "Directory may not be null");

        files = new HashMap<String, FileManger>();

    }

    @Override
    public void start() {

        super.start();

        if (maxSize > 0 && rollInterval > 0) {
            rollService = Executors.newScheduledThreadPool(
                    1,
                    new ThreadFactoryBuilder().setNameFormat(
                            "rollingFileSink-roller-" + Thread.currentThread().getId() + "-%d").build());

            rollService.scheduleAtFixedRate(new Runnable() {

                @Override
                public void run() {

                    for (String key : files.keySet()) {
                        FileManger manger = files.get(key);
                        File file = new File(manger.filePath);
                        if (file.length() > maxSize) {
                            manger.newFile = true;
                        }
                    }
                }

            }, rollInterval, rollInterval, TimeUnit.SECONDS);
        }
    }

    @Override
    public Status process() throws EventDeliveryException {

        Channel channel = getChannel();
        Transaction transaction = channel.getTransaction();
        Event event = null;
        Status result = Status.READY;

        try {
            transaction.begin();
            event = channel.take();

            if (event != null) {

                Map<String, String> headers = event.getHeaders();

                String dirName = headers.get(bizFieldName);

                dirName = dirName == null ? DEFAULT_DIR_NAME : dirName;

                FileManger manger = files.get(dirName);

                if (manger == null || manger.newFile) {
                    if (manger != null) {
                        manger.outputStream.flush();
                    }
                    manger = createFile(dirName);
                    files.put(dirName, manger);
                }

                final OutputStream outputStream = manger.outputStream;
                for (String key : headers.keySet()) {

                    if (!key.equals("guId") && !key.equals(bizFieldName)) {
                        outputStream.write((key + ":" + headers.get(key) + ";").getBytes());
                    }
                }
                outputStream.write("body:".getBytes());
                outputStream.write(event.getBody());
                String lineSeparator = System.getProperty("line.separator", "\n");
                outputStream.write(lineSeparator.getBytes());
                outputStream.flush();
            }
            else {
                // No events found, request back-off semantics from runner
                result = Status.BACKOFF;
            }
            transaction.commit();
        } catch (Exception ex) {
            transaction.rollback();
            throw new EventDeliveryException("Failed to process event: " + event, ex);
        } finally {
            transaction.close();
        }

        return result;
    }

    @Override
    public void stop() {

        super.stop();

        if (rollService != null) {
            rollService.shutdown();

            while (!rollService.isTerminated()) {
                try {
                    rollService.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    logger.debug("Interrupted while waiting for roll service to stop. " + "Please report this.", e);
                }
            }

            rollService = null;
        }
    }

    private FileManger createFile(String dirName) throws IOException {

        String path = directory + (directory.endsWith("/") ? "" : "/") + dirName;

        File file = new File(path);

        if (!file.exists()) {
            file.mkdirs();
        }

        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS");// 设置日期格式

        String filePath = path + "/" + df.format(new Date()) + ".log";

        file = new File(filePath);

        file.createNewFile();

        FileManger manager = new FileManger();

        manager.filePath = filePath;

        manager.newFile = false;

        manager.outputStream = new BufferedOutputStream(new FileOutputStream(file));

        return manager;
    }
}
