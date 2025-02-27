/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.neuralsearch.common;

import static org.apache.http.entity.ContentType.APPLICATION_JSON;
import static org.opensearch.neuralsearch.common.VectorUtil.vectorAsListToArray;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.opensearch.client.Request;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.opensearch.client.WarningsHandler;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.knn.index.SpaceType;
import org.opensearch.neuralsearch.OpenSearchSecureRestCloudNativeTestCase;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;
import com.google.common.collect.ImmutableList;

@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
public abstract class BaseNeuralSearchCloudNativeIT extends OpenSearchSecureRestCloudNativeTestCase {

    protected static final Locale LOCALE = Locale.ROOT;

    private static final int MAX_TASK_RESULT_QUERY_TIME_IN_SECOND = 60 * 5;

    private static final int DEFAULT_TASK_RESULT_QUERY_INTERVAL_IN_MILLISECOND = 1000;

    protected static final String DEFAULT_USER_AGENT = "Kibana";
    protected static final String DEFAULT_NORMALIZATION_METHOD = "min_max";
    protected static final String DEFAULT_COMBINATION_METHOD = "arithmetic_mean";
    protected static final String PARAM_NAME_WEIGHTS = "weights";

    protected static final Map<ProcessorType, String> PIPELINE_CONFIGS_BY_TYPE = Map.of(
        ProcessorType.TEXT_EMBEDDING,
        "processor/PipelineConfiguration.json",
        ProcessorType.SPARSE_ENCODING,
        "processor/SparseEncodingPipelineConfiguration.json",
        ProcessorType.TEXT_IMAGE_EMBEDDING,
        "processor/PipelineForTextImageEmbeddingProcessorConfiguration.json"
    );

    protected final ClassLoader classLoader = this.getClass().getClassLoader();

