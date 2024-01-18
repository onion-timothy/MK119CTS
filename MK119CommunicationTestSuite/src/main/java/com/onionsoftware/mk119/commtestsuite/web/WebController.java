package com.onionsoftware.mk119.commtestsuite.web;

import javax.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;


@Controller
public class WebController {
	@RequestMapping("/")
	public String index(HttpServletRequest request) {
		return "index";
	}
}
