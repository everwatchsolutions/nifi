/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.cluster.coordination.http.replication;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

import org.apache.nifi.cluster.context.ClusterContext;
import org.apache.nifi.cluster.context.ClusterContextImpl;
import org.apache.nifi.cluster.coordination.ClusterCoordinator;
import org.apache.nifi.cluster.coordination.http.HttpResponseMerger;
import org.apache.nifi.cluster.coordination.http.StandardHttpResponseMerger;
import org.apache.nifi.cluster.coordination.node.NodeConnectionState;
import org.apache.nifi.cluster.flow.DataFlowManagementService;
import org.apache.nifi.cluster.flow.PersistedFlowState;
import org.apache.nifi.cluster.manager.NodeResponse;
import org.apache.nifi.cluster.manager.exception.ConnectingNodeMutableRequestException;
import org.apache.nifi.cluster.manager.exception.DisconnectedNodeMutableRequestException;
import org.apache.nifi.cluster.manager.exception.IllegalClusterStateException;
import org.apache.nifi.cluster.manager.exception.UriConstructionException;
import org.apache.nifi.cluster.manager.impl.WebClusterManager;
import org.apache.nifi.cluster.protocol.NodeIdentifier;
import org.apache.nifi.events.EventReporter;
import org.apache.nifi.logging.NiFiLog;
import org.apache.nifi.reporting.Severity;
import org.apache.nifi.util.FormatUtils;
import org.apache.nifi.web.OptimisticLockingManager;
import org.apache.nifi.web.util.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.filter.GZIPContentEncodingFilter;
import com.sun.jersey.core.util.MultivaluedMapImpl;

public class ThreadPoolRequestReplicator implements RequestReplicator {
    /**
     * The HTTP header to store a cluster context. An example of what may be stored in the context is a node's
     * auditable actions in response to a cluster request. The cluster context is serialized
     * using Java's serialization mechanism and hex encoded.
     */
    static final String CLUSTER_CONTEXT_HTTP_HEADER = "X-ClusterContext";

    /**
     * The HTTP header that the NCM specifies to ask a node if they are able to process a given request. The value
     * is always 150-NodeContinue. The node will respond with 150 CONTINUE if it is able to
     * process the request, 417 EXPECTATION_FAILED otherwise.
     */
    static final String NCM_EXPECTS_HTTP_HEADER = "X-NcmExpects";
    static final String NODE_CONTINUE = "150-NodeContinue";
    static final int NODE_CONTINUE_STATUS_CODE = 150;

    private static final Logger logger = new NiFiLog(LoggerFactory.getLogger(ThreadPoolRequestReplicator.class));
    private static final int MAX_CONCURRENT_REQUESTS = 100;

    private final Client client; // the client to use for issuing requests
    private final int numThreads; // number of threads to use for request replication
    private final int connectionTimeoutMs; // connection timeout per node request
    private final int readTimeoutMs; // read timeout per node request
    private final HttpResponseMerger responseMerger;
    private final EventReporter eventReporter;
    private final RequestCompletionCallback callback;
    private final ClusterCoordinator clusterCoordinator;
    private final OptimisticLockingManager lockingManager;
    private final DataFlowManagementService dfmService;

    private ExecutorService executorService;
    private ScheduledExecutorService maintenanceExecutor;

    private final ConcurrentMap<String, StandardAsyncClusterResponse> responseMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<NodeIdentifier, AtomicInteger> sequentialLongRequestCounts = new ConcurrentHashMap<>();

    /**
     * Creates an instance using a connection timeout and read timeout of 3 seconds
     *
     * @param numThreads the number of threads to use when parallelizing requests
     * @param client a client for making requests
     * @param clusterCoordinator the cluster coordinator to use for interacting with node statuses
     * @param callback a callback that will be called whenever all of the responses have been gathered for a request. May be null.
     * @param eventReporter an EventReporter that can be used to notify users of interesting events. May be null.
     */
    public ThreadPoolRequestReplicator(final int numThreads, final Client client, final ClusterCoordinator clusterCoordinator,
        final RequestCompletionCallback callback, final EventReporter eventReporter, final OptimisticLockingManager lockingManager, final DataFlowManagementService dfmService) {
        this(numThreads, client, clusterCoordinator, "3 sec", "3 sec", callback, eventReporter, null, lockingManager, dfmService);
    }

