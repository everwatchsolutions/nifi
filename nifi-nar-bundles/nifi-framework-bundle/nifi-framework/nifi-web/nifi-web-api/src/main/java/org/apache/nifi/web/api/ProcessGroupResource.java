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
package org.apache.nifi.web.api;

import com.sun.jersey.api.core.ResourceContext;
import com.sun.jersey.multipart.FormDataParam;
import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import com.wordnik.swagger.annotations.ApiParam;
import com.wordnik.swagger.annotations.ApiResponse;
import com.wordnik.swagger.annotations.ApiResponses;
import com.wordnik.swagger.annotations.Authorization;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.cluster.context.ClusterContext;
import org.apache.nifi.cluster.context.ClusterContextThreadLocal;
import org.apache.nifi.cluster.manager.impl.WebClusterManager;
import org.apache.nifi.util.NiFiProperties;
import org.apache.nifi.web.NiFiServiceFacade;
import org.apache.nifi.web.Revision;
import org.apache.nifi.web.UpdateResult;
import org.apache.nifi.web.api.dto.ConnectionDTO;
import org.apache.nifi.web.api.dto.ProcessGroupDTO;
import org.apache.nifi.web.api.dto.RemoteProcessGroupDTO;
import org.apache.nifi.web.api.dto.SnippetDTO;
import org.apache.nifi.web.api.dto.TemplateDTO;
import org.apache.nifi.web.api.dto.flow.FlowDTO;
import org.apache.nifi.web.api.entity.ConnectionEntity;
import org.apache.nifi.web.api.entity.ConnectionsEntity;
import org.apache.nifi.web.api.entity.ControllerServiceEntity;
import org.apache.nifi.web.api.entity.CopySnippetRequestEntity;
import org.apache.nifi.web.api.entity.CreateTemplateRequestEntity;
import org.apache.nifi.web.api.entity.FlowEntity;
import org.apache.nifi.web.api.entity.FlowSnippetEntity;
import org.apache.nifi.web.api.entity.FunnelEntity;
import org.apache.nifi.web.api.entity.FunnelsEntity;
import org.apache.nifi.web.api.entity.InputPortsEntity;
import org.apache.nifi.web.api.entity.InstantiateTemplateRequestEntity;
import org.apache.nifi.web.api.entity.LabelEntity;
import org.apache.nifi.web.api.entity.LabelsEntity;
import org.apache.nifi.web.api.entity.OutputPortsEntity;
import org.apache.nifi.web.api.entity.PortEntity;
import org.apache.nifi.web.api.entity.ProcessGroupEntity;
import org.apache.nifi.web.api.entity.ProcessGroupsEntity;
import org.apache.nifi.web.api.entity.ProcessorEntity;
import org.apache.nifi.web.api.entity.ProcessorsEntity;
import org.apache.nifi.web.api.entity.RemoteProcessGroupEntity;
import org.apache.nifi.web.api.entity.RemoteProcessGroupsEntity;
import org.apache.nifi.web.api.entity.SnippetEntity;
import org.apache.nifi.web.api.entity.TemplateEntity;
import org.apache.nifi.web.api.entity.TemplatesEntity;
import org.apache.nifi.web.api.request.ClientIdParameter;
import org.apache.nifi.web.api.request.LongParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * RESTful endpoint for managing a Group.
 */
@Path("/process-groups")
@Api(
    value = "/process-groups",
    description = "Endpoint for managing a Process Group."
)
public class ProcessGroupResource extends ApplicationResource {

    private static final Logger logger = LoggerFactory.getLogger(ProcessGroupResource.class);

    private static final String VERBOSE = "false";
    private static final String RECURSIVE = "false";

    @Context
    private ResourceContext resourceContext;

    private NiFiServiceFacade serviceFacade;
    private WebClusterManager clusterManager;
    private NiFiProperties properties;

    private ProcessorResource processorResource;
    private InputPortResource inputPortResource;
    private OutputPortResource outputPortResource;
    private FunnelResource funnelResource;
    private LabelResource labelResource;
    private RemoteProcessGroupResource remoteProcessGroupResource;
    private ConnectionResource connectionResource;
    private TemplateResource templateResource;
    private ControllerServiceResource controllerServiceResource;

    /**
     * Populates the remaining fields in the specified process groups.
     *
     * @param processGroupEntities groups
     * @return group dto
     */
    public Set<ProcessGroupEntity> populateRemainingProcessGroupEntitiesContent(Set<ProcessGroupEntity> processGroupEntities) {
        for (ProcessGroupEntity processGroupEntity : processGroupEntities) {
            populateRemainingProcessGroupEntityContent(processGroupEntity);
        }
        return processGroupEntities;
    }

    /**
     * Populates the remaining fields in the specified process group.
     *
     * @param processGroupEntity group
     * @return group dto
     */
    public ProcessGroupEntity populateRemainingProcessGroupEntityContent(ProcessGroupEntity processGroupEntity) {
        if (processGroupEntity.getComponent() != null) {
            populateRemainingProcessGroupContent(processGroupEntity.getComponent());
        }
        return processGroupEntity;
    }

    /**
     * Populates the remaining fields in the specified process groups.
     *
     * @param processGroups groups
     * @return group dto
     */
    public Set<ProcessGroupDTO> populateRemainingProcessGroupsContent(Set<ProcessGroupDTO> processGroups) {
        for (ProcessGroupDTO processGroup : processGroups) {
            populateRemainingProcessGroupContent(processGroup);
        }
        return processGroups;
    }

    /**
     * Populates the remaining fields in the specified process group.
     *
     * @param processGroup group
     * @return group dto
     */
    private ProcessGroupDTO populateRemainingProcessGroupContent(ProcessGroupDTO processGroup) {
        processGroup.setUri(generateResourceUri("process-groups",  processGroup.getId()));
        return processGroup;
    }

    /**
     * Populates the remaining content of the specified snippet.
     */
    private FlowDTO populateRemainingSnippetContent(FlowDTO flow) {
        processorResource.populateRemainingProcessorEntitiesContent(flow.getProcessors());
        connectionResource.populateRemainingConnectionEntitiesContent(flow.getConnections());
        inputPortResource.populateRemainingInputPortEntitiesContent(flow.getInputPorts());
        outputPortResource.populateRemainingOutputPortEntitiesContent(flow.getOutputPorts());
        remoteProcessGroupResource.populateRemainingRemoteProcessGroupEntitiesContent(flow.getRemoteProcessGroups());
        funnelResource.populateRemainingFunnelEntitiesContent(flow.getFunnels());
        labelResource.populateRemainingLabelEntitiesContent(flow.getLabels());

        // go through each process group child and populate its uri
        if (flow.getProcessGroups() != null) {
            populateRemainingProcessGroupEntitiesContent(flow.getProcessGroups());
        }

        return flow;
    }

    /**
     * Populate the uri's for the specified snippet.
     *
     * @param entity processors
     * @return dtos
     */
    private SnippetEntity populateRemainingSnippetEntityContent(SnippetEntity entity) {
        if (entity.getSnippet() != null) {
            populateRemainingSnippetContent(entity.getSnippet());
        }
        return entity;
    }

