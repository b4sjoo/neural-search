/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.neuralsearch.query;

import com.google.common.collect.ImmutableList;
import lombok.SneakyThrows;

import lombok.extern.log4j.Log4j2;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.neuralsearch.OpenSearchSecureRestTestCase;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.opensearch.neuralsearch.common.BaseNeuralSearchIT;
import org.opensearch.test.rest.OpenSearchRestTestCase;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.apache.http.entity.ContentType.APPLICATION_JSON;

@Log4j2
public class NeuralQueryCloudNativeIT extends OpenSearchSecureRestTestCase {
    private static final String SERVICE_NAME = "aoss";
    private static final String ACCOUNT_ID = "058264223758";
    private static final String COLLECTION_ID = "iqgbxpohpaemd8jwyui2";

    @Test
    public void testGetModel() throws IOException {
        log.info("Hello world");
        try {
            // Change the hard code model id to env variable
            Response response = client().performRequest(new Request("GET", "/_plugins/_ml/models/eec0cfe2-64b6-4a52-9d62-94007e1d3b16"));
            throw new ResponseException(response);
        } catch (ResponseException e) {
            System.out.println(Arrays.toString(e.getResponse().getHeaders()) + ' ' + EntityUtils.toString(e.getResponse().getEntity()));
        }
    }

    @Test
    public void testGetIndex() throws IOException {
        log.info("Hello world");
        try {
            // Change the hard code model id to env variable
            StringBuilder stringBuilderForContentBody = new StringBuilder();
            stringBuilderForContentBody.append("{\"description\": \"Post processor pipeline\",")
                    .append("\"phase_results_processors\": [{ ")
                    .append("\"normalization-processor\": {")
                    .append("\"normalization\": {")
                    .append("\"technique\": \"%s\"")
                    .append("},")
                    .append("\"combination\": {")
                    .append("\"technique\": \"%s\"");
            stringBuilderForContentBody.append("}").append("}}]}");
            Request request = new Request("PUT","/_search/pipeline/phase-results-pipeline");
            request.setEntity(new StringEntity(String.format(Locale.ROOT, stringBuilderForContentBody.toString(), "min_max", "arithmetic_mean"), APPLICATION_JSON));
            Response putResponse = client().performRequest(request);
            Response getResponse = client().performRequest(new Request("GET", "/_search/pipeline/phase-results-pipeline"));
            throw new ResponseException(getResponse);
        } catch (ResponseException e) {
            System.out.println(Arrays.toString(e.getResponse().getHeaders()) + ' ' + EntityUtils.toString(e.getResponse().getEntity()));
        }
    }

    @Test
    public void testHybridSearch() throws IOException {
        log.info("Hello world");
        try {
            // Change the hard code model id to env variable
            Response response = client().performRequest(new Request("GET", "/_plugins/_ml/models/eec0cfe2-64b6-4a52-9d62-94007e1d3b16"));
        } catch (ResponseException e) {
            System.out.println(Arrays.toString(e.getResponse().getHeaders()) + ' ' + EntityUtils.toString(e.getResponse().getEntity()));
        }
    }

}
