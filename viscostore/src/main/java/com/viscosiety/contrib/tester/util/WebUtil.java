/*
 * Copyright 2026 Viscosiety B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.viscosiety.contrib.tester.util;

import ca.uhn.fhir.context.ConfigurationException;
import ca.uhn.fhir.i18n.Msg;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.http.CacheControl;
import java.util.concurrent.TimeUnit;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class WebUtil {
	public static final String BOOTSTRAP_ID = "bootstrap";
	public static final String BOOTSTRAP_PKG = "org.webjars";
	public static final String JQUERY_ID = "jquery";
	public static final String JQUERY_PKG = "org.webjars.bower";

	public static void addStaticResourceWebJar(ResourceHandlerRegistry theRegistry, String pkg, String name) {
		Properties props = new Properties();
		String resourceName = "/META-INF/maven/" + pkg + "/" + name + "/pom.properties";
		try {
			InputStream resourceAsStream = WebUtil.class.getResourceAsStream(resourceName);
			if (resourceAsStream == null) {
				throw new ConfigurationException(Msg.code(196) + "Failed to load resource: " + resourceName);
			}
			props.load(resourceAsStream);
		} catch (IOException e) {
			throw new ConfigurationException(Msg.code(197) + "Failed to load resource: " + resourceName);
		}
		String version = props.getProperty("version");
		addWebjarWithVersion(theRegistry, name, version);
	}

	public static ResourceHandlerRegistration addWebjarWithVersion(
			ResourceHandlerRegistry theRegistry, String name, String version) {
		return theRegistry
				.addResourceHandler("/resources/" + name + "/**")
				.addResourceLocations("classpath:/META-INF/resources/webjars/" + name + "/" + version + "/").setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS));
	}

	public static void webJarAddAceBuilds(ResourceHandlerRegistry theRegistry) {
		WebUtil.addStaticResourceWebJar(theRegistry, "org.webjars.npm", "ace-builds");
	}

	public static void webJarAddAwesomeCheckbox(ResourceHandlerRegistry theRegistry) {
		WebUtil.addStaticResourceWebJar(theRegistry, "org.webjars.bower", "awesome-bootstrap-checkbox");
	}

	public static void webJarAddBoostrap(ResourceHandlerRegistry theRegistry) {
		WebUtil.addStaticResourceWebJar(theRegistry, BOOTSTRAP_PKG, BOOTSTRAP_ID);
	}

	public static void webJarAddEonasdanBootstrapDatetimepicker(ResourceHandlerRegistry theRegistry) {
		WebUtil.addStaticResourceWebJar(theRegistry, "org.webjars", "Eonasdan-bootstrap-datetimepicker");
	}

	public static void webJarAddFontAwesome(ResourceHandlerRegistry theRegistry) {
		WebUtil.addStaticResourceWebJar(theRegistry, "org.webjars", "font-awesome");
	}

	public static void webJarAddJQuery(ResourceHandlerRegistry theRegistry) {
		WebUtil.addStaticResourceWebJar(theRegistry, JQUERY_PKG, JQUERY_ID);
	}

	public static void webJarAddJSTZ(ResourceHandlerRegistry theRegistry) {
		WebUtil.addStaticResourceWebJar(theRegistry, "org.webjars", "jstimezonedetect");
	}

	public static void webJarAddMomentJS(ResourceHandlerRegistry theRegistry) {
		WebUtil.addStaticResourceWebJar(theRegistry, "org.webjars.bower", "moment");
	}

	public static void webJarAddSelect2(ResourceHandlerRegistry theRegistry) {
		WebUtil.addStaticResourceWebJar(theRegistry, "org.webjars", "select2");
	}

	public static void webJarAddPopperJs(ResourceHandlerRegistry theRegistry) {
		WebUtil.addStaticResourceWebJar(theRegistry, "org.webjars", "popper.js");
	}
}