    /**
     * Creates an instance.
     *
     * @param numThreads the number of threads to use when parallelizing requests
     * @param client a client for making requests
     * @param clusterCoordinator the cluster coordinator to use for interacting with node statuses
     * @param connectionTimeout the connection timeout specified in milliseconds
     * @param readTimeout the read timeout specified in milliseconds
     * @param callback a callback that will be called whenever all of the responses have been gathered for a request. May be null.
     * @param eventReporter an EventReporter that can be used to notify users of interesting events. May be null.
     */
    public ThreadPoolRequestReplicator(final int numThreads, final Client client, final ClusterCoordinator clusterCoordinator,
        final String connectionTimeout, final String readTimeout, final RequestCompletionCallback callback, final EventReporter eventReporter,
        final WebClusterManager clusterManager, final OptimisticLockingManager lockingManager, final DataFlowManagementService dfmService) {
        if (numThreads <= 0) {
            throw new IllegalArgumentException("The number of threads must be greater than zero.");
        } else if (client == null) {
            throw new IllegalArgumentException("Client may not be null.");
        }

        this.numThreads = numThreads;
        this.client = client;
        this.clusterCoordinator = clusterCoordinator;
        this.connectionTimeoutMs = (int) FormatUtils.getTimeDuration(connectionTimeout, TimeUnit.MILLISECONDS);
        this.readTimeoutMs = (int) FormatUtils.getTimeDuration(readTimeout, TimeUnit.MILLISECONDS);
        this.responseMerger = new StandardHttpResponseMerger(clusterManager);
        this.eventReporter = eventReporter;
        this.callback = callback;
        this.lockingManager = lockingManager;
        this.dfmService = dfmService;

        client.getProperties().put(ClientConfig.PROPERTY_CONNECT_TIMEOUT, connectionTimeoutMs);
        client.getProperties().put(ClientConfig.PROPERTY_READ_TIMEOUT, readTimeoutMs);
        client.getProperties().put(ClientConfig.PROPERTY_FOLLOW_REDIRECTS, Boolean.TRUE);
    }

