/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.neuralsearch.query;

import static org.opensearch.neuralsearch.TestUtils.createRandomVector;
import static org.opensearch.neuralsearch.TestUtils.objectToFloat;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import org.apache.http.util.EntityUtils;
import org.junit.Ignore;
import org.junit.Test;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.index.query.MatchQueryBuilder;
import org.opensearch.knn.index.SpaceType;
import org.opensearch.neuralsearch.common.BaseNeuralSearchCloudNativeIT;

import com.google.common.primitives.Floats;

@Log4j2
public class NeuralQueryCloudNativeIT extends BaseNeuralSearchCloudNativeIT {
    protected static final int DEFAULT_REFRESH_INTERNAL_IN_MILLISECONDS = 20000;
    private static final String SYS_PROPERTY_KEY_EMBEDDING_MODEL_ID = "model.embedding";
    private static final String EMBEDDING_MODEL_ID = System.getProperty(SYS_PROPERTY_KEY_EMBEDDING_MODEL_ID);
    private static final String TEST_BASIC_INDEX_NAME = "test-neural-basic-index";
    private static final String TEST_MULTI_VECTOR_FIELD_INDEX_NAME = "test-neural-multi-vector-field-index";
    private static final String TEST_TEXT_AND_VECTOR_FIELD_INDEX_NAME = "test-neural-text-and-vector-field-index";
    private static final String TEST_NESTED_INDEX_NAME = "test-neural-nested-index";
    private static final String TEST_MULTI_DOC_INDEX_NAME = "test-neural-multi-doc-index";
    private static final String TEST_QUERY_TEXT = "Hello world";
    private static final String TEST_IMAGE_TEXT = "/9j/4AAQSkZJRgABAQAASABIAAD";
    private static final String TEST_KNN_VECTOR_FIELD_NAME_1 = "test-knn-vector-1";
    private static final String TEST_KNN_VECTOR_FIELD_NAME_2 = "test-knn-vector-2";
    private static final String TEST_TEXT_FIELD_NAME_1 = "test-text-field";
    private static final String TEST_KNN_VECTOR_FIELD_NAME_NESTED = "nested.knn.field";
    private static final String SYS_PROPERTY_KEY_VECTOR_DIMENSION = "vector.dimension";
    private static final int TEST_DIMENSION = Integer.parseInt(System.getProperty(SYS_PROPERTY_KEY_VECTOR_DIMENSION));
    private static final SpaceType TEST_SPACE_TYPE = SpaceType.L2;
    private final float[] testVector = createRandomVector(TEST_DIMENSION);

    @Test
    public void testGetEmbeddingModel() throws IOException {
        Response response = client().performRequest(new Request("GET", "/_plugins/_ml/models/" + EMBEDDING_MODEL_ID));
        assertEquals(200, response.getStatusLine().getStatusCode());
    }

    /**
    * Tests basic query:
    * {
    *     "query": {
    *         "neural": {
    *             "text_knn": {
    *                 "query_text": "Hello world",
    *                 "model_id": "dcsdcasd",
    *                 "k": 1
    *             }
    *         }
    *     }
    * }
    */
    @SneakyThrows
    public void testBasicQuery() {
        try {
            initializeIndexIfNotExist(TEST_BASIC_INDEX_NAME);
        } catch (ResponseException e) {
            throw new RuntimeException(
                Arrays.toString(e.getResponse().getHeaders()) + " in NeuralQueryIT " + EntityUtils.toString(e.getResponse().getEntity())
            );
        }
        NeuralQueryBuilder neuralQueryBuilder = new NeuralQueryBuilder(
            TEST_KNN_VECTOR_FIELD_NAME_1,
            TEST_QUERY_TEXT,
            "",
            EMBEDDING_MODEL_ID,
            1,
            null,
            null
        );
        Map<String, Object> searchResponseAsMap = search(TEST_BASIC_INDEX_NAME, neuralQueryBuilder, 1);
        Map<String, Object> firstInnerHit = getFirstInnerHit(searchResponseAsMap);
        float expectedScore = computeExpectedScore(EMBEDDING_MODEL_ID, testVector, TEST_SPACE_TYPE, TEST_QUERY_TEXT);
        assertEquals(expectedScore, objectToFloat(firstInnerHit.get("_score")), 0.0);
    }


