/*
 * Copyright 2013-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.server.mvc.common;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.util.UriComponentsBuilder;

import static org.springframework.web.servlet.function.RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE;

// TODO: maybe rename to ServerRequestUtils?
public abstract class MvcUtils {

	/**
	 * Cached raw request body key.
	 */
	public static final String CACHED_REQUEST_BODY_ATTR = qualify("cachedRequestBody");

	/**
	 * CircuitBreaker execution exception attribute name.
	 */
	public static final String CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR = qualify("circuitBreakerExecutionException");

	/**
	 * Gateway route ID attribute name.
	 */
	public static final String GATEWAY_ATTRIBUTES_ATTR = qualify("gatewayAttributes");

	/**
	 * Gateway request URL attribute name.
	 */
	public static final String GATEWAY_REQUEST_URL_ATTR = qualify("gatewayRequestUrl");

	/**
	 * Gateway route ID attribute name.
	 */
	public static final String GATEWAY_ROUTE_ID_ATTR = qualify("gatewayRouteId");

	/**
	 * Preserve-Host header attribute name.
	 */
	public static final String PRESERVE_HOST_HEADER_ATTRIBUTE = qualify("preserveHostHeader");

	/**
	 * Weight attribute name.
	 */
	public static final String WEIGHT_ATTR = qualify("routeWeight");

	private MvcUtils() {
	}

	private static String qualify(String attr) {
		return "GatewayServerMvc." + attr;
	}

	public static <T> Optional<T> cacheAndReadBody(ServerRequest request, Class<T> toClass) {
		ByteArrayInputStream rawBody = cacheBody(request);
		return readBody(request, rawBody, toClass);
	}

	public static ByteArrayInputStream cacheBody(ServerRequest request) {
		try {
			byte[] bytes = StreamUtils.copyToByteArray(request.servletRequest().getInputStream());
			ByteArrayInputStream body = new ByteArrayInputStream(bytes);
			putAttribute(request, MvcUtils.CACHED_REQUEST_BODY_ATTR, body);
			return body;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static String expand(ServerRequest request, String template) {
		Assert.notNull(request, "request may not be null");
		Assert.notNull(template, "template may not be null");

		if (template.indexOf('{') == -1) { // short circuit
			return template;
		}
		Map<String, Object> variables = getUriTemplateVariables(request);
		return UriComponentsBuilder.fromPath(template).build().expand(variables).getPath();
	}

	public static List<String> expandMultiple(ServerRequest request, Collection<String> templates) {
		return templates.stream().map(value -> MvcUtils.expand(request, value)).toList();
	}

	public static String[] expandMultiple(ServerRequest request, String... templates) {
		List<String> expanded = Arrays.stream(templates).map(value -> MvcUtils.expand(request, value)).toList();
		return expanded.toArray(new String[0]);
	}

	public static ApplicationContext getApplicationContext(ServerRequest request) {
		WebApplicationContext webApplicationContext = RequestContextUtils
				.findWebApplicationContext(request.servletRequest());
		if (webApplicationContext == null) {
			throw new IllegalStateException("No Application Context in request attributes");
		}
		return webApplicationContext;
	}

	@SuppressWarnings("unchecked")
	public static <T> T getAttribute(ServerRequest request, String key) {
		if (request.attributes().containsKey(key)) {
			return (T) request.attributes().get(key);
		}
		return (T) getGatewayAttributes(request).get(key);
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> getGatewayAttributes(ServerRequest request) {
		// This map is made in GatewayDelegatingRouterFunction.route() and persists across
		// attribute resetting in RequestPredicates
		Map<String, Object> attributes = (Map<String, Object>) request.attributes().get(GATEWAY_ATTRIBUTES_ATTR);
		return attributes;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> getUriTemplateVariables(ServerRequest request) {
		return (Map<String, Object>) request.attributes().getOrDefault(URI_TEMPLATE_VARIABLES_ATTRIBUTE,
				new HashMap<>());
	}

	public static void putAttribute(ServerRequest request, String key, Object value) {
		request.attributes().put(key, value);
		getGatewayAttributes(request).put(key, value);
	}

	@SuppressWarnings("unchecked")
	public static void putUriTemplateVariables(ServerRequest request, Map<String, String> uriVariables) {
		if (request.attributes().containsKey(URI_TEMPLATE_VARIABLES_ATTRIBUTE)) {
			Map<String, Object> existingVariables = (Map<String, Object>) request.attributes()
					.get(URI_TEMPLATE_VARIABLES_ATTRIBUTE);
			HashMap<String, Object> newVariables = new HashMap<>(existingVariables);
			newVariables.putAll(uriVariables);
			request.attributes().put(URI_TEMPLATE_VARIABLES_ATTRIBUTE, newVariables);
		}
		else {
			request.attributes().put(URI_TEMPLATE_VARIABLES_ATTRIBUTE, uriVariables);
		}
	}

	@SuppressWarnings("unchecked")
	public static <T> Optional<T> readBody(ServerRequest request, ByteArrayInputStream body, Class<T> toClass) {
		try {
			HttpInputMessage inputMessage = new ByteArrayInputMessage(request, body);
			List<HttpMessageConverter<?>> httpMessageConverters = request.messageConverters();
			for (HttpMessageConverter<?> messageConverter : httpMessageConverters) {
				if (messageConverter.canRead(toClass, request.headers().contentType().orElse(null))) {
					T convertedValue = (T) messageConverter.read((Class) toClass, inputMessage);
					return Optional.of(convertedValue);
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return Optional.empty();
	}

	public static void setRouteId(ServerRequest request, String routeId) {
		request.attributes().put(GATEWAY_ROUTE_ID_ATTR, routeId);
		request.servletRequest().setAttribute(GATEWAY_ROUTE_ID_ATTR, routeId);
	}

	public static void setRequestUrl(ServerRequest request, URI url) {
		request.attributes().put(GATEWAY_REQUEST_URL_ATTR, url);
		request.servletRequest().setAttribute(GATEWAY_REQUEST_URL_ATTR, url);
	}

	private final static class ByteArrayInputMessage implements HttpInputMessage {

		private final ServerRequest request;

		private final ByteArrayInputStream body;

		private ByteArrayInputMessage(ServerRequest request, ByteArrayInputStream body) {
			this.request = request;
			this.body = body;
		}

		@Override
		public InputStream getBody() {
			return body;
		}

		@Override
		public HttpHeaders getHeaders() {
			return request.headers().asHttpHeaders();
		}

	}

}
