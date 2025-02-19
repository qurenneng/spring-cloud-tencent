/*
 * Tencent is pleased to support the open source community by making Spring Cloud Tencent available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 */

package com.tencent.cloud.metadata.core;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.tencent.cloud.common.constant.MetadataConstant;
import com.tencent.cloud.common.metadata.MetadataContextHolder;
import com.tencent.cloud.common.util.JacksonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import static com.tencent.cloud.common.constant.ContextConstant.UTF_8;
import static com.tencent.cloud.common.constant.MetadataConstant.HeaderName.CUSTOM_DISPOSABLE_METADATA;
import static com.tencent.cloud.common.constant.MetadataConstant.HeaderName.CUSTOM_METADATA;
import static com.tencent.cloud.common.metadata.MetadataContext.FRAGMENT_RAW_TRANSHEADERS;
import static com.tencent.cloud.common.metadata.MetadataContext.FRAGMENT_RAW_TRANSHEADERS_KV;

/**
 * Filter used for storing the metadata from upstream temporarily when web application is
 * SERVLET.
 *
 * @author Haotian Zhang
 */
@Order(MetadataConstant.OrderConstant.WEB_FILTER_ORDER)
public class DecodeTransferMetadataServletFilter extends OncePerRequestFilter {

	private static final Logger LOG = LoggerFactory.getLogger(DecodeTransferMetadataServletFilter.class);

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest httpServletRequest,
			@NonNull HttpServletResponse httpServletResponse, FilterChain filterChain)
			throws ServletException, IOException {
		Map<String, String> internalTransitiveMetadata = getInternalMetadata(httpServletRequest, CUSTOM_METADATA);
		Map<String, String> customTransitiveMetadata = CustomTransitiveMetadataResolver.resolve(httpServletRequest);

		Map<String, String> mergedTransitiveMetadata = new HashMap<>();
		mergedTransitiveMetadata.putAll(internalTransitiveMetadata);
		mergedTransitiveMetadata.putAll(customTransitiveMetadata);

		Map<String, String> internalDisposableMetadata = getInternalMetadata(httpServletRequest, CUSTOM_DISPOSABLE_METADATA);
		Map<String, String> mergedDisposableMetadata = new HashMap<>(internalDisposableMetadata);

		MetadataContextHolder.init(mergedTransitiveMetadata, mergedDisposableMetadata);

		setCompleteTransHeaderIntoMC(httpServletRequest);
		filterChain.doFilter(httpServletRequest, httpServletResponse);
	}

	/**
	 * According to HttpServletRequest and trans-headers(key list in string type) in metadata, build
	 * the complete headers(key-value list in map type) into metadata.
	 */
	private void setCompleteTransHeaderIntoMC(HttpServletRequest httpServletRequest) {
		// transHeaderMetadata: for example, {"trans-headers" : {"header1,header2,header3":""}}
		Map<String, String> transHeaderMetadata = MetadataContextHolder.get()
				.getFragmentContext(FRAGMENT_RAW_TRANSHEADERS);
		if (!CollectionUtils.isEmpty(transHeaderMetadata)) {
			String transHeaders = transHeaderMetadata.keySet().stream().findFirst().orElse("");
			String[] transHeaderArray = transHeaders.split(",");
			Enumeration<String> httpHeaders = httpServletRequest.getHeaderNames();
			while (httpHeaders.hasMoreElements()) {
				String httpHeader = httpHeaders.nextElement();
				Arrays.stream(transHeaderArray).forEach(transHeader -> {
					if (transHeader.equals(httpHeader)) {
						String httpHeaderValue = httpServletRequest.getHeader(httpHeader);
						// for example, {"trans-headers-kv" : {"header1":"v1","header2":"v2"...}}
						MetadataContextHolder.get()
								.putContext(FRAGMENT_RAW_TRANSHEADERS_KV, httpHeader, httpHeaderValue);
					}
				});
			}
		}
	}

	private Map<String, String> getInternalMetadata(HttpServletRequest httpServletRequest, String headerName) {
		// Get custom metadata string from http header.
		String customMetadataStr = httpServletRequest.getHeader(headerName);
		try {
			if (StringUtils.hasText(customMetadataStr)) {
				customMetadataStr = URLDecoder.decode(customMetadataStr, UTF_8);
			}
		}
		catch (UnsupportedEncodingException e) {
			LOG.error("Runtime system does not support utf-8 coding.", e);
		}
		LOG.debug("Get upstream metadata string: {}", customMetadataStr);

		// create custom metadata.
		return JacksonUtils.deserialize2Map(customMetadataStr);
	}
}
