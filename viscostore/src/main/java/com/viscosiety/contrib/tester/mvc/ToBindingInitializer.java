package com.viscosiety.contrib.tester.mvc;

import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.support.WebBindingInitializer;

public class ToBindingInitializer implements WebBindingInitializer {

	@Override
	public void initBinder(WebDataBinder theBinder) {
		theBinder.setFieldMarkerPrefix("__");
	}
}
