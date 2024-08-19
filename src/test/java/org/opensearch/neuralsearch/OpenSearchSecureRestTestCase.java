/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.neuralsearch;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.extern.log4j.Log4j2;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContextBuilder;
import org.junit.After;
import org.opensearch.client.JunoRestClient;
import org.opensearch.client.JunoRestClientBuilder;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.common.io.PathUtils;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.test.rest.OpenSearchRestTestCase;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.ssl.SSLContexts;


import javax.net.ssl.SSLContext;

/**
 * Base class for running the integration tests on a secure cluster. The plugin IT test should either extend this
 * class or create another base class by extending this class to make sure that their IT can be run on secure clusters.
 */
@Log4j2
public abstract class OpenSearchSecureRestTestCase extends OpenSearchRestTestCase {

    private static final String PROTOCOL_HTTP = "http";
    private static final String PROTOCOL_HTTPS = "https";
    private static final String SYS_PROPERTY_KEY_HTTPS = "https";
    private static final String SYS_PROPERTY_KEY_CLUSTER_ENDPOINT = "tests.rest.cluster";
    private static final String SYS_PROPERTY_KEY_USER = "user";
    private static final String SYS_PROPERTY_KEY_PASSWORD = "password";
    private static final String SYS_PROPERTY_AWS_ACCESS_KEY_ID = "aws.accessKeyId";
    private static final String SYS_PROPERTY_AWS_SECRET_ACCESS_KEY = "aws.secretAccessKey";
    private static final String SYS_PROPERTY_AOSS = "aoss";
    private static final String SERVICE_NAME = "aoss";
    private static final String ACCOUNT_ID = "058264223758";
    private static final String COLLECTION_ID = "jzms9dhorjfvix6938w5";
    private static final String DEFAULT_SOCKET_TIMEOUT = "60s";
    private static final String INTERNAL_INDICES_PREFIX = ".";
    private static String protocol;

    @Override
    protected String getProtocol() {
        if (protocol == null) {
            protocol = readProtocolFromSystemProperty();
        }
        return protocol;
    }

    private String readProtocolFromSystemProperty() {
        final boolean isHttps = Optional.ofNullable(System.getProperty(SYS_PROPERTY_KEY_HTTPS)).map("true"::equalsIgnoreCase).orElse(false);
        if (!isHttps) {
            return PROTOCOL_HTTP;
        }

        // currently only external cluster is supported for security enabled testing
        if (Optional.ofNullable(System.getProperty(SYS_PROPERTY_KEY_CLUSTER_ENDPOINT)).isEmpty()) {
            throw new RuntimeException("cluster url should be provided for security enabled testing");
        }
        return PROTOCOL_HTTPS;
    }

    protected RestClient buildClient(Settings settings, HttpHost[] hosts) throws IOException {
        final JunoRestClientBuilder builder = JunoRestClient.junoBuilder(hosts);

        if (PROTOCOL_HTTPS.equals(getProtocol())) {
            configureHttpsClient(builder, settings);
        } else {
            configureClient(builder, settings);
        }

        return builder.build();
    }