    @Override
    public void start() {
        if (isRunning()) {
            return;
        }

        executorService = Executors.newFixedThreadPool(numThreads);
        maintenanceExecutor = Executors.newScheduledThreadPool(1, new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable r) {
                final Thread t = Executors.defaultThreadFactory().newThread(r);
                t.setDaemon(true);
                t.setName(ThreadPoolRequestReplicator.class.getSimpleName() + " Maintenance Thread");
                return t;
            }
        });

        maintenanceExecutor.scheduleWithFixedDelay(new PurgeExpiredRequestsTask(), 3, 3, TimeUnit.SECONDS);
    }

    @Override
    public boolean isRunning() {
        return executorService != null && !executorService.isShutdown();
    }

    @Override
    public void stop() {
        if (!isRunning()) {
            return;
        }

        executorService.shutdown();
        maintenanceExecutor.shutdown();
    }

    @Override
    public AsyncClusterResponse replicate(Set<NodeIdentifier> nodeIds, String method, URI uri, Object entity, Map<String, String> headers) {
        return replicate(nodeIds, method, uri, entity, headers, true, null);
    }

    /**
     * Replicates the request to all nodes in the given set of node identifiers
     *
     * @param nodeIds the NodeIdentifiers that identify which nodes to send the request to
     * @param method the HTTP method to use
     * @param uri the URI to send the request to
     * @param entity the entity to use
     * @param headers the HTTP Headers
     * @param performVerification whether or not to use 2-phase commit to verify that all nodes can handle the request. Ignored if request is not mutable.
     * @param response the response to update with the results
     *
     * @return an AsyncClusterResponse that can be used to obtain the result
     */
    private AsyncClusterResponse replicate(Set<NodeIdentifier> nodeIds, String method, URI uri, Object entity, Map<String, String> headers, boolean performVerification,
        StandardAsyncClusterResponse response) {

        // state validation
        Objects.requireNonNull(nodeIds);
        Objects.requireNonNull(method);
        Objects.requireNonNull(uri);
        Objects.requireNonNull(entity);
        Objects.requireNonNull(headers);

        if (nodeIds.isEmpty()) {
            throw new IllegalArgumentException("Cannot replicate request to 0 nodes");
        }

        logger.debug("Replicating request {} {} with entity {} to {}; response is {}", method, uri, entity, nodeIds, response);

        // Update headers to indicate the current revision so that we can
        // prevent multiple users changing the flow at the same time
        final Map<String, String> updatedHeaders = new HashMap<>(headers);
        final String requestId = updatedHeaders.computeIfAbsent(REQUEST_TRANSACTION_ID, key -> UUID.randomUUID().toString());

        if (performVerification) {
            verifyState(method, uri.getPath());
        }

        final int numRequests = responseMap.size();
        if (numRequests >= MAX_CONCURRENT_REQUESTS) {
            logger.debug("Cannot replicate request because there are {} outstanding HTTP Requests already", numRequests);
            throw new IllegalStateException("There are too many outstanding HTTP requests with a total " + numRequests + " outstanding requests");
        }

        // create the request objects and replicate to all nodes
        final CompletionCallback completionCallback = clusterResponse -> onCompletedResponse(requestId);
        final Runnable responseConsumedCallback = () -> onResponseConsumed(requestId);

        // create a response object if one was not already passed to us
        if (response == null) {
            response = new StandardAsyncClusterResponse(requestId, uri, method, nodeIds,
                responseMerger, completionCallback, responseConsumedCallback);
            responseMap.put(requestId, response);
        }

        logger.debug("For Request ID {}, response object is {}", requestId, response);
        // setRevision(updatedHeaders);

        // if mutable request, we have to do a two-phase commit where we ask each node to verify
        // that the request can take place and then, if all nodes agree that it can, we can actually
        // issue the request. This is all handled by calling performVerification, which will replicate
        // the 'vote' request to all nodes and then if successful will call back into this method to
        // replicate the actual request.
        final boolean mutableRequest = isMutableRequest(method, uri.getPath());
        if (mutableRequest && performVerification) {
            logger.debug("Performing verification (first phase of two-phase commit) for Request ID {}", requestId);
            performVerification(nodeIds, method, uri, entity, updatedHeaders, response);
            return response;
        }

        // Callback function for generating a NodeHttpRequestCallable that can be used to perform the work
        final StandardAsyncClusterResponse finalResponse = response;
        NodeRequestCompletionCallback nodeCompletionCallback = nodeResponse -> {
            logger.debug("Received response from {} for {} {}", nodeResponse.getNodeId(), method, uri.getPath());
            finalResponse.add(nodeResponse);
        };


        // replicate the request to all nodes
        final Function<NodeIdentifier, NodeHttpRequest> requestFactory =
            nodeId -> new NodeHttpRequest(nodeId, method, createURI(uri, nodeId), entity, updatedHeaders, nodeCompletionCallback);
        replicateRequest(nodeIds, uri.getScheme(), uri.getPath(), requestFactory, updatedHeaders);


        // TODO: Must handle revisions!!

        return response;
    }


    private void setRevision(final Map<String, String> headers) {
        final ClusterContext clusterCtx = new ClusterContextImpl();
        clusterCtx.setRequestSentByClusterManager(true); // indicate request is sent from cluster manager
        clusterCtx.setRevision(lockingManager.getLastModification().getRevision());

        // serialize cluster context and add to request header
        final String serializedClusterCtx = WebUtils.serializeObjectToHex(clusterCtx);
        headers.put(CLUSTER_CONTEXT_HTTP_HEADER, serializedClusterCtx);
    }


    private void performVerification(Set<NodeIdentifier> nodeIds, String method, URI uri, Object entity, Map<String, String> headers, StandardAsyncClusterResponse clusterResponse) {
        logger.debug("Verifying that mutable request {} {} can be made", method, uri.getPath());

        final Map<String, String> updatedHeaders = new HashMap<>(headers);
        updatedHeaders.put(NCM_EXPECTS_HTTP_HEADER, NODE_CONTINUE);

        final int numNodes = nodeIds.size();
        final NodeRequestCompletionCallback completionCallback = new NodeRequestCompletionCallback() {
            final Set<NodeResponse> nodeResponses = Collections.synchronizedSet(new HashSet<>());

            @Override
            public void onCompletion(final NodeResponse nodeResponse) {
                // Add the node response to our collection. We later need to know whether or
                // not this is the last node response, so we add the response and then check
                // the size within a synchronized block to ensure that those two things happen
                // atomically. Otherwise, we could have multiple threads checking the sizes of
                // the sets at the same time, which could result in multiple threads performing
                // the 'all nodes are complete' logic.
                final boolean allNodesResponded;
                synchronized (nodeResponses) {
                    nodeResponses.add(nodeResponse);
                    allNodesResponded = nodeResponses.size() == numNodes;
                }

                try {
                    // If we have all of the node responses, then we can verify the responses
                    // and if good replicate the original request to all of the nodes.
                    if (allNodesResponded) {
                        // Check if we have any requests that do not have a 150-Continue status code.
                        final long dissentingCount = nodeResponses.stream().filter(p -> p.getStatus() != NODE_CONTINUE_STATUS_CODE).count();

                        // If all nodes responded with 150-Continue, then we can replicate the original request
                        // to all nodes and we are finished.
                        if (dissentingCount == 0) {
                            logger.debug("Received verification from all {} nodes that mutable request {} {} can be made", numNodes, method, uri.getPath());
                            replicate(nodeIds, method, uri, entity, headers, false, clusterResponse);
                            return;
                        }

                        // Add a NodeResponse for each node to the Cluster Response
                        // Check that all nodes responded successfully.
                        for (final NodeResponse response : nodeResponses) {
                            if (response.getStatus() != NODE_CONTINUE_STATUS_CODE) {
                                final ClientResponse clientResponse = response.getClientResponse();

                                final RuntimeException failure;
                                if (clientResponse == null) {
                                    failure = new IllegalClusterStateException("Node " + response.getNodeId()
                                        + " is unable to fulfill this request due to: Unexpected Response Code " + response.getStatus());

                                    logger.info("Received a status of {} from {} for request {} {} when performing first stage of two-stage commit. "
                                        + "Will respond with CONFLICT response and action will not occur",
                                        response.getStatus(), response.getNodeId(), method, uri.getPath());
                                } else {
                                    final String nodeExplanation = clientResponse.getEntity(String.class);
                                    failure = new IllegalClusterStateException("Node " + response.getNodeId() + " is unable to fulfill this request due to: "
                                        + nodeExplanation, response.getThrowable());

                                    logger.info("Received a status of {} from {} for request {} {} when performing first stage of two-stage commit. "
                                        + "Will respond with CONFLICT response and action will not occur. Node explanation: {}",
                                        response.getStatus(), response.getNodeId(), method, uri.getPath(), nodeExplanation);
                                }

                                clusterResponse.setFailure(failure);
                                break;
                            }
                        }
                    }
                } catch (final Exception e) {
                    clusterResponse.add(new NodeResponse(nodeResponse.getNodeId(), method, uri, e));

                    // If there was a problem, we need to ensure that we add all of the other nodes' responses
                    // to the Cluster Response so that the Cluster Response is complete.
                    for (final NodeResponse otherResponse : nodeResponses) {
                        if (otherResponse.getNodeId().equals(nodeResponse.getNodeId())) {
                            continue;
                        }

                        clusterResponse.add(otherResponse);
                    }
                }
            }
        };

        // notify dataflow management service that flow state is not known
        dfmService.setPersistedFlowState(PersistedFlowState.UNKNOWN);

        // Callback function for generating a NodeHttpRequestCallable that can be used to perform the work
        final Function<NodeIdentifier, NodeHttpRequest> requestFactory = nodeId -> new NodeHttpRequest(nodeId, method, createURI(uri, nodeId), entity, updatedHeaders, completionCallback);

        // replicate the 'verification request' to all nodes
        replicateRequest(nodeIds, uri.getScheme(), uri.getPath(), requestFactory, updatedHeaders);
    }


    @Override
    public AsyncClusterResponse getClusterResponse(final String identifier) {
        final AsyncClusterResponse response = responseMap.get(identifier);
        if (response == null) {
            return null;
        }

        return response;
    }

    // Visible for testing - overriding this method makes it easy to verify behavior without actually making any web requests
    protected NodeResponse replicateRequest(final WebResource.Builder resourceBuilder, final NodeIdentifier nodeId, final String method, final URI uri, final String requestId) {
        final ClientResponse clientResponse;
        final long startNanos = System.nanoTime();

        switch (method.toUpperCase()) {
            case HttpMethod.DELETE:
                clientResponse = resourceBuilder.delete(ClientResponse.class);
                break;
            case HttpMethod.GET:
                clientResponse = resourceBuilder.get(ClientResponse.class);
                break;
            case HttpMethod.HEAD:
                clientResponse = resourceBuilder.head();
                break;
            case HttpMethod.OPTIONS:
                clientResponse = resourceBuilder.options(ClientResponse.class);
                break;
            case HttpMethod.POST:
                clientResponse = resourceBuilder.post(ClientResponse.class);
                break;
            case HttpMethod.PUT:
                clientResponse = resourceBuilder.put(ClientResponse.class);
                break;
            default:
                throw new IllegalArgumentException("HTTP Method '" + method + "' not supported for request replication.");
        }

        return new NodeResponse(nodeId, method, uri, clientResponse, System.nanoTime() - startNanos, requestId);
    }

    private boolean isMutableRequest(final String method, final String uriPath) {
        switch (method.toUpperCase()) {
            case HttpMethod.GET:
            case HttpMethod.HEAD:
            case HttpMethod.OPTIONS:
                return false;
            default:
                return true;
        }
    }

    /**
     * Verifies that the cluster is in a state that will allow requests to be made using the given HTTP Method and URI path
     *
     * @param httpMethod the HTTP Method
     * @param uriPath the URI Path
     *
     * @throw IllegalClusterStateException if the cluster is not in a state that allows a request to made to the given URI Path using the given HTTP Method
     */
    private void verifyState(final String httpMethod, final String uriPath) {
        final boolean mutableRequest = HttpMethod.DELETE.equals(httpMethod) || HttpMethod.POST.equals(httpMethod) || HttpMethod.PUT.equals(httpMethod);

        // check that the request can be applied
        if (mutableRequest) {
            final Map<NodeConnectionState, List<NodeIdentifier>> connectionStates = clusterCoordinator.getConnectionStates();
            if (connectionStates.containsKey(NodeConnectionState.DISCONNECTED) || connectionStates.containsKey(NodeConnectionState.DISCONNECTING)) {
                throw new DisconnectedNodeMutableRequestException("Received a mutable request [" + httpMethod + " " + uriPath + "] while a node is disconnected from the cluster");
            }

            if (connectionStates.containsKey(NodeConnectionState.CONNECTING)) {
                // if any node is connecting and a request can change the flow, then we throw an exception
                throw new ConnectingNodeMutableRequestException("Received a mutable request [" + httpMethod + " " + uriPath + "] while a node is trying to connect to the cluster");
            }
        }
    }

    private void onResponseConsumed(final String requestId) {
        final AsyncClusterResponse response = responseMap.remove(requestId);

        if (response != null && logger.isDebugEnabled()) {
            logTimingInfo(response);
        }
    }

    private void onCompletedResponse(final String requestId) {
        final AsyncClusterResponse response = responseMap.get(requestId);

        if (response != null) {
            if (isMutableRequest(response.getMethod(), response.getURIPath())) {
                dfmService.setPersistedFlowState(PersistedFlowState.STALE);
            }
        }

        if (response != null && callback != null) {
            try {
                callback.afterRequest(response.getURIPath(), response.getMethod(), response.getCompletedNodeResponses());
            } catch (final Exception e) {
                logger.warn("Completed request {} {} but failed to properly handle the Request Completion Callback due to {}",
                    response.getMethod(), response.getURIPath(), e.toString());
                logger.warn("", e);
            }
        }

        // If we have any nodes that are slow to respond, keep track of this. If the same node is slow 3 times in
        // a row, log a warning to indicate that the node is responding slowly.
        final Set<NodeIdentifier> slowResponseNodes = ResponseUtils.findLongResponseTimes(response, 1.5D);
        for (final NodeIdentifier nodeId : response.getNodesInvolved()) {
            final AtomicInteger counter = sequentialLongRequestCounts.computeIfAbsent(nodeId, id -> new AtomicInteger(0));
            if (slowResponseNodes.contains(nodeId)) {
                final int sequentialLongRequests = counter.incrementAndGet();
                if (sequentialLongRequests >= 3) {
                    final String message = "Response time from " + nodeId + " was slow for each of the last 3 requests made. "
                        + "To see more information about timing, enable DEBUG logging for " + logger.getName();

                    logger.warn(message);
                    if (eventReporter != null) {
                        eventReporter.reportEvent(Severity.WARNING, "Node Response Time", message);
                    }

                    counter.set(0);
                }
            } else {
                counter.set(0);
            }
        }
    }

    private void logTimingInfo(final AsyncClusterResponse response) {
        // Calculate min, max, mean for the requests
        final LongSummaryStatistics stats = response.getNodesInvolved().stream()
            .map(p -> response.getNodeResponse(p).getRequestDuration(TimeUnit.MILLISECONDS))
            .collect(Collectors.summarizingLong(Long::longValue));

        final StringBuilder sb = new StringBuilder();
        sb.append("Node Responses for ").append(response.getMethod()).append(" ").append(response.getURIPath()).append(" (Request ID ").append(response.getRequestIdentifier()).append("):\n");
        for (final NodeIdentifier node : response.getNodesInvolved()) {
            sb.append(node).append(": ").append(response.getNodeResponse(node).getRequestDuration(TimeUnit.MILLISECONDS)).append(" millis\n");
        }

        logger.debug("For {} {} (Request ID {}), minimum response time = {}, max = {}, average = {} ms",
            response.getMethod(), response.getURIPath(), response.getRequestIdentifier(), stats.getMin(), stats.getMax(), stats.getAverage());
        logger.debug(sb.toString());
    }


    private void replicateRequest(final Set<NodeIdentifier> nodeIds, final String scheme,
        final String path, final Function<NodeIdentifier, NodeHttpRequest> callableFactory, final Map<String, String> headers) {

        if (nodeIds.isEmpty()) {
            return; // return quickly for trivial case
        }

        // submit the requests to the nodes
        final String requestId = UUID.randomUUID().toString();
        headers.put(WebClusterManager.REQUEST_ID_HEADER, requestId);
        for (final NodeIdentifier nodeId : nodeIds) {
            final NodeHttpRequest callable = callableFactory.apply(nodeId);
            executorService.submit(callable);
        }
    }


    private URI createURI(final URI exampleUri, final NodeIdentifier nodeId) {
        return createURI(exampleUri.getScheme(), nodeId.getApiAddress(), nodeId.getApiPort(), exampleUri.getPath());
    }

    private URI createURI(final String scheme, final String nodeApiAddress, final int nodeApiPort, final String path) {
        try {
            return new URI(scheme, null, nodeApiAddress, nodeApiPort, path, null, null);
        } catch (final URISyntaxException e) {
            throw new UriConstructionException(e);
        }
    }


    /**
     * A Callable for making an HTTP request to a single node and returning its response.
     */
    private class NodeHttpRequest implements Runnable {
        private final NodeIdentifier nodeId;
        private final String method;
        private final URI uri;
        private final Object entity;
        private final Map<String, String> headers = new HashMap<>();
        private final NodeRequestCompletionCallback callback;

        private NodeHttpRequest(final NodeIdentifier nodeId, final String method,
            final URI uri, final Object entity, final Map<String, String> headers, final NodeRequestCompletionCallback callback) {
            this.nodeId = nodeId;
            this.method = method;
            this.uri = uri;
            this.entity = entity;
            this.headers.putAll(headers);
            this.callback = callback;
        }


        @Override
        public void run() {
            NodeResponse nodeResponse;

            try {
                // create and send the request
                final WebResource.Builder resourceBuilder = createResourceBuilder();
                final String requestId = headers.get("x-nifi-request-id");

                logger.debug("Replicating request {} {} to {}", method, uri.getPath(), nodeId);
                nodeResponse = replicateRequest(resourceBuilder, nodeId, method, uri, requestId);
            } catch (final Exception e) {
                nodeResponse = new NodeResponse(nodeId, method, uri, e);
                logger.warn("Failed to replicate request {} {} to {} due to {}", method, uri.getPath(), nodeId, e);
                logger.warn("", e);
            }

            if (callback != null) {
                logger.debug("Request {} {} completed for {}", method, uri.getPath(), nodeId);
                callback.onCompletion(nodeResponse);
            }
        }


        @SuppressWarnings({"rawtypes", "unchecked"})
        private WebResource.Builder createResourceBuilder() {
            // convert parameters to a more convenient data structure
            final MultivaluedMap<String, String> map = new MultivaluedMapImpl();

            if (entity instanceof MultivaluedMap) {
                map.putAll((Map) entity);
            }

            // create the resource
            WebResource resource = client.resource(uri);

            if (responseMerger.isResponseInterpreted(uri, method)) {
                resource.addFilter(new GZIPContentEncodingFilter(false));
            }

            // set the parameters as either query parameters or as request body
            final WebResource.Builder builder;
            if (HttpMethod.DELETE.equalsIgnoreCase(method) || HttpMethod.HEAD.equalsIgnoreCase(method) || HttpMethod.GET.equalsIgnoreCase(method) || HttpMethod.OPTIONS.equalsIgnoreCase(method)) {
                resource = resource.queryParams(map);
                builder = resource.getRequestBuilder();
            } else {
                if (entity == null) {
                    builder = resource.entity(map);
                } else {
                    builder = resource.entity(entity);
                }
            }

            // set headers
            boolean foundContentType = false;
            for (final Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
                if (entry.getKey().equalsIgnoreCase("content-type")) {
                    foundContentType = true;
                }
            }

            // set default content type
            if (!foundContentType) {
                // set default content type
                builder.type(MediaType.APPLICATION_FORM_URLENCODED);
            }

            return builder;
        }
    }

    private static interface NodeRequestCompletionCallback {
        void onCompletion(NodeResponse nodeResponse);
    }

    private class PurgeExpiredRequestsTask implements Runnable {
        @Override
        public void run() {
            final Set<String> expiredRequestIds = ThreadPoolRequestReplicator.this.responseMap.entrySet().stream()
                .filter(entry -> entry.getValue().isOlderThan(30, TimeUnit.SECONDS)) // older than 30 seconds
                .filter(entry -> entry.getValue().isComplete()) // is complete
                .map(entry -> entry.getKey()) // get the request id
                .collect(Collectors.toSet());

            expiredRequestIds.forEach(id -> onResponseConsumed(id));
        }
    }
}