    /**
     * Tests basic query with boost parameter:
     * {
     *     "query": {
     *         "neural": {
     *             "text_knn": {
     *                 "query_text": "Hello world",
     *                 "model_id": "dcsdcasd",
     *                 "k": 1,
     *                 "boost": 2.0
     *             }
     *         }
     *     }
     * }
     */
    @SneakyThrows
    public void testBoostQuery() {
        initializeIndexIfNotExist(TEST_BASIC_INDEX_NAME);
        NeuralQueryBuilder neuralQueryBuilder = new NeuralQueryBuilder(
            TEST_KNN_VECTOR_FIELD_NAME_1,
            TEST_QUERY_TEXT,
            "",
            EMBEDDING_MODEL_ID,
            1,
            null,
            null
        );

        final float boost = 2.0f;
        neuralQueryBuilder.boost(boost);
        Map<String, Object> searchResponseAsMap = search(TEST_BASIC_INDEX_NAME, neuralQueryBuilder, 1);
        Map<String, Object> firstInnerHit = getFirstInnerHit(searchResponseAsMap);

        float expectedScore = 2 * computeExpectedScore(EMBEDDING_MODEL_ID, testVector, TEST_SPACE_TYPE, TEST_QUERY_TEXT);
        assertEquals(expectedScore, objectToFloat(firstInnerHit.get("_score")), 0.0);
    }

    /**
     * Tests rescore query:
     * {
     *     "query" : {
     *       "match_all": {}
     *     },
     *     "rescore": {
     *         "query": {
     *              "rescore_query": {
     *                  "neural": {
     *                      "text_knn": {
     *                          "query_text": "Hello world",
     *                          "model_id": "dcsdcasd",
     *                          "k": 1
     *                      }
     *                  }
     *              }
     *          }
     *    }
     */
    @SneakyThrows
    public void testRescoreQuery() {
        initializeIndexIfNotExist(TEST_BASIC_INDEX_NAME);
        MatchAllQueryBuilder matchAllQueryBuilder = new MatchAllQueryBuilder();
        NeuralQueryBuilder rescoreNeuralQueryBuilder = new NeuralQueryBuilder(
            TEST_KNN_VECTOR_FIELD_NAME_1,
            TEST_QUERY_TEXT,
            "",
            EMBEDDING_MODEL_ID,
            1,
            null,
            null
        );

        Map<String, Object> searchResponseAsMap = search(TEST_BASIC_INDEX_NAME, matchAllQueryBuilder, rescoreNeuralQueryBuilder, 1);
        Map<String, Object> firstInnerHit = getFirstInnerHit(searchResponseAsMap);

        float expectedScore = computeExpectedScore(EMBEDDING_MODEL_ID, testVector, TEST_SPACE_TYPE, TEST_QUERY_TEXT);
        assertEquals(expectedScore, objectToFloat(firstInnerHit.get("_score")), 0.0);
    }

    /**
     * Tests bool should query with vectors:
     * {
     *     "query": {
     *         "bool" : {
     *             "should": [
     *                 "neural": {
     *                     "field_1": {
     *                         "query_text": "Hello world",
     *                         "model_id": "dcsdcasd",
     *                         "k": 1
     *                     },
     *                  },
     *                  "neural": {
     *                     "field_2": {
     *                         "query_text": "Hello world",
     *                         "model_id": "dcsdcasd",
     *                         "k": 1
     *                     }
     *                  }
     *             ]
     *         }
     *     }
     * }
     */
    @SneakyThrows
    public void testBooleanQuery_withMultipleNeuralQueries() {
        initializeIndexIfNotExist(TEST_MULTI_VECTOR_FIELD_INDEX_NAME);
        BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();

        NeuralQueryBuilder neuralQueryBuilder1 = new NeuralQueryBuilder(
            TEST_KNN_VECTOR_FIELD_NAME_1,
            TEST_QUERY_TEXT,
            "",
            EMBEDDING_MODEL_ID,
            1,
            null,
            null
        );
        NeuralQueryBuilder neuralQueryBuilder2 = new NeuralQueryBuilder(
            TEST_KNN_VECTOR_FIELD_NAME_2,
            TEST_QUERY_TEXT,
            "",
            EMBEDDING_MODEL_ID,
            1,
            null,
            null
        );

        boolQueryBuilder.should(neuralQueryBuilder1).should(neuralQueryBuilder2);

        Map<String, Object> searchResponseAsMap = search(TEST_MULTI_VECTOR_FIELD_INDEX_NAME, boolQueryBuilder, 1);
        Map<String, Object> firstInnerHit = getFirstInnerHit(searchResponseAsMap);

        float expectedScore = 2 * computeExpectedScore(EMBEDDING_MODEL_ID, testVector, TEST_SPACE_TYPE, TEST_QUERY_TEXT);
        assertEquals(expectedScore, objectToFloat(firstInnerHit.get("_score")), 0.0);
    }

