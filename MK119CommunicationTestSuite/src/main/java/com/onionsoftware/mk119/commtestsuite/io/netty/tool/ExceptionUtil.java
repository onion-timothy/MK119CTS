package com.onionsoftware.mk119.commtestsuite.io.netty.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExceptionUtil {
    static Logger logger = LoggerFactory.getLogger("agnet");
    public static void printStackTrace(Throwable e){
        for(StackTraceElement s : e.getStackTrace()){
            logger.info("at {}.{}({}:{})", s.getClassName(), s.getMethodName(), s.getFileName(), s.getLineNumber());
        }
    }
}
