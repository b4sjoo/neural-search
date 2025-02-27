/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.client;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import lombok.SneakyThrows;
import lombok.Value;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.RequestLine;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicRequestLine;
import org.apache.http.message.BasicStatusLine;
import org.opensearch.interceptors.AWSRequestSigningApacheInterceptor;

import com.amazonaws.auth.AWS4UnsignedPayloadSigner;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;

/**
 * Client that connects to an OpenSearch cluster through HTTP.
 * <p>
 * Must be created using {@link RestClientBuilder}, which allows to set all the different options or just rely on defaults.
 * The hosts that are part of the cluster need to be provided at creation time, but can also be replaced later
 * by calling {@link #setNodes(Collection)}.
 * <p>
 * The method {@link #performRequest(Request)} allows to send a request to the cluster. When
 * sending a request, a host gets selected out of the provided ones in a round-robin fashion. Failing hosts are marked dead and
 * retried after a certain amount of time (minimum 1 minute, maximum 30 minutes), depending on how many times they previously
 * failed (the more failures, the later they will be retried). In case of failures all of the alive nodes (or dead nodes that
 * deserve a retry) are retried until one responds or none of them does, in which case an {@link IOException} will be thrown.
 * <p>
 * Requests can be either synchronous or asynchronous. The asynchronous variants all end with {@code Async}.
 * <p>
 * Requests can be traced by enabling trace logging for "tracer". The trace logger outputs requests and responses in curl format.
 */
public class JunoRestClient extends RestClient implements Closeable {

    private static final String SYS_PROPERTY_KEY_AWS_SERVICE = "aws.service";
    private static final String SYS_PROPERTY_KEY_AWS_REGION = "aws.region";
    private static final String SYS_PROPERTY_KEY_COLLECTION_HOST = "collection.host";
    private static final String COLLECTION_HOST = System.getProperty(SYS_PROPERTY_KEY_COLLECTION_HOST);
    private static final String COLLECTION_ENDPOINT = "https://" + COLLECTION_HOST;
    private static final Duration TIMEOUT = Duration.ofMinutes(1);

    private static final String INDEX_NAME_PATTERN = "(\\w|\\.|-|\\+|_|\\d)+";
    private static final String DOC_ID_PATTERN = INDEX_NAME_PATTERN;

    private static final Map<ApiId, ApiHandler> API_HANDLERS = Map.ofEntries(
        // not actually supported, opensearch test framework is calling this so for now we will bypass
        Map.entry(new ApiId("GET", Pattern.compile("^.*_nodes/plugins.*$")), JunoRestClient::callLocal),
        Map.entry(new ApiId("GET", Pattern.compile("^.*_search.*$")), JunoRestClient::callRemote),
        Map.entry(new ApiId("GET", Pattern.compile("^/_snapshot/_all$")), JunoRestClient::callLocal),
        Map.entry(new ApiId("DELETE", Pattern.compile("^_data_stream/.*$")), JunoRestClient::callLocal),
        Map.entry(new ApiId("DELETE", Pattern.compile("^_template/.*$")), JunoRestClient::callLocal),
        Map.entry(new ApiId("DELETE", Pattern.compile("^_index_template/.*$")), JunoRestClient::callLocal),
        Map.entry(new ApiId("DELETE", Pattern.compile("^_component_template/.*$")), JunoRestClient::callLocal),
        Map.entry(new ApiId("GET", Pattern.compile("^/_cluster/.*$")), JunoRestClient::callLocal),
        Map.entry(new ApiId("GET", Pattern.compile("^/_tasks$")), JunoRestClient::callLocal),
        Map.entry(new ApiId("GET", Pattern.compile("^/_plugins/_ml/.*$")), JunoRestClient::callRemote),
        Map.entry(new ApiId("POST", Pattern.compile("^/_plugins/_ml/.*$")), JunoRestClient::callRemote),
        // Map.entry(new ApiId("PUT", Pattern.compile("^/_search/pipeline/.*$")), JunoRestClient::callRemote),
        // Map.entry(new ApiId("GET", Pattern.compile("^/_search/pipeline/.*$")), JunoRestClient::callRemote),
        // Map.entry(new ApiId("DELETE", Pattern.compile("^/_search/pipeline/.*$")), JunoRestClient::callRemote),
        Map.entry(new ApiId("GET", Pattern.compile("^/_cat/indices.*$")), JunoRestClient::callRemote),
        Map.entry(new ApiId("HEAD", Pattern.compile("^/" + INDEX_NAME_PATTERN + "$")), JunoRestClient::callRemote),
        Map.entry(new ApiId("PUT", Pattern.compile("^/" + INDEX_NAME_PATTERN + "$")), JunoRestClient::callRemote),
        Map.entry(new ApiId("PUT", Pattern.compile("^/" + INDEX_NAME_PATTERN + "/_settings.*$")), JunoRestClient::callRemote),
        Map.entry(
            new ApiId("PUT", Pattern.compile("^/" + INDEX_NAME_PATTERN + "/_alias/" + INDEX_NAME_PATTERN + "$")),
            JunoRestClient::callRemote
        ),
        Map.entry(new ApiId("DELETE", Pattern.compile("^/" + INDEX_NAME_PATTERN + "$")), JunoRestClient::callRemote),
        Map.entry(new ApiId("POST", Pattern.compile("^/" + INDEX_NAME_PATTERN + "/_bulk.*$")), JunoRestClient::callRemote),
        Map.entry(new ApiId("POST", Pattern.compile("^/" + INDEX_NAME_PATTERN + "/_doc$")), JunoRestClient::callRemote),
        // not actually supported, letting it fail at the gateway
        Map.entry(new ApiId("PUT", Pattern.compile("^_cluster/settings$")), JunoRestClient::noop)
    );