    /**
     * Tests bool should with BM25 and neural query:
     * {
     *     "query": {
     *         "bool" : {
     *             "should": [
     *                 "neural": {
     *                     "field_1": {
     *                         "query_text": "Hello world",
     *                         "model_id": "dcsdcasd",
     *                         "k": 1
     *                     },
     *                  },
     *                  "match": {
     *                     "field_2": {
     *                          "query": "Hello world"
     *                     }
     *                  }
     *             ]
     *         }
     *     }
     * }
     */
    @SneakyThrows
    public void testBooleanQuery_withNeuralAndBM25Queries() {
        initializeIndexIfNotExist(TEST_TEXT_AND_VECTOR_FIELD_INDEX_NAME);
        BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();

        NeuralQueryBuilder neuralQueryBuilder = new NeuralQueryBuilder(
            TEST_KNN_VECTOR_FIELD_NAME_1,
            TEST_QUERY_TEXT,
            "",
            EMBEDDING_MODEL_ID,
            1,
            null,
            null
        );

        MatchQueryBuilder matchQueryBuilder = new MatchQueryBuilder(TEST_TEXT_FIELD_NAME_1, TEST_QUERY_TEXT);

        boolQueryBuilder.should(neuralQueryBuilder).should(matchQueryBuilder);

        Map<String, Object> searchResponseAsMap = search(TEST_TEXT_AND_VECTOR_FIELD_INDEX_NAME, boolQueryBuilder, 1);
        Map<String, Object> firstInnerHit = getFirstInnerHit(searchResponseAsMap);

        float minExpectedScore = computeExpectedScore(EMBEDDING_MODEL_ID, testVector, TEST_SPACE_TYPE, TEST_QUERY_TEXT);
        assertTrue(minExpectedScore < objectToFloat(firstInnerHit.get("_score")));
    }

    /**
     * Tests nested query:
     * {
     *     "query": {
     *         "nested" : {
     *             "query": {
     *                 "neural": {
     *                     "field_1": {
     *                         "query_text": "Hello world",
     *                         "model_id": "dcsdcasd",
     *                         "k": 1
     *                     },
     *                  }
     *              }
     *          }
     *      }
     * }
     */
    @SneakyThrows
    public void testNestedQuery() {
        initializeIndexIfNotExist(TEST_NESTED_INDEX_NAME);
        NeuralQueryBuilder neuralQueryBuilder = new NeuralQueryBuilder(
            TEST_KNN_VECTOR_FIELD_NAME_NESTED,
            TEST_QUERY_TEXT,
            "",
            EMBEDDING_MODEL_ID,
            1,
            null,
            null
        );

        Map<String, Object> searchResponseAsMap = search(TEST_NESTED_INDEX_NAME, neuralQueryBuilder, 1);
        Map<String, Object> firstInnerHit = getFirstInnerHit(searchResponseAsMap);

        float expectedScore = computeExpectedScore(EMBEDDING_MODEL_ID, testVector, TEST_SPACE_TYPE, TEST_QUERY_TEXT);
        assertEquals(expectedScore, objectToFloat(firstInnerHit.get("_score")), 0.0);
    }

