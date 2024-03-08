package com.onionsoftware.mk119.commtestsuite.utils;

import com.onionsoftware.mk119.commtestsuite.io.netty.IOServerInstances;
import org.springframework.context.ApplicationContext;

public class BeanUtils {
	public static Object getBean(String beanName) {
		ApplicationContext applicationContext = ApplicationContextProvider.getApplicationContext();
		return applicationContext.getBean(beanName);
	}
}