    /**
     * Populates the uri for the specified snippet.
     */
    private SnippetDTO populateRemainingSnippetContent(SnippetDTO snippet) {
        String snippetGroupId = snippet.getParentGroupId();

        // populate the snippet href
        snippet.setUri(generateResourceUri("process-groups", snippetGroupId, "snippets", snippet.getId()));

        return snippet;
    }

    /**
     * Retrieves the contents of the specified group.
     *
     * @param groupId The id of the process group.
     * @return A processGroupEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    // TODO - @PreAuthorize("hasAnyRole('ROLE_MONITOR', 'ROLE_DFM', 'ROLE_ADMIN')")
    @ApiOperation(
            value = "Gets a process group",
            response = ProcessGroupEntity.class,
            authorizations = {
                @Authorization(value = "Read Only", type = "ROLE_MONITOR"),
                @Authorization(value = "Data Flow Manager", type = "ROLE_DFM"),
                @Authorization(value = "Administrator", type = "ROLE_ADMIN")
            }
    )
    @ApiResponses(
            value = {
                @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                @ApiResponse(code = 401, message = "Client could not be authenticated."),
                @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                @ApiResponse(code = 404, message = "The specified resource could not be found."),
                @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response getProcessGroup(
            @ApiParam(
                    value = "The process group id.",
                    required = false
            )
            @PathParam("id") String groupId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // get this process group contents
        final ProcessGroupEntity entity = serviceFacade.getProcessGroup(groupId);
        populateRemainingProcessGroupEntityContent(entity);

        if (entity.getComponent() != null) {
            entity.getComponent().setContents(null);
        }


        return clusterContext(generateOkResponse(entity)).build();
    }

    /**
     * Updates the specified process group.
     *
     * @param httpServletRequest request
     * @param id The id of the process group.
     * @param processGroupEntity A processGroupEntity.
     * @return A processGroupEntity.
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
            value = "Updates a process group",
            response = ProcessGroupEntity.class,
            authorizations = {
                @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
            }
    )
    @ApiResponses(
            value = {
                @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                @ApiResponse(code = 401, message = "Client could not be authenticated."),
                @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                @ApiResponse(code = 404, message = "The specified resource could not be found."),
                @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response updateProcessGroup(
            @Context HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") String id,
            @ApiParam(
                    value = "The process group configuration details.",
                    required = true
            )
            ProcessGroupEntity processGroupEntity) {

        if (processGroupEntity == null || processGroupEntity.getComponent() == null) {
            throw new IllegalArgumentException("Process group details must be specified.");
        }

        if (processGroupEntity.getRevision() == null) {
            throw new IllegalArgumentException("Revision must be specified.");
        }

        // ensure the same id is being used
        final ProcessGroupDTO requestProcessGroupDTO = processGroupEntity.getComponent();
        if (!id.equals(requestProcessGroupDTO.getId())) {
            throw new IllegalArgumentException(String.format("The process group id (%s) in the request body does "
                    + "not equal the process group id of the requested resource (%s).", requestProcessGroupDTO.getId(), id));
        }

        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.PUT, getAbsolutePath(), processGroupEntity, getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final Revision revision = getRevision(processGroupEntity, id);
        final boolean validationPhase = isValidationPhase(httpServletRequest);
        if (validationPhase || !isTwoPhaseRequest(httpServletRequest)) {
            serviceFacade.claimRevision(revision);
        }
        if (validationPhase) {
            serviceFacade.verifyUpdateProcessGroup(requestProcessGroupDTO);
            return generateContinueResponse().build();
        }

        // update the process group
        final UpdateResult<ProcessGroupEntity> updateResult = serviceFacade.updateProcessGroup(revision, requestProcessGroupDTO);
        final ProcessGroupEntity entity = updateResult.getResult();
        populateRemainingProcessGroupEntityContent(entity);

        if (updateResult.isNew()) {
            return clusterContext(generateCreatedResponse(URI.create(entity.getComponent().getUri()), entity)).build();
        } else {
            return clusterContext(generateOkResponse(entity)).build();
        }
    }

    /**
     * Removes the specified process group reference.
     *
     * @param httpServletRequest request
     * @param version The revision is used to verify the client is working with the latest version of the flow.
     * @param clientId Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @param id The id of the process group to be removed.
     * @return A processGroupEntity.
     */
    @DELETE
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
            value = "Deletes a process group",
            response = ProcessGroupEntity.class,
            authorizations = {
                @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
            }
    )
    @ApiResponses(
            value = {
                @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                @ApiResponse(code = 401, message = "Client could not be authenticated."),
                @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                @ApiResponse(code = 404, message = "The specified resource could not be found."),
                @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response removeProcessGroupReference(
            @Context HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The revision is used to verify the client is working with the latest version of the flow.",
                    required = false
            )
            @QueryParam(VERSION) LongParameter version,
            @ApiParam(
                    value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
                    required = false
            )
            @QueryParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
            @ApiParam(
                    value = "The process group id.",
                    required = true
            )
            @PathParam("id") String id) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.DELETE, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final Revision revision = new Revision(version == null ? null : version.getLong(), clientId.getClientId(), id);
        final boolean validationPhase = isValidationPhase(httpServletRequest);
        if (validationPhase || !isTwoPhaseRequest(httpServletRequest)) {
            serviceFacade.claimRevision(revision);
        }
        if (validationPhase) {
            serviceFacade.verifyDeleteProcessGroup(id);
            return generateContinueResponse().build();
        }

        // delete the process group
        final ProcessGroupEntity entity = serviceFacade.deleteProcessGroup(revision, id);

        // create the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    /**
     * Adds the specified process group.
     *
     * @param httpServletRequest request
     * @param groupId The group id
     * @param processGroupEntity A processGroupEntity
     * @return A processGroupEntity
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/process-groups")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Creates a process group",
        response = ProcessGroupEntity.class,
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response createProcessGroup(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "The process group id.",
            required = false
        )
        @PathParam("id") String groupId,
        @ApiParam(
            value = "The process group configuration details.",
            required = true
        )
            ProcessGroupEntity processGroupEntity) {

        if (processGroupEntity == null || processGroupEntity.getComponent() == null) {
            throw new IllegalArgumentException("Process group details must be specified.");
        }

        if (processGroupEntity.getComponent().getId() != null) {
            throw new IllegalArgumentException("Process group ID cannot be specified.");
        }

        if (processGroupEntity.getComponent().getParentGroupId() != null && !groupId.equals(processGroupEntity.getComponent().getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                processGroupEntity.getComponent().getParentGroupId(), groupId));
        }
        processGroupEntity.getComponent().setParentGroupId(groupId);

        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.POST, getAbsolutePath(), processGroupEntity, getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            return generateContinueResponse().build();
        }

        // set the processor id as appropriate
        final ClusterContext clusterContext = ClusterContextThreadLocal.getContext();
        if (clusterContext != null) {
            processGroupEntity.getComponent().setId(UUID.nameUUIDFromBytes(clusterContext.getIdGenerationSeed().getBytes(StandardCharsets.UTF_8)).toString());
        } else {
            processGroupEntity.getComponent().setId(UUID.randomUUID().toString());
        }

        // create the process group contents
        final ProcessGroupEntity entity = serviceFacade.createProcessGroup(groupId, processGroupEntity.getComponent());
        populateRemainingProcessGroupEntityContent(entity);

        // generate a 201 created response
        String uri = entity.getComponent().getUri();
        return clusterContext(generateCreatedResponse(URI.create(uri), entity)).build();
    }

    /**
     * Retrieves all the processors in this NiFi.
     *
     * @return A processorsEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/process-groups")
    // TODO - @PreAuthorize("hasAnyRole('ROLE_MONITOR', 'ROLE_DFM', 'ROLE_ADMIN')")
    @ApiOperation(
        value = "Gets all process groups",
        response = ProcessorsEntity.class,
        authorizations = {
            @Authorization(value = "Read Only", type = "ROLE_MONITOR"),
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM"),
            @Authorization(value = "Administrator", type = "ROLE_ADMIN")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response getProcessGroups(
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // get the process groups
        final Set<ProcessGroupEntity> entities = serviceFacade.getProcessGroups(groupId);

        // always prune the contents
        for (final ProcessGroupEntity entity : entities) {
            if (entity.getComponent() != null) {
                entity.getComponent().setContents(null);
            }
        }

        // create the response entity
        final ProcessGroupsEntity entity = new ProcessGroupsEntity();
        entity.setProcessGroups(populateRemainingProcessGroupEntitiesContent(entities));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    // ----------
    // processors
    // ----------

    /**
     * Creates a new processor.
     *
     * @param httpServletRequest request
     * @param groupId The group id
     * @param processorEntity A processorEntity.
     * @return A processorEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/processors")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Creates a new processor",
        response = ProcessorEntity.class,
        authorizations = {
            @Authorization(value = "ROLE_DFM", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response createProcessor(
            @Context HttpServletRequest httpServletRequest,
            @ApiParam(
                value = "The process group id.",
                required = true
            )
            @PathParam("id") String groupId,
            @ApiParam(
                value = "The processor configuration details.",
                required = true
            )
            ProcessorEntity processorEntity) {

        if (processorEntity == null || processorEntity.getComponent() == null) {
            throw new IllegalArgumentException("Processor details must be specified.");
        }

        if (processorEntity.getComponent().getId() != null) {
            throw new IllegalArgumentException("Processor ID cannot be specified.");
        }

        if (StringUtils.isBlank(processorEntity.getComponent().getType())) {
            throw new IllegalArgumentException("The type of processor to create must be specified.");
        }

        if (processorEntity.getComponent().getParentGroupId() != null && !groupId.equals(processorEntity.getComponent().getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                processorEntity.getComponent().getParentGroupId(), groupId));
        }
        processorEntity.getComponent().setParentGroupId(groupId);

        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.POST, getAbsolutePath(), processorEntity, getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            return generateContinueResponse().build();
        }

        // set the processor id as appropriate
        final ClusterContext clusterContext = ClusterContextThreadLocal.getContext();
        if (clusterContext != null) {
            processorEntity.getComponent().setId(UUID.nameUUIDFromBytes(clusterContext.getIdGenerationSeed().getBytes(StandardCharsets.UTF_8)).toString());
        } else {
            processorEntity.getComponent().setId(UUID.randomUUID().toString());
        }

        // create the new processor
        final ProcessorEntity entity = serviceFacade.createProcessor(groupId, processorEntity.getComponent());
        processorResource.populateRemainingProcessorEntityContent(entity);

        // generate a 201 created response
        String uri = entity.getComponent().getUri();
        return clusterContext(generateCreatedResponse(URI.create(uri), entity)).build();
    }

    /**
     * Retrieves all the processors in this NiFi.
     *
     * @param groupId group id
     * @return A processorsEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/processors")
    // TODO - @PreAuthorize("hasAnyRole('ROLE_MONITOR', 'ROLE_DFM', 'ROLE_ADMIN')")
    @ApiOperation(
        value = "Gets all processors",
        response = ProcessorsEntity.class,
        authorizations = {
            @Authorization(value = "Read Only", type = "ROLE_MONITOR"),
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM"),
            @Authorization(value = "Administrator", type = "ROLE_ADMIN")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response getProcessors(
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // get the processors
        final Set<ProcessorEntity> processors = serviceFacade.getProcessors(groupId);

        // create the response entity
        final ProcessorsEntity entity = new ProcessorsEntity();
        entity.setProcessors(processorResource.populateRemainingProcessorEntitiesContent(processors));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    // -----------
    // input ports
    // -----------

    /**
     * Creates a new input port.
     *
     * @param httpServletRequest request
     * @param groupId The group id
     * @param portEntity A inputPortEntity.
     * @return A inputPortEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/input-ports")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Creates an input port",
        response = PortEntity.class,
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response createInputPort(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId,
        @ApiParam(
            value = "The input port configuration details.",
            required = true
        ) PortEntity portEntity) {

        if (portEntity == null || portEntity.getComponent() == null) {
            throw new IllegalArgumentException("Port details must be specified.");
        }

        if (portEntity.getComponent().getId() != null) {
            throw new IllegalArgumentException("Input port ID cannot be specified.");
        }

        if (portEntity.getComponent().getParentGroupId() != null && !groupId.equals(portEntity.getComponent().getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                portEntity.getComponent().getParentGroupId(), groupId));
        }
        portEntity.getComponent().setParentGroupId(groupId);

        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.POST, getAbsolutePath(), portEntity, getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            return generateContinueResponse().build();
        }

        // set the processor id as appropriate
        final ClusterContext clusterContext = ClusterContextThreadLocal.getContext();
        if (clusterContext != null) {
            portEntity.getComponent().setId(UUID.nameUUIDFromBytes(clusterContext.getIdGenerationSeed().getBytes(StandardCharsets.UTF_8)).toString());
        } else {
            portEntity.getComponent().setId(UUID.randomUUID().toString());
        }

        // create the input port and generate the json
        final PortEntity entity = serviceFacade.createInputPort(groupId, portEntity.getComponent());
        inputPortResource.populateRemainingInputPortEntityContent(entity);

        // build the response
        return clusterContext(generateCreatedResponse(URI.create(entity.getComponent().getUri()), entity)).build();
    }

    /**
     * Retrieves all the of input ports in this NiFi.
     *
     * @return A inputPortsEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/input-ports")
    // TODO - @PreAuthorize("hasAnyRole('ROLE_MONITOR', 'ROLE_DFM', 'ROLE_ADMIN')")
    @ApiOperation(
        value = "Gets all input ports",
        response = InputPortsEntity.class,
        authorizations = {
            @Authorization(value = "Read Only", type = "ROLE_MONITOR"),
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM"),
            @Authorization(value = "Administrator", type = "ROLE_ADMIN")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response getInputPorts(
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // get all the input ports
        final Set<PortEntity> inputPorts = serviceFacade.getInputPorts(groupId);

        final InputPortsEntity entity = new InputPortsEntity();
        entity.setInputPorts(inputPortResource.populateRemainingInputPortEntitiesContent(inputPorts));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    // ------------
    // output ports
    // ------------

    /**
     * Creates a new output port.
     *
     * @param httpServletRequest request
     * @param groupId The group id
     * @param portEntity A outputPortEntity.
     * @return A outputPortEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/output-ports")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Creates an output port",
        response = PortEntity.class,
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response createOutputPort(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId,
        @ApiParam(
            value = "The output port configuration.",
            required = true
        ) PortEntity portEntity) {

        if (portEntity == null || portEntity.getComponent() == null) {
            throw new IllegalArgumentException("Port details must be specified.");
        }

        if (portEntity.getComponent().getId() != null) {
            throw new IllegalArgumentException("Output port ID cannot be specified.");
        }

        if (portEntity.getComponent().getParentGroupId() != null && !groupId.equals(portEntity.getComponent().getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                portEntity.getComponent().getParentGroupId(), groupId));
        }
        portEntity.getComponent().setParentGroupId(groupId);

        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.POST, getAbsolutePath(), portEntity, getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            return generateContinueResponse().build();
        }

        // set the processor id as appropriate
        final ClusterContext clusterContext = ClusterContextThreadLocal.getContext();
        if (clusterContext != null) {
            portEntity.getComponent().setId(UUID.nameUUIDFromBytes(clusterContext.getIdGenerationSeed().getBytes(StandardCharsets.UTF_8)).toString());
        } else {
            portEntity.getComponent().setId(UUID.randomUUID().toString());
        }

        // create the output port and generate the json
        final PortEntity entity = serviceFacade.createOutputPort(groupId, portEntity.getComponent());
        outputPortResource.populateRemainingOutputPortEntityContent(entity);

        // build the response
        return clusterContext(generateCreatedResponse(URI.create(entity.getComponent().getUri()), entity)).build();
    }

    /**
     * Retrieves all the of output ports in this NiFi.
     *
     * @return A outputPortsEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/output-ports")
    // TODO - @PreAuthorize("hasAnyRole('ROLE_MONITOR', 'ROLE_DFM', 'ROLE_ADMIN')")
    @ApiOperation(
        value = "Gets all output ports",
        response = OutputPortsEntity.class,
        authorizations = {
            @Authorization(value = "Read Only", type = "ROLE_MONITOR"),
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM"),
            @Authorization(value = "Administrator", type = "ROLE_ADMIN")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response getOutputPorts(
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // get all the output ports
        final Set<PortEntity> outputPorts = serviceFacade.getOutputPorts(groupId);

        // create the response entity
        final OutputPortsEntity entity = new OutputPortsEntity();
        entity.setOutputPorts(outputPortResource.populateRemainingOutputPortEntitiesContent(outputPorts));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    // -------
    // funnels
    // -------

    /**
     * Creates a new Funnel.
     *
     * @param httpServletRequest request
     * @param groupId The group id
     * @param funnelEntity A funnelEntity.
     * @return A funnelEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/funnels")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Creates a funnel",
        response = FunnelEntity.class,
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response createFunnel(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId,
        @ApiParam(
            value = "The funnel configuration details.",
            required = true
        ) FunnelEntity funnelEntity) {

        if (funnelEntity == null || funnelEntity.getComponent() == null) {
            throw new IllegalArgumentException("Funnel details must be specified.");
        }

        if (funnelEntity.getComponent().getId() != null) {
            throw new IllegalArgumentException("Funnel ID cannot be specified.");
        }

        if (funnelEntity.getComponent().getParentGroupId() != null && !groupId.equals(funnelEntity.getComponent().getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                funnelEntity.getComponent().getParentGroupId(), groupId));
        }
        funnelEntity.getComponent().setParentGroupId(groupId);

        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.POST, getAbsolutePath(), funnelEntity, getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            return generateContinueResponse().build();
        }

        // set the processor id as appropriate
        final ClusterContext clusterContext = ClusterContextThreadLocal.getContext();
        if (clusterContext != null) {
            funnelEntity.getComponent().setId(UUID.nameUUIDFromBytes(clusterContext.getIdGenerationSeed().getBytes(StandardCharsets.UTF_8)).toString());
        } else {
            funnelEntity.getComponent().setId(UUID.randomUUID().toString());
        }

        // create the funnel and generate the json
        final FunnelEntity entity = serviceFacade.createFunnel(groupId, funnelEntity.getComponent());
        funnelResource.populateRemainingFunnelEntityContent(entity);

        // build the response
        return clusterContext(generateCreatedResponse(URI.create(entity.getComponent().getUri()), entity)).build();
    }

    /**
     * Retrieves all the of funnels in this NiFi.
     *
     * @return A funnelsEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/funnels")
    // TODO - @PreAuthorize("hasAnyRole('ROLE_MONITOR', 'ROLE_DFM', 'ROLE_ADMIN')")
    @ApiOperation(
        value = "Gets all funnels",
        response = FunnelsEntity.class,
        authorizations = {
            @Authorization(value = "Read Only", type = "ROLE_MONITOR"),
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM"),
            @Authorization(value = "Administrator", type = "ROLE_ADMIN")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response getFunnels(
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // get all the funnels
        final Set<FunnelEntity> funnels = serviceFacade.getFunnels(groupId);

        // create the response entity
        final FunnelsEntity entity = new FunnelsEntity();
        entity.setFunnels(funnelResource.populateRemainingFunnelEntitiesContent(funnels));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    // ------
    // labels
    // ------

    /**
     * Creates a new Label.
     *
     * @param httpServletRequest request
     * @param groupId The group id
     * @param labelEntity A labelEntity.
     * @return A labelEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/labels")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Creates a label",
        response = LabelEntity.class,
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response createLabel(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId,
        @ApiParam(
            value = "The label configuration details.",
            required = true
        ) LabelEntity labelEntity) {

        if (labelEntity == null || labelEntity.getComponent() == null) {
            throw new IllegalArgumentException("Label details must be specified.");
        }

        if (labelEntity.getComponent().getId() != null) {
            throw new IllegalArgumentException("Label ID cannot be specified.");
        }

        if (labelEntity.getComponent().getParentGroupId() != null && !groupId.equals(labelEntity.getComponent().getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                labelEntity.getComponent().getParentGroupId(), groupId));
        }
        labelEntity.getComponent().setParentGroupId(groupId);

        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.POST, getAbsolutePath(), labelEntity, getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            return generateContinueResponse().build();
        }

        // set the processor id as appropriate
        final ClusterContext clusterContext = ClusterContextThreadLocal.getContext();
        if (clusterContext != null) {
            labelEntity.getComponent().setId(UUID.nameUUIDFromBytes(clusterContext.getIdGenerationSeed().getBytes(StandardCharsets.UTF_8)).toString());
        } else {
            labelEntity.getComponent().setId(UUID.randomUUID().toString());
        }

        // create the label and generate the json
        final LabelEntity entity = serviceFacade.createLabel(groupId, labelEntity.getComponent());
        labelResource.populateRemainingLabelEntityContent(entity);

        // build the response
        return clusterContext(generateCreatedResponse(URI.create(entity.getComponent().getUri()), entity)).build();
    }

    /**
     * Retrieves all the of labels in this NiFi.
     *
     * @return A labelsEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/labels")
    // TODO - @PreAuthorize("hasAnyRole('ROLE_MONITOR', 'ROLE_DFM', 'ROLE_ADMIN')")
    @ApiOperation(
        value = "Gets all labels",
        response = LabelsEntity.class,
        authorizations = {
            @Authorization(value = "Read Only", type = "ROLE_MONITOR"),
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM"),
            @Authorization(value = "Administrator", type = "ROLE_ADMIN")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response getLabels(
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // get all the labels
        final Set<LabelEntity> labels = serviceFacade.getLabels(groupId);

        // create the response entity
        final LabelsEntity entity = new LabelsEntity();
        entity.setLabels(labelResource.populateRemainingLabelEntitiesContent(labels));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    // ---------------------
    // remote process groups
    // ---------------------

    /**
     * Creates a new remote process group.
     *
     * @param httpServletRequest request
     * @param groupId The group id
     * @param remoteProcessGroupEntity A remoteProcessGroupEntity.
     * @return A remoteProcessGroupEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/remote-process-groups")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Creates a new process group",
        response = RemoteProcessGroupEntity.class,
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM"),
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response createRemoteProcessGroup(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId,
        @ApiParam(
            value = "The remote process group configuration details.",
            required = true
        ) RemoteProcessGroupEntity remoteProcessGroupEntity) {

        if (remoteProcessGroupEntity == null || remoteProcessGroupEntity.getComponent() == null) {
            throw new IllegalArgumentException("Remote process group details must be specified.");
        }

        final RemoteProcessGroupDTO requestProcessGroupDTO = remoteProcessGroupEntity.getComponent();

        if (requestProcessGroupDTO.getId() != null) {
            throw new IllegalArgumentException("Remote process group ID cannot be specified.");
        }

        if (requestProcessGroupDTO.getTargetUri() == null) {
            throw new IllegalArgumentException("The URI of the process group must be specified.");
        }

        if (requestProcessGroupDTO.getParentGroupId() != null && !groupId.equals(requestProcessGroupDTO.getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                requestProcessGroupDTO.getParentGroupId(), groupId));
        }
        requestProcessGroupDTO.setParentGroupId(groupId);

        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.POST, getAbsolutePath(), remoteProcessGroupEntity, getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            return generateContinueResponse().build();
        }

        // set the processor id as appropriate
        final ClusterContext clusterContext = ClusterContextThreadLocal.getContext();
        if (clusterContext != null) {
            requestProcessGroupDTO.setId(UUID.nameUUIDFromBytes(clusterContext.getIdGenerationSeed().getBytes(StandardCharsets.UTF_8)).toString());
        } else {
            requestProcessGroupDTO.setId(UUID.randomUUID().toString());
        }

        // parse the uri
        final URI uri;
        try {
            uri = URI.create(requestProcessGroupDTO.getTargetUri());
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("The specified remote process group URL is malformed: " + requestProcessGroupDTO.getTargetUri());
        }

        // validate each part of the uri
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("The specified remote process group URL is malformed: " + requestProcessGroupDTO.getTargetUri());
        }

        if (!(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("The specified remote process group URL is invalid because it is not http or https: " + requestProcessGroupDTO.getTargetUri());
        }

        // normalize the uri to the other controller
        String controllerUri = uri.toString();
        if (controllerUri.endsWith("/")) {
            controllerUri = StringUtils.substringBeforeLast(controllerUri, "/");
        }

        // since the uri is valid, use the normalized version
        requestProcessGroupDTO.setTargetUri(controllerUri);

        // create the remote process group
        final RemoteProcessGroupEntity entity = serviceFacade.createRemoteProcessGroup(groupId, requestProcessGroupDTO);
        remoteProcessGroupResource.populateRemainingRemoteProcessGroupEntityContent(entity);

        return clusterContext(generateCreatedResponse(URI.create(entity.getComponent().getUri()), entity)).build();
    }

    /**
     * Retrieves all the of remote process groups in this NiFi.
     *
     * @param verbose Optional verbose flag that defaults to false. If the verbose flag is set to true remote group contents (ports) will be included.
     * @return A remoteProcessGroupEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/remote-process-groups")
    // TODO - @PreAuthorize("hasAnyRole('ROLE_MONITOR', 'ROLE_DFM', 'ROLE_ADMIN')")
    @ApiOperation(
        value = "Gets all remote process groups",
        response = RemoteProcessGroupsEntity.class,
        authorizations = {
            @Authorization(value = "Read Only", type = "ROLE_MONITOR"),
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM"),
            @Authorization(value = "Administrator", type = "ROLE_ADMIN")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response getRemoteProcessGroups(
        @ApiParam(
            value = "Whether to include any encapulated ports or just details about the remote process group.",
            required = false
        )
        @QueryParam("verbose") @DefaultValue(VERBOSE) Boolean verbose,
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // get all the remote process groups
        final Set<RemoteProcessGroupEntity> remoteProcessGroups = serviceFacade.getRemoteProcessGroups(groupId);

        // prune response as necessary
        if (!verbose) {
            for (RemoteProcessGroupEntity remoteProcessGroupEntity : remoteProcessGroups) {
                if (remoteProcessGroupEntity.getComponent() != null) {
                    remoteProcessGroupEntity.getComponent().setContents(null);
                }
            }
        }

        // create the response entity
        final RemoteProcessGroupsEntity entity = new RemoteProcessGroupsEntity();
        entity.setRemoteProcessGroups(remoteProcessGroupResource.populateRemainingRemoteProcessGroupEntitiesContent(remoteProcessGroups));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    // -----------
    // connections
    // -----------

    /**
     * Creates a new connection.
     *
     * @param httpServletRequest request
     * @param groupId The group id
     * @param connectionEntity A connectionEntity.
     * @return A connectionEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/connections")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Creates a connection",
        response = ConnectionEntity.class,
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response createConnection(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId,
        @ApiParam(
            value = "The connection configuration details.",
            required = true
        ) ConnectionEntity connectionEntity) {

        if (connectionEntity == null || connectionEntity.getComponent() == null) {
            throw new IllegalArgumentException("Connection details must be specified.");
        }

        if (connectionEntity.getComponent().getId() != null) {
            throw new IllegalArgumentException("Connection ID cannot be specified.");
        }

        if (connectionEntity.getComponent().getParentGroupId() != null && !groupId.equals(connectionEntity.getComponent().getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                connectionEntity.getComponent().getParentGroupId(), groupId));
        }
        connectionEntity.getComponent().setParentGroupId(groupId);

        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.POST, getAbsolutePath(), connectionEntity, getHeaders()).getResponse();
        }

        // get the connection
        final ConnectionDTO connection = connectionEntity.getComponent();

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            serviceFacade.verifyCreateConnection(groupId, connection);
            return generateContinueResponse().build();
        }

        // set the processor id as appropriate
        final ClusterContext clusterContext = ClusterContextThreadLocal.getContext();
        if (clusterContext != null) {
            connection.setId(UUID.nameUUIDFromBytes(clusterContext.getIdGenerationSeed().getBytes(StandardCharsets.UTF_8)).toString());
        } else {
            connection.setId(UUID.randomUUID().toString());
        }

        // create the new relationship target
        final ConnectionEntity entity = serviceFacade.createConnection(groupId, connection);
        connectionResource.populateRemainingConnectionEntityContent(entity);

        // extract the href and build the response
        String uri = entity.getComponent().getUri();
        return clusterContext(generateCreatedResponse(URI.create(uri), entity)).build();
    }

    /**
     * Gets all the connections.
     *
     * @return A connectionsEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/connections")
    // TODO - @PreAuthorize("hasAnyRole('ROLE_MONITOR', 'ROLE_DFM', 'ROLE_ADMIN')")
    @ApiOperation(
        value = "Gets all connections",
        response = ConnectionsEntity.class,
        authorizations = {
            @Authorization(value = "Read Only", type = "ROLE_MONITOR"),
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM"),
            @Authorization(value = "Administrator", type = "ROLE_ADMIN")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response getConnections(
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // all of the relationships for the specified source processor
        Set<ConnectionEntity> connections = serviceFacade.getConnections(groupId);

        // create the client response entity
        ConnectionsEntity entity = new ConnectionsEntity();
        entity.setConnections(connectionResource.populateRemainingConnectionEntitiesContent(connections));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    // --------
    // snippets
    // --------

    /**
     * Creates a snippet based off the specified configuration.
     *
     * @param httpServletRequest request
     * @param snippetEntity A snippetEntity
     * @return A snippetEntity
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/snippets")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Creates a snippet",
        response = SnippetEntity.class,
        authorizations = {
            @Authorization(value = "Read Only", type = "ROLE_MONITOR"),
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM"),
            @Authorization(value = "Administrator", type = "ROLE_ADMIN")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response createSnippet(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId,
        @ApiParam(
            value = "The snippet configuration details.",
            required = true
        )
        final SnippetEntity snippetEntity) {

        if (snippetEntity == null || snippetEntity.getSnippet() == null) {
            throw new IllegalArgumentException("Snippet details must be specified.");
        }

        if (snippetEntity.getSnippet().getId() != null) {
            throw new IllegalArgumentException("Snippet ID cannot be specified.");
        }

        // ensure the group id has been specified
        if (snippetEntity.getSnippet().getParentGroupId() == null) {
            throw new IllegalArgumentException("The group id must be specified when creating a snippet.");
        }

        if (snippetEntity.getSnippet().getParentGroupId() != null && !groupId.equals(snippetEntity.getSnippet().getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                snippetEntity.getSnippet().getParentGroupId(), groupId));
        }
        snippetEntity.getSnippet().setParentGroupId(groupId);

        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.POST, getAbsolutePath(), snippetEntity, getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            return generateContinueResponse().build();
        }

        // set the processor id as appropriate
        final ClusterContext clusterContext = ClusterContextThreadLocal.getContext();
        if (clusterContext != null) {
            snippetEntity.getSnippet().setId(UUID.nameUUIDFromBytes(clusterContext.getIdGenerationSeed().getBytes(StandardCharsets.UTF_8)).toString());
        } else {
            snippetEntity.getSnippet().setId(UUID.randomUUID().toString());
        }

        // create the snippet
        final SnippetEntity entity = serviceFacade.createSnippet(snippetEntity.getSnippet());
        populateRemainingSnippetEntityContent(entity);

        // build the response
        return clusterContext(generateCreatedResponse(URI.create(entity.getSnippet().getUri()), entity)).build();
    }

    /**
     * Retrieves the specified snippet.
     *
     * @param id The id of the snippet to retrieve.
     * @return A snippetEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/snippets/{snippet-id}")
    // TODO - @PreAuthorize("hasAnyRole('ROLE_MONITOR', 'ROLE_DFM', 'ROLE_ADMIN')")
    @ApiOperation(
        value = "Gets a snippet",
        response = SnippetEntity.class,
        authorizations = {
            @Authorization(value = "Read Only", type = "ROLE_MONITOR"),
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM"),
            @Authorization(value = "Administrator", type = "ROLE_ADMIN")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response getSnippet(
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId,
        @ApiParam(
            value = "The snippet id.",
            required = true
        )
        @PathParam("snippet-id") String id) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // get the snippet
        final SnippetEntity entity = serviceFacade.getSnippet(id);
        populateRemainingSnippetEntityContent(entity);

        return clusterContext(generateOkResponse(entity)).build();
    }

    /**
     * Updates the specified snippet. The contents of the snippet (component
     * ids) cannot be updated once the snippet is created.
     *
     * @param httpServletRequest request
     * @param id The id of the snippet.
     * @param snippetEntity A snippetEntity
     * @return A snippetEntity
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/snippets/{snippet-id}")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Updates a snippet",
        response = SnippetEntity.class,
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response updateSnippet(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId,
        @ApiParam(
            value = "The snippet id.",
            required = true
        )
        @PathParam("snippet-id") String id,
        @ApiParam(
            value = "The snippet configuration details.",
            required = true
        ) final SnippetEntity snippetEntity) {

        if (snippetEntity == null || snippetEntity.getSnippet() == null) {
            throw new IllegalArgumentException("Snippet details must be specified.");
        }

        if (snippetEntity.getRevision() == null) {
            throw new IllegalArgumentException("Revision must be specified.");
        }

        // ensure the ids are the same
        final SnippetDTO requestSnippetDTO = snippetEntity.getSnippet();
        if (!id.equals(requestSnippetDTO.getId())) {
            throw new IllegalArgumentException(String.format("The snippet id (%s) in the request body does not equal the "
                + "snippet id of the requested resource (%s).", requestSnippetDTO.getId(), id));
        }

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.PUT, getAbsolutePath(), snippetEntity, getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final Revision revision = getRevision(snippetEntity, id);
        final boolean validationPhase = isValidationPhase(httpServletRequest);
        if (validationPhase || !isTwoPhaseRequest(httpServletRequest)) {
            serviceFacade.claimRevision(revision);
        }
        if (validationPhase) {
            serviceFacade.verifyUpdateSnippet(requestSnippetDTO);
            return generateContinueResponse().build();
        }

        // update the snippet
        final UpdateResult<SnippetEntity> updateResult = serviceFacade.updateSnippet(revision, snippetEntity.getSnippet());

        // get the results
        final SnippetEntity entity = updateResult.getResult();
        populateRemainingSnippetEntityContent(entity);

        if (updateResult.isNew()) {
            return clusterContext(generateCreatedResponse(URI.create(entity.getSnippet().getUri()), entity)).build();
        } else {
            return clusterContext(generateOkResponse(entity)).build();
        }
    }

    /**
     * Removes the specified snippet.
     *
     * @param httpServletRequest request
     * @param version The revision is used to verify the client is working with
     * the latest version of the flow.
     * @param clientId Optional client id. If the client id is not specified, a
     * new one will be generated. This value (whether specified or generated) is
     * included in the response.
     * @param id The id of the snippet to remove.
     * @return A entity containing the client id and an updated revision.
     */
    @DELETE
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/snippets/{snippet-id}")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Deletes a snippet",
        response = SnippetEntity.class,
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response deleteSnippet(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "The revision is used to verify the client is working with the latest version of the flow.",
            required = false
        )
        @QueryParam(VERSION) LongParameter version,
        @ApiParam(
            value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
            required = false
        )
        @QueryParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId,
        @ApiParam(
            value = "The snippet id.",
            required = true
        )
        @PathParam("snippet-id") String id) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.DELETE, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        final Revision revision = new Revision(version == null ? null : version.getLong(), clientId.getClientId(), id);

        // handle expects request (usually from the cluster manager)
        final boolean validationPhase = isValidationPhase(httpServletRequest);

        // We need to claim the revision for the Processor if either this is the first phase of a two-phase
        // request, or if this is not a two-phase request.
        if (validationPhase || !isTwoPhaseRequest(httpServletRequest)) {
            serviceFacade.claimRevision(revision);
        }
        if (validationPhase) {
            serviceFacade.verifyDeleteSnippet(id);
            return generateContinueResponse().build();
        }

        // delete the specified snippet
        final SnippetEntity snippetEntity = serviceFacade.deleteSnippet(revision, id);

        return clusterContext(generateOkResponse(snippetEntity)).build();
    }

    // ----------------
    // snippet instance
    // ----------------

    /**
     * Copies the specified snippet within this ProcessGroup. The snippet instance that is instantiated cannot be referenced at a later time, therefore there is no
     * corresponding URI. Instead the request URI is returned.
     *
     * Alternatively, we could have performed a PUT request. However, PUT requests are supposed to be idempotent and this endpoint is certainly not.
     *
     * @param httpServletRequest request
     * @param groupId The group id
     * @param copySnippetEntity The copy snippet request
     * @return A flowSnippetEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/snippet-instance")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Copies a snippet",
        response = FlowSnippetEntity.class,
        authorizations = {
            @Authorization(value = "ROLE_DFM", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response copySnippet(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId,
        @ApiParam(
            value = "The copy snippet request.",
            required = true
        ) CopySnippetRequestEntity copySnippetEntity) {

        // ensure the position has been specified
        if (copySnippetEntity == null || copySnippetEntity.getOriginX() == null || copySnippetEntity.getOriginY() == null) {
            throw new IllegalArgumentException("The  origin position (x, y) must be specified");
        }

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.POST, getAbsolutePath(), copySnippetEntity, getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            return generateContinueResponse().build();
        }

        // copy the specified snippet
        final FlowEntity flowEntity = serviceFacade.copySnippet(
            groupId, copySnippetEntity.getSnippetId(), copySnippetEntity.getOriginX(), copySnippetEntity.getOriginY());

        // get the snippet
        final FlowDTO flow = flowEntity.getFlow();

        // prune response as necessary
        for (ProcessGroupEntity childGroupEntity : flow.getProcessGroups()) {
            childGroupEntity.getComponent().setContents(null);
        }

        // create the response entity
        populateRemainingSnippetContent(flow);

        // generate the response
        return clusterContext(generateCreatedResponse(getAbsolutePath(), flowEntity)).build();
    }

    // -----------------
    // template instance
    // -----------------

    /**
     * Instantiates the specified template within this ProcessGroup. The template instance that is instantiated cannot be referenced at a later time, therefore there is no
     * corresponding URI. Instead the request URI is returned.
     *
     * Alternatively, we could have performed a PUT request. However, PUT requests are supposed to be idempotent and this endpoint is certainly not.
     *
     * @param httpServletRequest request
     * @param groupId The group id
     * @param instantiateTemplateRequestEntity The instantiate template request
     * @return A flowEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/template-instance")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Instantiates a template",
        response = FlowEntity.class,
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response instantiateTemplate(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId,
        @ApiParam(
            value = "The instantiate template request.",
            required = true
        ) InstantiateTemplateRequestEntity instantiateTemplateRequestEntity) {

        // ensure the position has been specified
        if (instantiateTemplateRequestEntity == null || instantiateTemplateRequestEntity.getOriginX() == null || instantiateTemplateRequestEntity.getOriginY() == null) {
            throw new IllegalArgumentException("The  origin position (x, y) must be specified");
        }

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.POST, getAbsolutePath(), instantiateTemplateRequestEntity, getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            return generateContinueResponse().build();
        }

        // create the template and generate the json
        final FlowEntity entity = serviceFacade.createTemplateInstance(groupId, instantiateTemplateRequestEntity.getOriginX(),
            instantiateTemplateRequestEntity.getOriginY(), instantiateTemplateRequestEntity.getTemplateId());

        final FlowDTO flowSnippet = entity.getFlow();

        // prune response as necessary
        for (ProcessGroupEntity childGroupEntity : flowSnippet.getProcessGroups()) {
            childGroupEntity.getComponent().setContents(null);
        }

        // create the response entity
        populateRemainingSnippetContent(flowSnippet);

        // generate the response
        return clusterContext(generateCreatedResponse(getAbsolutePath(), entity)).build();
    }

    // ---------
    // templates
    // ---------

    /**
     * Retrieves all the of templates in this NiFi.
     *
     * @return A templatesEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/templates")
    // TODO - @PreAuthorize("hasAnyRole('ROLE_MONITOR', 'ROLE_DFM', 'ROLE_ADMIN')")
    @ApiOperation(
        value = "Gets all templates",
        response = TemplatesEntity.class,
        authorizations = {
            @Authorization(value = "Read Only", type = "ROLE_MONITOR"),
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM"),
            @Authorization(value = "Administrator", type = "ROLE_ADMIN")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response getTemplates(
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // get all the templates
        final Set<TemplateDTO> templates = templateResource.populateRemainingTemplatesContent(serviceFacade.getTemplates());

        // create the response entity
        final TemplatesEntity entity = new TemplatesEntity();
        entity.setTemplates(templates);
        entity.setGenerated(new Date());

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    /**
     * Creates a new template based off of the specified template.
     *
     * @param httpServletRequest request
     * @param createTemplateRequestEntity request to create the template
     * @return A templateEntity
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/templates")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Creates a template",
        response = TemplateEntity.class,
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response createTemplate(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId,
        @ApiParam(
            value = "The create template request.",
            required = true
        ) CreateTemplateRequestEntity createTemplateRequestEntity) {

        // TODO - verify parent group id

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.POST, getAbsolutePath(), createTemplateRequestEntity, getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            return generateContinueResponse().build();
        }

        // create the template and generate the json
        final TemplateDTO template = serviceFacade.createTemplate(createTemplateRequestEntity.getName(), createTemplateRequestEntity.getDescription(),
            createTemplateRequestEntity.getSnippetId(), groupId);
        templateResource.populateRemainingTemplateContent(template);

        // build the response entity
        final TemplateEntity entity = new TemplateEntity();
        entity.setTemplate(template);

        // build the response
        return clusterContext(generateCreatedResponse(URI.create(template.getUri()), entity)).build();
    }

    /**
     * Imports the specified template.
     *
     * @param httpServletRequest request
     * @param clientId Optional client id. If the client id is not specified, a
     * new one will be generated. This value (whether specified or generated) is
     * included in the response.
     * @param in The template stream
     * @return A templateEntity or an errorResponse XML snippet.
     */
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_XML)
    @Path("{id}/templates/upload")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    public Response uploadTemplate(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId,
        @FormDataParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
        @FormDataParam("template") InputStream in) {

        // unmarshal the template
        final TemplateDTO template;
        try {
            JAXBContext context = JAXBContext.newInstance(TemplateDTO.class);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            JAXBElement<TemplateDTO> templateElement = unmarshaller.unmarshal(new StreamSource(in), TemplateDTO.class);
            template = templateElement.getValue();
        } catch (JAXBException jaxbe) {
            logger.warn("An error occurred while parsing a template.", jaxbe);
            String responseXml = String.format("<errorResponse status=\"%s\" statusText=\"The specified template is not in a valid format.\"/>", Response.Status.BAD_REQUEST.getStatusCode());
            return Response.status(Response.Status.OK).entity(responseXml).type("application/xml").build();
        } catch (IllegalArgumentException iae) {
            logger.warn("Unable to import template.", iae);
            String responseXml = String.format("<errorResponse status=\"%s\" statusText=\"%s\"/>", Response.Status.BAD_REQUEST.getStatusCode(), iae.getMessage());
            return Response.status(Response.Status.OK).entity(responseXml).type("application/xml").build();
        } catch (Exception e) {
            logger.warn("An error occurred while importing a template.", e);
            String responseXml = String.format("<errorResponse status=\"%s\" statusText=\"Unable to import the specified template: %s\"/>",
                Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), e.getMessage());
            return Response.status(Response.Status.OK).entity(responseXml).type("application/xml").build();
        }

        // build the response entity
        TemplateEntity entity = new TemplateEntity();
        entity.setTemplate(template);

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            // convert request accordingly
            URI importUri = null;
            try {
                importUri = new URI(generateResourceUri("process-groups", groupId, "templates", "import"));
            } catch (final URISyntaxException e) {
                throw new WebApplicationException(e);
            }

            // change content type to JSON for serializing entity
            final Map<String, String> headersToOverride = new HashMap<>();
            headersToOverride.put("content-type", MediaType.APPLICATION_XML);

            // replicate the request
            return clusterManager.applyRequest(HttpMethod.POST, importUri, entity, getHeaders(headersToOverride)).getResponse();
        }

        // otherwise import the template locally
        return importTemplate(httpServletRequest, groupId, entity);
    }

    /**
     * Imports the specified template.
     *
     * @param httpServletRequest request
     * @param templateEntity A templateEntity.
     * @return A templateEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_XML)
    @Path("{id}/templates/import")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    public Response importTemplate(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId,
        TemplateEntity templateEntity) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.POST, getAbsolutePath(), templateEntity, getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            return generateContinueResponse().build();
        }

        try {
            // verify the template was specified
            if (templateEntity == null || templateEntity.getTemplate() == null) {
                throw new IllegalArgumentException("Template details must be specified.");
            }

            // import the template
            final TemplateDTO template = serviceFacade.importTemplate(templateEntity.getTemplate(), groupId);
            templateResource.populateRemainingTemplateContent(template);

            // build the response entity
            TemplateEntity entity = new TemplateEntity();
            entity.setTemplate(template);

            // build the response
            return clusterContext(generateCreatedResponse(URI.create(template.getUri()), entity)).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            logger.info("Unable to import template: " + e);
            String responseXml = String.format("<errorResponse status=\"%s\" statusText=\"%s\"/>", Response.Status.BAD_REQUEST.getStatusCode(), e.getMessage());
            return Response.status(Response.Status.OK).entity(responseXml).type("application/xml").build();
        } catch (Exception e) {
            logger.warn("An error occurred while importing a template.", e);
            String responseXml
                = String.format("<errorResponse status=\"%s\" statusText=\"Unable to import the specified template: %s\"/>", Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), e.getMessage());
            return Response.status(Response.Status.OK).entity(responseXml).type("application/xml").build();
        }
    }

    // -------------------
    // controller services
    // -------------------

    /**
     * Creates a new Controller Service.
     *
     * @param httpServletRequest request
     * @param controllerServiceEntity A controllerServiceEntity.
     * @return A controllerServiceEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}/controller-services")
    // TODO - @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Creates a new controller service",
        response = ControllerServiceEntity.class,
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response createControllerService(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "The process group id.",
            required = true
        )
        @PathParam("id") String groupId,
        @ApiParam(
            value = "The controller service configuration details.",
            required = true
        ) ControllerServiceEntity controllerServiceEntity) {

        if (controllerServiceEntity == null || controllerServiceEntity.getComponent() == null) {
            throw new IllegalArgumentException("Controller service details must be specified.");
        }

        if (controllerServiceEntity.getComponent().getId() != null) {
            throw new IllegalArgumentException("Controller service ID cannot be specified.");
        }

        if (StringUtils.isBlank(controllerServiceEntity.getComponent().getType())) {
            throw new IllegalArgumentException("The type of controller service to create must be specified.");
        }

        if (controllerServiceEntity.getComponent().getParentGroupId() != null && !groupId.equals(controllerServiceEntity.getComponent().getParentGroupId())) {
            throw new IllegalArgumentException(String.format("If specified, the parent process group id %s must be the same as specified in the URI %s",
                controllerServiceEntity.getComponent().getParentGroupId(), groupId));
        }
        controllerServiceEntity.getComponent().setParentGroupId(groupId);

        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.POST, getAbsolutePath(), controllerServiceEntity, getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            return generateContinueResponse().build();
        }

        // set the processor id as appropriate
        final ClusterContext clusterContext = ClusterContextThreadLocal.getContext();
        if (clusterContext != null) {
            controllerServiceEntity.getComponent().setId(UUID.nameUUIDFromBytes(clusterContext.getIdGenerationSeed().getBytes(StandardCharsets.UTF_8)).toString());
        } else {
            controllerServiceEntity.getComponent().setId(UUID.randomUUID().toString());
        }

        // create the controller service and generate the json
        final ControllerServiceEntity entity = serviceFacade.createControllerService(groupId, controllerServiceEntity.getComponent());
        controllerServiceResource.populateRemainingControllerServiceContent(entity.getComponent());

        // build the response
        return clusterContext(generateCreatedResponse(URI.create(entity.getComponent().getUri()), entity)).build();
    }

    // setters
    public void setServiceFacade(NiFiServiceFacade serviceFacade) {
        this.serviceFacade = serviceFacade;
    }

    public void setClusterManager(WebClusterManager clusterManager) {
        this.clusterManager = clusterManager;
    }

    public void setProcessorResource(ProcessorResource processorResource) {
        this.processorResource = processorResource;
    }

    public void setInputPortResource(InputPortResource inputPortResource) {
        this.inputPortResource = inputPortResource;
    }

    public void setOutputPortResource(OutputPortResource outputPortResource) {
        this.outputPortResource = outputPortResource;
    }

    public void setFunnelResource(FunnelResource funnelResource) {
        this.funnelResource = funnelResource;
    }

    public void setLabelResource(LabelResource labelResource) {
        this.labelResource = labelResource;
    }

    public void setRemoteProcessGroupResource(RemoteProcessGroupResource remoteProcessGroupResource) {
        this.remoteProcessGroupResource = remoteProcessGroupResource;
    }

    public void setConnectionResource(ConnectionResource connectionResource) {
        this.connectionResource = connectionResource;
    }

    public void setTemplateResource(TemplateResource templateResource) {
        this.templateResource = templateResource;
    }

    public void setControllerServiceResource(ControllerServiceResource controllerServiceResource) {
        this.controllerServiceResource = controllerServiceResource;
    }

    public void setProperties(NiFiProperties properties) {
        this.properties = properties;
    }
}