    /**
     * Tests filter query:
     * {
     *     "query": {
     *         "neural": {
     *             "text_knn": {
     *                 "query_text": "Hello world",
     *                 "model_id": "dcsdcasd",
     *                 "k": 1,
     *                 "filter": {
     *                     "match": {
     *                         "_id": {
     *                             "query": "3"
     *                         }
     *                     }
     *                 }
     *             }
     *         }
     *     }
     * }
     */
    @Ignore("Temporarily ignore it due to the issue of the filter query")
    @SneakyThrows
    public void testFilterQuery() {
        initializeIndexIfNotExist(TEST_MULTI_DOC_INDEX_NAME);
        NeuralQueryBuilder neuralQueryBuilder = new NeuralQueryBuilder(
            TEST_KNN_VECTOR_FIELD_NAME_1,
            TEST_QUERY_TEXT,
            "",
            EMBEDDING_MODEL_ID,
            1,
            null,
            new MatchQueryBuilder("_id", "3")
        );
        Map<String, Object> searchResponseAsMap = search(TEST_MULTI_DOC_INDEX_NAME, neuralQueryBuilder, 3);
        assertEquals(1, getHitCount(searchResponseAsMap));
        Map<String, Object> firstInnerHit = getFirstInnerHit(searchResponseAsMap);
        float expectedScore = computeExpectedScore(EMBEDDING_MODEL_ID, testVector, TEST_SPACE_TYPE, TEST_QUERY_TEXT);
        assertEquals(expectedScore, objectToFloat(firstInnerHit.get("_score")), 0.0);
    }

    /**
     * Tests basic query for multimodal:
     * {
     *     "query": {
     *         "neural": {
     *             "text_knn": {
     *                 "query_text": "Hello world",
     *                 "query_image": "base64_1234567890",
     *                 "model_id": "dcsdcasd",
     *                 "k": 1
     *             }
     *         }
     *     }
     * }
     */
    @SneakyThrows
    public void testMultimodalQuery() {
        initializeIndexIfNotExist(TEST_BASIC_INDEX_NAME);
        NeuralQueryBuilder neuralQueryBuilder = new NeuralQueryBuilder(
            TEST_KNN_VECTOR_FIELD_NAME_1,
            TEST_QUERY_TEXT,
            TEST_IMAGE_TEXT,
            EMBEDDING_MODEL_ID,
            1,
            null,
            null
        );
        Map<String, Object> searchResponseAsMap = search(TEST_BASIC_INDEX_NAME, neuralQueryBuilder, 1);
        Map<String, Object> firstInnerHit = getFirstInnerHit(searchResponseAsMap);

        float expectedScore = computeExpectedScore(EMBEDDING_MODEL_ID, testVector, TEST_SPACE_TYPE, TEST_QUERY_TEXT);
        assertEquals(expectedScore, objectToFloat(firstInnerHit.get("_score")), 0.0);
    }

    @Ignore("This test is not supported on AOSS")
    @Test
    public void testGetPipeline() throws IOException, InterruptedException {
        log.info("Hello world");
        try {
            Response getResponse = client().performRequest(new Request("GET", "/_search/pipeline/phase-results-pipeline"));
            throw new ResponseException(getResponse);
        } catch (ResponseException e) {
            throw new RuntimeException(
                Arrays.toString(e.getResponse().getHeaders()) + ' ' + EntityUtils.toString(e.getResponse().getEntity())
            );
        }
    }

    @Ignore("This test is not supported on AOSS")
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

