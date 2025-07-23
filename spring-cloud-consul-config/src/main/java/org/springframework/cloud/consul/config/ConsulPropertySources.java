/*
 * Copyright 2015-2020 the original author or authors.
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

package org.springframework.cloud.consul.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;

import org.springframework.cloud.consul.ConsulException;
import org.springframework.cloud.consul.IConsulClient;
import org.springframework.cloud.consul.model.http.ConsulHeaders;
import org.springframework.cloud.consul.model.http.kv.GetValue;
import org.springframework.core.style.ToStringCreator;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;

import static org.springframework.cloud.consul.config.ConsulConfigProperties.Format.FILES;

public class ConsulPropertySources {

	protected static final List<String> DIR_SUFFIXES = Collections.singletonList("/");

	protected static final List<String> FILES_SUFFIXES = Collections
		.unmodifiableList(Arrays.asList(".yml", ".yaml", ".properties"));

	private final ConsulConfigProperties properties;

	private final Log log;

	public ConsulPropertySources(ConsulConfigProperties properties, Log log) {
		this.properties = properties;
		this.log = log;
	}

	public List<String> getAutomaticContexts(List<String> profiles) {
		return getAutomaticContexts(profiles, true);
	}

	public List<String> getAutomaticContexts(List<String> profiles, boolean reverse) {
		return generateAutomaticContexts(profiles, reverse).stream().map(Context::getPath).collect(Collectors.toList());
	}

	public List<Context> generateAutomaticContexts(List<String> profiles, boolean reverse) {
		List<Context> contexts = new ArrayList<>();
		for (String prefix : this.properties.getPrefixes()) {
			String defaultContext = getContext(prefix, properties.getDefaultContext());
			List<String> suffixes = getSuffixes();
			for (String suffix : suffixes) {
				contexts.add(new Context(defaultContext + suffix));
			}
			for (String suffix : suffixes) {
				addProfiles(contexts, defaultContext, profiles, suffix);
			}

			// getName() defaults to ${spring.application.name} or application
			String baseContext = getContext(prefix, properties.getName());

			for (String suffix : suffixes) {
				contexts.add(new Context(baseContext + suffix));
			}
			for (String suffix : suffixes) {
				addProfiles(contexts, baseContext, profiles, suffix);
			}
		}
		if (reverse) {
			// we build them backwards, first wins, so reverse
			Collections.reverse(contexts);
		}
		return contexts;
	}

	protected String getContext(String prefix, String context) {
		if (!StringUtils.hasText(prefix)) {
			return context;
		}
		else {
			return prefix + "/" + context;
		}
	}

	protected List<String> getSuffixes() {
		if (properties.getFormat() == FILES) {
			return FILES_SUFFIXES;
		}
		return DIR_SUFFIXES;
	}

	private void addProfiles(List<Context> contexts, String baseContext, List<String> profiles, String suffix) {
		for (String profile : profiles) {
			String path = baseContext + properties.getProfileSeparator() + profile + suffix;
			contexts.add(new Context(path, profile));
		}
	}

	@Deprecated
	public ConsulPropertySource createPropertySource(String propertySourceContext, boolean optional,
			IConsulClient consul, BiConsumer<String, Long> indexConsumer) {
		return createPropertySource(propertySourceContext, consul, indexConsumer);
	}

	public ConsulPropertySource createPropertySource(String propertySourceContext, IConsulClient consul,
			BiConsumer<String, Long> indexConsumer) {
		try {
			ConsulPropertySource propertySource = null;

			if (properties.getFormat() == FILES) {
				ResponseEntity<List<GetValue>> response = consul.getKVValue(propertySourceContext,
						properties.getAclToken());

				GetValue value = null;
				if (response.getStatusCode().is2xxSuccessful()) {
					List<GetValue> values = response.getBody();
					if (values.size() == 0) {
						value = new GetValue();
					}
					else if (values.size() == 1) {
						value = values.get(0);
					}
					else {
						throw new ConsulException("Strange response (list size=" + values.size() + ")");
					}
				}

				String indexHeader = response.getHeaders().getFirst(ConsulHeaders.ConsulIndex.getHeaderName());
				Long consulIndex = indexHeader == null ? null : Long.parseLong(indexHeader);
				indexConsumer.accept(propertySourceContext, consulIndex);
				if (response.hasBody()) {
					ConsulFilesPropertySource filesPropertySource = new ConsulFilesPropertySource(propertySourceContext,
							consul, properties);
					filesPropertySource.init(value);
					propertySource = filesPropertySource;
				}
			}
			else {
				propertySource = create(propertySourceContext, consul, indexConsumer);
			}
			return propertySource;
		}
		catch (PropertySourceNotFoundException e) {
			throw e;
		}
		catch (Exception e) {
			if (properties.isFailFast()) {
				throw new PropertySourceNotFoundException(propertySourceContext, e);
			}
			else {
				log.warn("Unable to load consul config from " + propertySourceContext, e);
			}
		}
		return null;
	}

	private ConsulPropertySource create(String context, IConsulClient consulClient,
			BiConsumer<String, Long> indexConsumer) {
		ConsulPropertySource propertySource = new ConsulPropertySource(context, consulClient, this.properties);
		propertySource.init();
		indexConsumer.accept(context, propertySource.getInitialIndex());
		return propertySource;
	}

	public static class Context {

		private final String path;

		private final String profile;

		public Context(String path) {
			this.path = path;
			this.profile = null;
		}

		public Context(String path, String profile) {
			this.path = path;
			this.profile = profile;
		}

		public String getPath() {
			return this.path;
		}

		public String getProfile() {
			return this.profile;
		}

		@Override
		public String toString() {
			return new ToStringCreator(this).append("path", path).append("profile", profile).toString();

		}

	}

	static class PropertySourceNotFoundException extends RuntimeException {

		private final String context;

		PropertySourceNotFoundException(String context) {
			this.context = context;
		}

		PropertySourceNotFoundException(String context, Exception cause) {
			super(cause);
			this.context = context;
		}

		public String getContext() {
			return this.context;
		}

	}

}