    /**
     * Execute model inference on the provided query text
     *
     * @param modelId id of model to run inference
     * @param queryText text to be transformed to a model
     * @return text embedding
     */
    @SuppressWarnings("unchecked")
    @SneakyThrows
    protected float[] runInference(String modelId, String queryText) {
        Response inferenceResponse = makeRequest(
            client(),
            "POST",
            String.format(LOCALE, "/_plugins/_ml/_predict/text_embedding/%s", modelId),
            null,
            toHttpEntity(String.format(LOCALE, "{\"text_docs\": [\"%s\"],\"target_response\": [\"sentence_embedding\"]}", queryText)),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, "Kibana"))
        );

        Map<String, Object> inferenceResJson = XContentHelper.convertToMap(
            XContentType.JSON.xContent(),
            EntityUtils.toString(inferenceResponse.getEntity()),
            false
        );

        Object inference_results = inferenceResJson.get("inference_results");
        assertTrue(inference_results instanceof List);
        List<Object> inferenceResultsAsMap = (List<Object>) inference_results;
        assertEquals(1, inferenceResultsAsMap.size());
        Map<String, Object> result = (Map<String, Object>) inferenceResultsAsMap.get(0);
        List<Object> output = (List<Object>) result.get("output");
        assertEquals(1, output.size());
        Map<String, Object> map = (Map<String, Object>) output.get(0);
        List<Float> data = ((List<Double>) map.get("data")).stream().map(Double::floatValue).collect(Collectors.toList());
        return vectorAsListToArray(data);
    }

    protected void createIndexWithConfiguration(String indexName, String indexConfiguration, String pipelineName) throws Exception {
        if (StringUtils.isNotBlank(pipelineName)) {
            indexConfiguration = String.format(LOCALE, indexConfiguration, pipelineName);
        }
        Response response = makeRequest(
            client(),
            "PUT",
            '/' + indexName,
            null,
            toHttpEntity(indexConfiguration),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, "Kibana"))
        );
        Map<String, Object> node = XContentHelper.convertToMap(
            XContentType.JSON.xContent(),
            EntityUtils.toString(response.getEntity()),
            false
        );
        assertEquals("true", node.get("acknowledged").toString());
        assertEquals(indexName, node.get("index").toString());
    }

    protected void createPipelineProcessor(String modelId, String pipelineName) throws Exception {
        createPipelineProcessor(modelId, pipelineName, ProcessorType.TEXT_EMBEDDING);
    }

    protected void createPipelineProcessor(String modelId, String pipelineName, ProcessorType processorType) throws Exception {
        Response pipelineCreateResponse = makeRequest(
            client(),
            "PUT",
            "/_ingest/pipeline/" + pipelineName,
            null,
            toHttpEntity(
                String.format(
                    LOCALE,
                    Files.readString(Path.of(classLoader.getResource(PIPELINE_CONFIGS_BY_TYPE.get(processorType)).toURI())),
                    modelId
                )
            ),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, "Kibana"))
        );
        Map<String, Object> node = XContentHelper.convertToMap(
            XContentType.JSON.xContent(),
            EntityUtils.toString(pipelineCreateResponse.getEntity()),
            false
        );
        assertEquals("true", node.get("acknowledged").toString());
    }

    protected void createSearchRequestProcessor(String modelId, String pipelineName) throws Exception {
        Response pipelineCreateResponse = makeRequest(
            client(),
            "PUT",
            "/_search/pipeline/" + pipelineName,
            null,
            toHttpEntity(
                String.format(
                    LOCALE,
                    Files.readString(Path.of(classLoader.getResource("processor/SearchRequestPipelineConfiguration.json").toURI())),
                    modelId
                )
            ),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
        Map<String, Object> node = XContentHelper.convertToMap(
            XContentType.JSON.xContent(),
            EntityUtils.toString(pipelineCreateResponse.getEntity()),
            false
        );
        assertEquals("true", node.get("acknowledged").toString());
    }

    /**
     * Get the number of documents in a particular index
     *
     * @param indexName name of index
     * @return number of documents indexed to that index
     */
    @SneakyThrows
    protected int getDocCount(String indexName) {
        Request request = new Request("GET", "/" + indexName + "/_count");
        Response response = client().performRequest(request);
        assertEquals(request.getEndpoint() + ": failed", RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));
        String responseBody = EntityUtils.toString(response.getEntity());
        Map<String, Object> responseMap = createParser(XContentType.JSON.xContent(), responseBody).map();
        return (Integer) responseMap.get("count");
    }

    /**
     * Execute a search request initialized from a neural query builder
     *
     * @param index Index to search against
     * @param queryBuilder queryBuilder to produce source of query
     * @param resultSize number of results to return in the search
     * @return Search results represented as a map
     */
    protected Map<String, Object> search(String index, QueryBuilder queryBuilder, int resultSize) {
        return search(index, queryBuilder, null, resultSize);
    }

    /**
     * Execute a search request initialized from a neural query builder that can add a rescore query to the request
     *
     * @param index Index to search against
     * @param queryBuilder queryBuilder to produce source of query
     * @param  rescorer used for rescorer query builder
     * @param resultSize number of results to return in the search
     * @return Search results represented as a map
     */
    @SneakyThrows
    protected Map<String, Object> search(String index, QueryBuilder queryBuilder, QueryBuilder rescorer, int resultSize) {
        return search(index, queryBuilder, rescorer, resultSize, Map.of());
    }

    /**
     * Execute a search request initialized from a neural query builder that can add a rescore query to the request
     *
     * @param index Index to search against
     * @param queryBuilder queryBuilder to produce source of query
     * @param  rescorer used for rescorer query builder
     * @param resultSize number of results to return in the search
     * @param requestParams additional request params for search
     * @return Search results represented as a map
     */
    @SneakyThrows
    protected Map<String, Object> search(
        String index,
        QueryBuilder queryBuilder,
        QueryBuilder rescorer,
        int resultSize,
        Map<String, String> requestParams
    ) {
        XContentBuilder builder = XContentFactory.jsonBuilder().startObject().field("query");
        queryBuilder.toXContent(builder, ToXContent.EMPTY_PARAMS);

        if (rescorer != null) {
            builder.startObject("rescore").startObject("query").field("query_weight", 0.0f).field("rescore_query");
            rescorer.toXContent(builder, ToXContent.EMPTY_PARAMS);
            builder.endObject().endObject();
        }

        builder.endObject();

        Request request = new Request("POST", "/" + index + "/_search");
        request.addParameter("size", Integer.toString(resultSize));
        if (requestParams != null && !requestParams.isEmpty()) {
            requestParams.forEach(request::addParameter);
        }
        request.setJsonEntity(builder.toString());

        Response response = client().performRequest(request);
        assertEquals(request.getEndpoint() + ": failed", RestStatus.OK, RestStatus.fromCode(response.getStatusLine().getStatusCode()));

        String responseBody = EntityUtils.toString(response.getEntity());

        return XContentHelper.convertToMap(XContentType.JSON.xContent(), responseBody, false);
    }

    /**
     * Add a set of knn vectors
     *
     * @param index Name of the index
     * @param vectorFieldNames List of vectir fields to be added
     * @param vectors List of vectors corresponding to those fields
     */
    protected void addKnnDoc(String index, List<String> vectorFieldNames, List<Object[]> vectors) {
        addKnnDoc(index, vectorFieldNames, vectors, Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Add a set of knn vectors and text to an index
     *
     * @param index Name of the index
     * @param vectorFieldNames List of vectir fields to be added
     * @param vectors List of vectors corresponding to those fields
     * @param textFieldNames List of text fields to be added
     * @param texts List of text corresponding to those fields
     */
    @SneakyThrows
    protected void addKnnDoc(
        String index,
        List<String> vectorFieldNames,
        List<Object[]> vectors,
        List<String> textFieldNames,
        List<String> texts
    ) {
        Request request = new Request("POST", "/" + index + "/_doc");
        XContentBuilder builder = XContentFactory.jsonBuilder().startObject();
        for (int i = 0; i < vectorFieldNames.size(); i++) {
            builder.field(vectorFieldNames.get(i), vectors.get(i));
        }

        for (int i = 0; i < textFieldNames.size(); i++) {
            builder.field(textFieldNames.get(i), texts.get(i));
        }
        builder.endObject();
        Response response = makeRequest(
                client(),
                "POST",
                "/" + index + "/_doc",
                null,
                toHttpEntity(builder.toString()),
                ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, "Kibana"))
        );
        System.out.println(
            request.getOptions().getHeaders().toString() + " in BaseNeuralSearchIT " + EntityUtils.toString(request.getEntity())
        );
        assertEquals(request.getEndpoint() + ": failed", RestStatus.CREATED, RestStatus.fromCode(response.getStatusLine().getStatusCode()));
    }

    /**
     * Parse the first returned hit from a search response as a map
     *
     * @param searchResponseAsMap Complete search response as a map
     * @return Map of first internal hit from the search
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> getFirstInnerHit(Map<String, Object> searchResponseAsMap) {
        Map<String, Object> hits1map = (Map<String, Object>) searchResponseAsMap.get("hits");
        List<Object> hits2List = (List<Object>) hits1map.get("hits");
        assertFalse(hits2List.isEmpty());
        return (Map<String, Object>) hits2List.get(0);
    }

    /**
     * Parse the total number of hits from the search
     *
     * @param searchResponseAsMap Complete search response as a map
     * @return number of hits from the search
     */
    @SuppressWarnings("unchecked")
    protected int getHitCount(Map<String, Object> searchResponseAsMap) {
        Map<String, Object> hits1map = (Map<String, Object>) searchResponseAsMap.get("hits");
        List<Object> hits1List = (List<Object>) hits1map.get("hits");
        return hits1List.size();
    }

    /**
     * Create a k-NN index from a list of KNNFieldConfigs
     *
     * @param indexName of index to be created
     * @param knnFieldConfigs list of configs specifying field
     */
    @SneakyThrows
    protected void prepareKnnIndex(String indexName, List<KNNFieldConfig> knnFieldConfigs) {
        createIndexWithConfiguration(indexName, buildIndexConfiguration(knnFieldConfigs), "");
    }

    /**
     * Computes the expected distance between an indexVector and query text without using the neural query type.
     *
     * @param modelId ID of model to run inference
     * @param indexVector vector to compute score against
     * @param spaceType Space to measure distance
     * @param queryText Text to produce query vector from
     * @return Expected OpenSearch score for this indexVector
     */
    protected float computeExpectedScore(String modelId, float[] indexVector, SpaceType spaceType, String queryText) {
        float[] queryVector = runInference(modelId, queryText);
        return spaceType.getVectorSimilarityFunction().compare(queryVector, indexVector);
    }

    @SneakyThrows
    private String buildIndexConfiguration(List<KNNFieldConfig> knnFieldConfigs) {
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("settings")
            .field("index.knn", true)
            .endObject()
            .startObject("mappings")
            .startObject("properties");

        // Lucene engine is not supported on AOSS
        for (KNNFieldConfig knnFieldConfig : knnFieldConfigs) {
            xContentBuilder.startObject(knnFieldConfig.getName())
                .field("type", "knn_vector")
                .field("dimension", Integer.toString(knnFieldConfig.getDimension()))
                .startObject("method")
                .field("space_type", knnFieldConfig.getSpaceType().getValue())
                .field("name", "hnsw")
                .endObject()
                .endObject();
        }
        xContentBuilder.endObject().endObject().endObject();
        return xContentBuilder.toString();
    }

    protected static Response makeRequest(
        RestClient client,
        String method,
        String endpoint,
        Map<String, String> params,
        HttpEntity entity,
        List<Header> headers
    ) throws IOException {
        return makeRequest(client, method, endpoint, params, entity, headers, false);
    }

    protected static Response makeRequest(
        RestClient client,
        String method,
        String endpoint,
        Map<String, String> params,
        HttpEntity entity,
        List<Header> headers,
        boolean strictDeprecationMode
    ) throws IOException {
        Request request = new Request(method, endpoint);

        RequestOptions.Builder options = RequestOptions.DEFAULT.toBuilder();
        if (headers != null) {
            headers.forEach(header -> options.addHeader(header.getName(), header.getValue()));
        }
        options.setWarningsHandler(strictDeprecationMode ? WarningsHandler.STRICT : WarningsHandler.PERMISSIVE);
        request.setOptions(options.build());

        if (params != null) {
            params.forEach(request::addParameter);
        }
        if (entity != null) {
            request.setEntity(entity);
        }
        System.out.println(
            request.getOptions().getHeaders().toString() + " in BaseNeuralSearchIT " + EntityUtils.toString(request.getEntity())
        );
        return client.performRequest(request);
    }

    protected static HttpEntity toHttpEntity(String jsonString) {
        return new StringEntity(jsonString, APPLICATION_JSON);
    }

    @AllArgsConstructor
    @Getter
    protected static class KNNFieldConfig {
        private final String name;
        private final Integer dimension;
        private final SpaceType spaceType;
    }

    @SneakyThrows
    protected void createSearchPipelineWithResultsPostProcessor(final String pipelineId) {
        createSearchPipeline(pipelineId, DEFAULT_NORMALIZATION_METHOD, DEFAULT_COMBINATION_METHOD, Map.of());
    }

    @SneakyThrows
    protected void createSearchPipeline(
        final String pipelineId,
        final String normalizationMethod,
        String combinationMethod,
        final Map<String, String> combinationParams
    ) {
        StringBuilder stringBuilderForContentBody = new StringBuilder();
        stringBuilderForContentBody.append("{\"description\": \"Post processor pipeline\",")
            .append("\"phase_results_processors\": [{ ")
            .append("\"normalization-processor\": {")
            .append("\"normalization\": {")
            .append("\"technique\": \"%s\"")
            .append("},")
            .append("\"combination\": {")
            .append("\"technique\": \"%s\"");
        if (Objects.nonNull(combinationParams) && !combinationParams.isEmpty()) {
            stringBuilderForContentBody.append(", \"parameters\": {");
            if (combinationParams.containsKey(PARAM_NAME_WEIGHTS)) {
                stringBuilderForContentBody.append("\"weights\": ").append(combinationParams.get(PARAM_NAME_WEIGHTS));
            }
            stringBuilderForContentBody.append(" }");
        }
        stringBuilderForContentBody.append("}").append("}}]}");
        makeRequest(
            client(),
            "PUT",
            String.format(LOCALE, "/_search/pipeline/%s", pipelineId),
            null,
            toHttpEntity(String.format(LOCALE, stringBuilderForContentBody.toString(), normalizationMethod, combinationMethod)),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
    }

    @SneakyThrows
    protected void createSearchPipelineWithDefaultResultsPostProcessor(final String pipelineId) {
        makeRequest(
            client(),
            "PUT",
            String.format(LOCALE, "/_search/pipeline/%s", pipelineId),
            null,
            toHttpEntity(
                String.format(
                    LOCALE,
                    "{\"description\": \"Post processor pipeline\","
                        + "\"phase_results_processors\": [{ "
                        + "\"normalization-processor\": {}}]}"
                )
            ),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
    }

    @SneakyThrows
    protected void deleteSearchPipeline(final String pipelineId) {
        makeRequest(
            client(),
            "DELETE",
            String.format(LOCALE, "/_search/pipeline/%s", pipelineId),
            null,
            toHttpEntity(""),
            ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, DEFAULT_USER_AGENT))
        );
    }

    /**
     * Enumeration for types of pipeline processors, used to lookup resources like create
     * processor request as those are type specific
     */
    protected enum ProcessorType {
        TEXT_EMBEDDING,
        TEXT_IMAGE_EMBEDDING,
        SPARSE_ENCODING
    }
}
