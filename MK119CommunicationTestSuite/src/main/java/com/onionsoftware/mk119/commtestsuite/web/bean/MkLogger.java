package com.onionsoftware.mk119.commtestsuite.web.bean;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Getter
@Setter
public class MkLogger implements Comparable<MkLogger> {
    public static CircularFifoQueue<MkLogger> LATEST_LOGS = new CircularFifoQueue<MkLogger>(100);
    private String message;
    private long timestamp;
    private LogLevel level;
    private String subject;

    public MkLogger(Logger logger, LogLevel level, String message, Object... args) {
        this.setSubject(logger.getName());
        for (int i = 0; i < args.length; i++) {
            message = message.replace("{" + i + "}", args[i].toString());
        }
        this.setMessage(message);
        this.setLevel(level);
        this.setTimestamp(System.currentTimeMillis());
        if (level == LogLevel.ERROR) {
            logger.error(message);
        } else if (level == LogLevel.WARNING) {
            logger.warn(message);
        } else if (level == LogLevel.INFO) {
            logger.info(message);
        }
    }

    public MkLogger(Logger logger, Exception e, String message, Object... args) {
        this.setSubject(logger.getName());
        for (int i = 0; i < args.length; i++) {
            message = message.replace("{" + i + "}", args[i].toString());
        }
        this.setMessage(message + "\n" + e.toString());
        this.setLevel(level);
        this.setTimestamp(System.currentTimeMillis());
        if (level == LogLevel.ERROR) {
            logger.error(message, e);
        }
    }

    public static void log(String loggerName, LogLevel level, String message, Object... args) {
        Logger logger = LoggerFactory.getLogger(loggerName);
        LATEST_LOGS.add(new MkLogger(logger, level, message, args));
    }

    public static void log(String loggerName, Exception e, String message, Object... args) {
        Logger logger = LoggerFactory.getLogger(loggerName);
        LATEST_LOGS.add(new MkLogger(logger, e, message, args));
    }

    @Override
    public int compareTo(MkLogger arg0) {
        return Long.compare(arg0.getTimestamp(), this.timestamp);
    }
}