    private void initializeIndexIfNotExist(String indexName) throws IOException {
        if (TEST_BASIC_INDEX_NAME.equals(indexName) && !indexExists(TEST_BASIC_INDEX_NAME)) {
            prepareKnnIndex(
                TEST_BASIC_INDEX_NAME,
                Collections.singletonList(new KNNFieldConfig(TEST_KNN_VECTOR_FIELD_NAME_1, TEST_DIMENSION, TEST_SPACE_TYPE))
            );
            addKnnDoc(
                TEST_BASIC_INDEX_NAME,
                Collections.singletonList(TEST_KNN_VECTOR_FIELD_NAME_1),
                Collections.singletonList(Floats.asList(testVector).toArray())
            );
            assertEquals(1, getDocCount(TEST_BASIC_INDEX_NAME));
        }

        if (TEST_MULTI_VECTOR_FIELD_INDEX_NAME.equals(indexName) && !indexExists(TEST_MULTI_VECTOR_FIELD_INDEX_NAME)) {
            prepareKnnIndex(
                TEST_MULTI_VECTOR_FIELD_INDEX_NAME,
                List.of(
                    new KNNFieldConfig(TEST_KNN_VECTOR_FIELD_NAME_1, TEST_DIMENSION, TEST_SPACE_TYPE),
                    new KNNFieldConfig(TEST_KNN_VECTOR_FIELD_NAME_2, TEST_DIMENSION, TEST_SPACE_TYPE)
                )
            );
            addKnnDoc(
                TEST_MULTI_VECTOR_FIELD_INDEX_NAME,
                List.of(TEST_KNN_VECTOR_FIELD_NAME_1, TEST_KNN_VECTOR_FIELD_NAME_2),
                List.of(Floats.asList(testVector).toArray(), Floats.asList(testVector).toArray())
            );
            assertEquals(1, getDocCount(TEST_MULTI_VECTOR_FIELD_INDEX_NAME));
        }

        if (TEST_NESTED_INDEX_NAME.equals(indexName) && !indexExists(TEST_NESTED_INDEX_NAME)) {
            prepareKnnIndex(
                TEST_NESTED_INDEX_NAME,
                Collections.singletonList(new KNNFieldConfig(TEST_KNN_VECTOR_FIELD_NAME_NESTED, TEST_DIMENSION, TEST_SPACE_TYPE))
            );
            addKnnDoc(
                TEST_NESTED_INDEX_NAME,
                Collections.singletonList(TEST_KNN_VECTOR_FIELD_NAME_NESTED),
                Collections.singletonList(Floats.asList(testVector).toArray())
            );
            assertEquals(1, getDocCount(TEST_NESTED_INDEX_NAME));
        }

        if (TEST_TEXT_AND_VECTOR_FIELD_INDEX_NAME.equals(indexName) && !indexExists(TEST_TEXT_AND_VECTOR_FIELD_INDEX_NAME)) {
            prepareKnnIndex(
                TEST_TEXT_AND_VECTOR_FIELD_INDEX_NAME,
                Collections.singletonList(new KNNFieldConfig(TEST_KNN_VECTOR_FIELD_NAME_1, TEST_DIMENSION, TEST_SPACE_TYPE))
            );
            addKnnDoc(
                TEST_TEXT_AND_VECTOR_FIELD_INDEX_NAME,
                Collections.singletonList(TEST_KNN_VECTOR_FIELD_NAME_1),
                Collections.singletonList(Floats.asList(testVector).toArray()),
                Collections.singletonList(TEST_TEXT_FIELD_NAME_1),
                Collections.singletonList(TEST_QUERY_TEXT)
            );
            assertEquals(1, getDocCount(TEST_TEXT_AND_VECTOR_FIELD_INDEX_NAME));
        }

        if (TEST_MULTI_DOC_INDEX_NAME.equals(indexName) && !indexExists(TEST_MULTI_DOC_INDEX_NAME)) {
            prepareKnnIndex(
                TEST_MULTI_DOC_INDEX_NAME,
                Collections.singletonList(new KNNFieldConfig(TEST_KNN_VECTOR_FIELD_NAME_1, TEST_DIMENSION, TEST_SPACE_TYPE))
            );
            addKnnDoc(
                TEST_MULTI_DOC_INDEX_NAME,
                Collections.singletonList(TEST_KNN_VECTOR_FIELD_NAME_1),
                Collections.singletonList(Floats.asList(testVector).toArray())
            );
            addKnnDoc(
                TEST_MULTI_DOC_INDEX_NAME,
                Collections.singletonList(TEST_KNN_VECTOR_FIELD_NAME_1),
                Collections.singletonList(Floats.asList(testVector).toArray())
            );
            addKnnDoc(
                TEST_MULTI_DOC_INDEX_NAME,
                Collections.singletonList(TEST_KNN_VECTOR_FIELD_NAME_1),
                Collections.singletonList(Floats.asList(testVector).toArray())
            );
            assertEquals(3, getDocCount(TEST_MULTI_DOC_INDEX_NAME));
        }
    }
}