    private void configureHttpsClient(final JunoRestClientBuilder builder, final Settings settings) {
        final Map<String, String> headers = ThreadContext.buildDefaultHeaders(settings);
        final Header[] defaultHeaders = new Header[headers.size()];
        int i = 0;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            defaultHeaders[i++] = new BasicHeader(entry.getKey(), entry.getValue());
        }
        builder.setDefaultHeaders(defaultHeaders);
        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            final String userName = Optional.ofNullable(System.getProperty(SYS_PROPERTY_KEY_USER))
                .orElseThrow(() -> new RuntimeException("user name is missing"));
            final String password = Optional.ofNullable(System.getProperty(SYS_PROPERTY_KEY_PASSWORD))
                .orElseThrow(() -> new RuntimeException("password is missing"));
            final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(userName, password));
            try {
                return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
                    // disable the certificate since our testing cluster just uses the default security configuration
                    .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                    .setSSLContext(SSLContextBuilder.create().loadTrustMaterial(null, (chains, authType) -> true).build());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        final String socketTimeoutString = settings.get(CLIENT_SOCKET_TIMEOUT);
        final TimeValue socketTimeout = TimeValue.parseTimeValue(
            socketTimeoutString == null ? DEFAULT_SOCKET_TIMEOUT : socketTimeoutString,
            CLIENT_SOCKET_TIMEOUT
        );
        builder.setRequestConfigCallback(conf -> conf.setSocketTimeout(Math.toIntExact(socketTimeout.getMillis())));
        if (settings.hasValue(CLIENT_PATH_PREFIX)) {
            builder.setPathPrefix(settings.get(CLIENT_PATH_PREFIX));
        }
    }

    protected static void openSearchRestTestCaseConfigureClient(JunoRestClientBuilder builder, Settings settings) throws IOException {
        String keystorePath = settings.get(TRUSTSTORE_PATH);
        if (keystorePath != null) {
            final String keystorePass = settings.get(TRUSTSTORE_PASSWORD);
            if (keystorePass == null) {
                throw new IllegalStateException(TRUSTSTORE_PATH + " is provided but not " + TRUSTSTORE_PASSWORD);
            }
            Path path = PathUtils.get(keystorePath);
            if (!Files.exists(path)) {
                throw new IllegalStateException(TRUSTSTORE_PATH + " is set but points to a non-existing file");
            }
            try {
                final String keyStoreType = keystorePath.endsWith(".p12") ? "PKCS12" : "jks";
                KeyStore keyStore = KeyStore.getInstance(keyStoreType);
                try (InputStream is = Files.newInputStream(path)) {
                    keyStore.load(is, keystorePass.toCharArray());
                }
                SSLContext sslcontext = SSLContexts.custom().loadTrustMaterial(keyStore, null).build();
                SSLIOSessionStrategy sessionStrategy = new SSLIOSessionStrategy(sslcontext);
                builder.setHttpClientConfigCallback(httpClientBuilder -> {
                    return httpClientBuilder.setSSLStrategy(sessionStrategy);
                });
            } catch (KeyStoreException | NoSuchAlgorithmException | KeyManagementException | CertificateException e) {
                throw new RuntimeException("Error setting up ssl", e);
            }
        }
        Map<String, String> headers = ThreadContext.buildDefaultHeaders(settings);
        Header[] defaultHeaders = new Header[headers.size()];
        int i = 0;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            defaultHeaders[i++] = new BasicHeader(entry.getKey(), entry.getValue());
        }
        builder.setDefaultHeaders(defaultHeaders);
        final String socketTimeoutString = settings.get(CLIENT_SOCKET_TIMEOUT);
        final TimeValue socketTimeout = TimeValue.parseTimeValue(
                socketTimeoutString == null ? "60s" : socketTimeoutString,
                CLIENT_SOCKET_TIMEOUT
        );
        builder.setRequestConfigCallback(conf -> conf.setSocketTimeout(Math.toIntExact(socketTimeout.getMillis())));
        if (settings.hasValue(CLIENT_PATH_PREFIX)) {
            builder.setPathPrefix(settings.get(CLIENT_PATH_PREFIX));
        }
    }

    protected static void configureClient(JunoRestClientBuilder builder, Settings settings) throws IOException {
        String userName = System.getProperty("user");
        String password = System.getProperty("password");
        if (userName != null && password != null) {
            builder.setHttpClientConfigCallback(
                    httpClientBuilder -> {
                        JunoRestClient.configureHttpRequestHeaders(httpClientBuilder);
                        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                        credentialsProvider.setCredentials(
                                new AuthScope(null, -1), new UsernamePasswordCredentials(userName, password));
                        return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    });
        }
        openSearchRestTestCaseConfigureClient(builder, settings);
    }
    /**
     * wipeAllIndices won't work since it cannot delete security index. Use deleteExternalIndices instead.
     */
    @Override
    protected boolean preserveIndicesUponCompletion() {
        return true;
    }

    @After
    public void deleteExternalIndices() throws IOException {
        final Response response = client().performRequest(new Request("GET", "/_cat/indices?format=json"));
        try (
            final XContentParser parser = JsonXContent.jsonXContent.createParser(
                NamedXContentRegistry.EMPTY,
                DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                response.getEntity().getContent()
            )
        ) {
            final XContentParser.Token token = parser.nextToken();
            final List<Map<String, Object>> parserList;
            if (token == XContentParser.Token.START_ARRAY) {
                parserList = parser.listOrderedMap().stream().map(obj -> (Map<String, Object>) obj).collect(Collectors.toList());
            } else {
                parserList = Collections.singletonList(parser.mapOrdered());
            }

            final List<String> externalIndices = parserList.stream()
                .map(index -> (String) index.get("index"))
                .filter(indexName -> indexName != null)
                .filter(indexName -> !indexName.startsWith(INTERNAL_INDICES_PREFIX))
                .collect(Collectors.toList());

            for (final String indexName : externalIndices) {
                adminClient().performRequest(new Request("DELETE", "/" + indexName));
            }
        }
    }
}
