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

package com.viscosiety.contrib.tester;

import ca.uhn.fhir.system.HapiSystemProperties;
import com.viscosiety.contrib.tester.mvc.AnnotationMethodHandlerAdapterConfigurer;
import com.viscosiety.contrib.tester.util.WebUtil;
import jakarta.annotation.Nonnull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.springframework.http.CacheControl;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableWebMvc
@ComponentScan(basePackages = "com.viscosiety.contrib.tester")
public class FhirTesterMvcConfig implements WebMvcConfigurer {

	@Override
	public void addResourceHandlers(@Nonnull ResourceHandlerRegistry theRegistry) {
		WebUtil.webJarAddBoostrap(theRegistry);
		WebUtil.webJarAddAceBuilds(theRegistry);
		WebUtil.webJarAddJQuery(theRegistry);
		WebUtil.webJarAddFontAwesome(theRegistry);
		WebUtil.webJarAddJSTZ(theRegistry);
		WebUtil.webJarAddEonasdanBootstrapDatetimepicker(theRegistry);
		WebUtil.webJarAddMomentJS(theRegistry);
		WebUtil.webJarAddSelect2(theRegistry);
		WebUtil.webJarAddAwesomeCheckbox(theRegistry);
		WebUtil.webJarAddPopperJs(theRegistry);

		theRegistry.addResourceHandler("/css/**").addResourceLocations("/css/").setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS));
		theRegistry.addResourceHandler("/fa/**").addResourceLocations("/fa/").setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS));
		theRegistry.addResourceHandler("/fonts/**").addResourceLocations("/fonts/").setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS));
		theRegistry.addResourceHandler("/img/**").addResourceLocations("/img/").setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS));
		theRegistry.addResourceHandler("/js/**").addResourceLocations("/js/").setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS));
		theRegistry.addResourceHandler("favicon.ico").addResourceLocations("/img/favicon.ico").setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS));
	}

	@Bean
	public SpringResourceTemplateResolver templateResolver(TesterConfig theTesterConfig) {
		SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
		resolver.setPrefix("/WEB-INF/templates/");
		resolver.setSuffix(".html");
		resolver.setTemplateMode(TemplateMode.HTML);
		resolver.setCharacterEncoding("UTF-8");

		if (theTesterConfig.getDebugTemplatesMode() || HapiSystemProperties.isUnitTestModeEnabled()) {
			resolver.setCacheable(false);
		}

		return resolver;
	}

	@Bean
	public AnnotationMethodHandlerAdapterConfigurer annotationMethodHandlerAdapterConfigurer(
			@Qualifier("requestMappingHandlerAdapter") RequestMappingHandlerAdapter theAdapter) {
		return new AnnotationMethodHandlerAdapterConfigurer(theAdapter);
	}

	@Bean
	public ThymeleafViewResolver viewResolver(SpringTemplateEngine theTemplateEngine) {
		ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
		viewResolver.setTemplateEngine(theTemplateEngine);
		viewResolver.setCharacterEncoding("UTF-8");
		return viewResolver;
	}

	@Bean
	public SpringTemplateEngine templateEngine(SpringResourceTemplateResolver theTemplateResolver) {
		SpringTemplateEngine templateEngine = new SpringTemplateEngine();
		templateEngine.setTemplateResolver(theTemplateResolver);

		return templateEngine;
	}
}