    private final RestClient remoteRestClient;

    JunoRestClient(
        CloseableHttpAsyncClient client,
        Header[] defaultHeaders,
        List<Node> nodes,
        String pathPrefix,
        FailureListener failureListener,
        NodeSelector nodeSelector,
        boolean strictDeprecationMode,
        boolean compressionEnabled,
        boolean chunkedEnabled
    ) {
        super(
            client,
            defaultHeaders,
            nodes,
            pathPrefix,
            failureListener,
            nodeSelector,
            strictDeprecationMode,
            compressionEnabled,
            chunkedEnabled
        );
        remoteRestClient = buildRemoteClient();
    }

    JunoRestClient(
        CloseableHttpAsyncClient client,
        Header[] defaultHeaders,
        List<Node> nodes,
        String pathPrefix,
        FailureListener failureListener,
        NodeSelector nodeSelector,
        boolean strictDeprecationMode,
        boolean compressionEnabled
    ) {
        super(client, defaultHeaders, nodes, pathPrefix, failureListener, nodeSelector, strictDeprecationMode, compressionEnabled);
        remoteRestClient = buildRemoteClient();
    }

    public static JunoRestClientBuilder junoBuilder(HttpHost... hosts) {
        if (hosts == null || hosts.length == 0) {
            throw new IllegalArgumentException("hosts must not be null nor empty");
        }
        List<Node> nodes = Arrays.stream(hosts).map(Node::new).collect(Collectors.toList());
        return new JunoRestClientBuilder(nodes);
    }

    @Override
    public synchronized void setNodes(Collection<Node> nodes) {
        super.setNodes(nodes);
    }

    @Override
    public List<Node> getNodes() {
        return List.of(new Node(new HttpHost(COLLECTION_HOST, 433, "https")));
    }

    @Override
    public boolean isRunning() {
        return super.isRunning() || remoteRestClient.isRunning();
    }

    @Override
    public void close() throws IOException {
        super.close();
        remoteRestClient.close();
    }

    @Override
    public Response performRequest(Request request) {
        for (var entry : API_HANDLERS.entrySet()) {
            if (entry.getKey().getMethod().equals(request.getMethod())
                && entry.getKey().getEndpoint().matcher(request.getEndpoint()).matches()) {
                return entry.getValue().handle(this, request);
            }
        }
        notSupported(String.format("performRequest(%s, %s)", request.getMethod(), request.getEndpoint()));
        return null;
    }

    @Override
    public Cancellable performRequestAsync(Request request, ResponseListener responseListener) {
        notSupported("performRequestAsync()");
        return null;
    }

    private static void notSupported(String method) {
        throw new UnsupportedOperationException(method + " is not supported by JunoRestClient");
    }

    private static boolean matches(List<Pattern> patterns, String str) {
        return patterns.stream().map(p -> p.matcher(str)).anyMatch(Matcher::matches);
    }

    private RestClient buildRemoteClient() {
        return RestClient.builder(HttpHost.create(COLLECTION_ENDPOINT)).setHttpClientConfigCallback(config -> {
            configureSigV4Signing(config, new DefaultAWSCredentialsProviderChain());
            return config;
        }).build();
    }

    private void configureSigV4Signing(HttpAsyncClientBuilder builder, AWSCredentialsProvider awsCredentialsProvider) {
        AWS4UnsignedPayloadSigner signer = new AWS4UnsignedPayloadSigner();
        signer.setServiceName(System.getProperty(SYS_PROPERTY_KEY_AWS_SERVICE));
        signer.setRegionName(System.getProperty(SYS_PROPERTY_KEY_AWS_REGION));
        HttpRequestInterceptor interceptor = new AWSRequestSigningApacheInterceptor(
            signer.getServiceName(),
            signer,
            awsCredentialsProvider
        );
        builder.addInterceptorLast(interceptor);
    }

    private static Response callLocal(JunoRestClient client, Request request) {
        request.setOptions(
            RequestOptions.DEFAULT.toBuilder()
                .setRequestConfig(
                    RequestConfig.copy(RequestConfig.DEFAULT)
                        .setSocketTimeout((int) TIMEOUT.toMillis())
                        .setConnectionRequestTimeout((int) TIMEOUT.toMillis())
                        .build()
                )
                .build()
        );
        return client.performRequestSuper(request);
    }

    @SneakyThrows
    private static Response callRemote(JunoRestClient client, Request request) {
        System.out.println(
            request.getOptions().getHeaders().toString() + " in JunoRestClient " + request.getMethod() + " " + request.getEndpoint()
        );
        return client.remoteRestClient.performRequest(request);
    }

    @SneakyThrows
    private Response performRequestSuper(Request request) {
        return super.performRequest(request);
    }

    @SneakyThrows
    private static Response noop(JunoRestClient client, Request request) {
        ProtocolVersion protocolVersion = new ProtocolVersion("HTTP", 2, 0);
        RequestLine requestLine = new BasicRequestLine(request.getMethod(), request.getEndpoint(), protocolVersion);
        HttpHost host = HttpHost.create("http://localhost:9200");
        HttpResponse response = new BasicHttpResponse(new BasicStatusLine(protocolVersion, 200, ""));
        response.setEntity(new StringEntity("{}"));

        return new Response(requestLine, host, response);
    }

    @Value
    private static final class ApiId {
        private final String method;
        private final Pattern endpoint;
    }

    private static interface ApiHandler {
        Response handle(JunoRestClient client, Request request);
    }
}
