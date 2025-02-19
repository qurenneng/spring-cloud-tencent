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

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tencent.cloud.common.constant.MetadataConstant;
import com.tencent.cloud.common.metadata.MetadataContext;
import com.tencent.cloud.common.metadata.MetadataContextHolder;
import com.tencent.cloud.common.util.JacksonUtils;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.CollectionUtils;
import org.springframework.web.server.ServerWebExchange;

import static com.tencent.cloud.common.constant.ContextConstant.UTF_8;
import static com.tencent.cloud.common.constant.MetadataConstant.HeaderName.CUSTOM_DISPOSABLE_METADATA;
import static com.tencent.cloud.common.constant.MetadataConstant.HeaderName.CUSTOM_METADATA;
import static com.tencent.cloud.common.metadata.MetadataContext.FRAGMENT_RAW_TRANSHEADERS;
import static com.tencent.cloud.common.metadata.MetadataContext.FRAGMENT_RAW_TRANSHEADERS_KV;
import static org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter.LOAD_BALANCER_CLIENT_FILTER_ORDER;

/**
 * Scg filter used for writing metadata in HTTP request header.
 *
 * @author Haotian Zhang
 */
public class EncodeTransferMedataScgFilter implements GlobalFilter, Ordered {

	private static final int METADATA_SCG_FILTER_ORDER = LOAD_BALANCER_CLIENT_FILTER_ORDER + 1;

	@Override
	public int getOrder() {
		return METADATA_SCG_FILTER_ORDER;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		// get request builder
		ServerHttpRequest.Builder builder = exchange.getRequest().mutate();

		// get metadata of current thread
		MetadataContext metadataContext = exchange.getAttribute(MetadataConstant.HeaderName.METADATA_CONTEXT);
		if (metadataContext == null) {
			metadataContext = MetadataContextHolder.get();
		}

		Map<String, String> customMetadata = metadataContext.getFragmentContext(MetadataContext.FRAGMENT_TRANSITIVE);
		Map<String, String> disposableMetadata = metadataContext.getFragmentContext(MetadataContext.FRAGMENT_DISPOSABLE);

		// Clean upstream disposable metadata.
		Map<String, String> newestCustomMetadata = new HashMap<>();
		customMetadata.forEach((key, value) -> {
			if (!disposableMetadata.containsKey(key)) {
				newestCustomMetadata.put(key, value);
			}
		});

		this.buildMetadataHeader(builder, newestCustomMetadata, CUSTOM_METADATA);
		this.buildMetadataHeader(builder, disposableMetadata, CUSTOM_DISPOSABLE_METADATA);

		setCompleteTransHeaderIntoMC(exchange.getRequest());
		return chain.filter(exchange.mutate().request(builder.build()).build());
	}

	/**
	 * According to ServerHttpRequest and trans-headers(key list in string type) in metadata, build
	 * the complete headers(key-value list in map type) into metadata.
	 */
	private void setCompleteTransHeaderIntoMC(ServerHttpRequest serverHttpRequest) {
		// transHeaderMetadata: for example, {"trans-headers" : {"header1,header2,header3":""}}
		Map<String, String> transHeaderMetadata = MetadataContextHolder.get()
				.getFragmentContext(FRAGMENT_RAW_TRANSHEADERS);
		if (!CollectionUtils.isEmpty(transHeaderMetadata)) {
			String transHeaders = transHeaderMetadata.keySet().stream().findFirst().orElse("");
			String[] transHeaderArray = transHeaders.split(",");
			HttpHeaders headers = serverHttpRequest.getHeaders();
			Set<String> headerKeys = headers.keySet();
			for (String httpHeader : headerKeys) {
				Arrays.stream(transHeaderArray).forEach(transHeader -> {
					if (transHeader.equals(httpHeader)) {
						List<String> list = headers.get(httpHeader);
						String httpHeaderValue = JacksonUtils.serialize2Json(list);
						// for example, {"trans-headers-kv" : {"header1":"v1","header2":"v2"...}}
						MetadataContextHolder.get()
								.putContext(FRAGMENT_RAW_TRANSHEADERS_KV, httpHeader, httpHeaderValue);
					}
				});
			}
		}
	}

	/**
	 * Set metadata into the request header for {@link ServerHttpRequest.Builder} .
	 * @param builder instance of {@link ServerHttpRequest.Builder}
	 * @param metadata metadata map .
	 * @param headerName target metadata http header name .
	 */
	private void buildMetadataHeader(ServerHttpRequest.Builder builder, Map<String, String> metadata, String headerName) {
		if (!CollectionUtils.isEmpty(metadata)) {
			String encodedMetadata = JacksonUtils.serialize2Json(metadata);
			try {
				builder.header(headerName, URLEncoder.encode(encodedMetadata, UTF_8));
			}
			catch (UnsupportedEncodingException e) {
				builder.header(headerName, encodedMetadata);
			}
		}
	}
}
