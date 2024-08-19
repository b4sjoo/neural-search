/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.neuralsearch.query;

import lombok.SneakyThrows;

import lombok.extern.log4j.Log4j2;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.neuralsearch.OpenSearchSecureRestTestCase;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Log4j2
public class NeuralQueryCloudNativeIT extends OpenSearchSecureRestTestCase {
    private static final String SERVICE_NAME = "aoss";
    private static final String ACCOUNT_ID = "058264223758";
    private static final String COLLECTION_ID = "jzms9dhorjfvix6938w5";

    @Test
    public void testNeuralQuery() throws IOException {
        log.info("Hello world");
        Response response = client().performRequest(new Request("GET", "_cat/indices"));
        throw new RuntimeException("Hello world" + response.toString());
    }
}
