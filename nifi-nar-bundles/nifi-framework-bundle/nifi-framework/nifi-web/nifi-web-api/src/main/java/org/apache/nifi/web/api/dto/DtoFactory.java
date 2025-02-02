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
package org.apache.nifi.web.api.dto;

import org.apache.nifi.action.Action;
import org.apache.nifi.action.component.details.ComponentDetails;
import org.apache.nifi.action.component.details.ExtensionDetails;
import org.apache.nifi.action.component.details.FlowChangeExtensionDetails;
import org.apache.nifi.action.component.details.FlowChangeRemoteProcessGroupDetails;
import org.apache.nifi.action.component.details.RemoteProcessGroupDetails;
import org.apache.nifi.action.details.ActionDetails;
import org.apache.nifi.action.details.ConfigureDetails;
import org.apache.nifi.action.details.ConnectDetails;
import org.apache.nifi.action.details.FlowChangeConfigureDetails;
import org.apache.nifi.action.details.FlowChangeConnectDetails;
import org.apache.nifi.action.details.FlowChangeMoveDetails;
import org.apache.nifi.action.details.FlowChangePurgeDetails;
import org.apache.nifi.action.details.MoveDetails;
import org.apache.nifi.action.details.PurgeDetails;
import org.apache.nifi.annotation.behavior.Stateful;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.authorization.Authorizer;
import org.apache.nifi.authorization.RequestAction;
import org.apache.nifi.authorization.Resource;
import org.apache.nifi.authorization.resource.Authorizable;
import org.apache.nifi.cluster.coordination.heartbeat.NodeHeartbeat;
import org.apache.nifi.cluster.event.Event;
import org.apache.nifi.cluster.manager.StatusMerger;
import org.apache.nifi.cluster.node.Node;
import org.apache.nifi.cluster.protocol.NodeIdentifier;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.components.state.Scope;
import org.apache.nifi.components.state.StateMap;
import org.apache.nifi.connectable.Connectable;
import org.apache.nifi.connectable.ConnectableType;
import org.apache.nifi.connectable.Connection;
import org.apache.nifi.connectable.Funnel;
import org.apache.nifi.connectable.Port;
import org.apache.nifi.connectable.Position;
import org.apache.nifi.controller.ConfiguredComponent;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.controller.Counter;
import org.apache.nifi.controller.ProcessorNode;
import org.apache.nifi.controller.ReportingTaskNode;
import org.apache.nifi.controller.Snippet;
import org.apache.nifi.controller.Template;
import org.apache.nifi.controller.label.Label;
import org.apache.nifi.controller.queue.DropFlowFileState;
import org.apache.nifi.controller.queue.DropFlowFileStatus;
import org.apache.nifi.controller.queue.FlowFileSummary;
import org.apache.nifi.controller.queue.ListFlowFileState;
import org.apache.nifi.controller.queue.ListFlowFileStatus;
import org.apache.nifi.controller.queue.QueueSize;
import org.apache.nifi.controller.repository.FlowFileRecord;
import org.apache.nifi.controller.repository.claim.ContentClaim;
import org.apache.nifi.controller.repository.claim.ResourceClaim;
import org.apache.nifi.controller.service.ControllerServiceNode;
import org.apache.nifi.controller.service.ControllerServiceProvider;
import org.apache.nifi.controller.state.SortedStateUtils;
import org.apache.nifi.controller.status.ConnectionStatus;
import org.apache.nifi.controller.status.PortStatus;
import org.apache.nifi.controller.status.ProcessGroupStatus;
import org.apache.nifi.controller.status.ProcessorStatus;
import org.apache.nifi.controller.status.RemoteProcessGroupStatus;
import org.apache.nifi.diagnostics.GarbageCollection;
import org.apache.nifi.diagnostics.StorageUsage;
import org.apache.nifi.diagnostics.SystemDiagnostics;
import org.apache.nifi.flowfile.FlowFilePrioritizer;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.groups.ProcessGroup;
import org.apache.nifi.groups.ProcessGroupCounts;
import org.apache.nifi.groups.RemoteProcessGroup;
import org.apache.nifi.history.History;
import org.apache.nifi.processor.Processor;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.provenance.lineage.ComputeLineageResult;
import org.apache.nifi.provenance.lineage.ComputeLineageSubmission;
import org.apache.nifi.provenance.lineage.LineageEdge;
import org.apache.nifi.provenance.lineage.LineageNode;
import org.apache.nifi.provenance.lineage.ProvenanceEventLineageNode;
import org.apache.nifi.remote.RemoteGroupPort;
import org.apache.nifi.remote.RootGroupPort;
import org.apache.nifi.reporting.Bulletin;
import org.apache.nifi.reporting.ReportingTask;
import org.apache.nifi.scheduling.SchedulingStrategy;
import org.apache.nifi.util.FormatUtils;
import org.apache.nifi.web.FlowModification;
import org.apache.nifi.web.Revision;
import org.apache.nifi.web.api.dto.PropertyDescriptorDTO.AllowableValueDTO;
import org.apache.nifi.web.api.dto.action.ActionDTO;
import org.apache.nifi.web.api.dto.action.HistoryDTO;
import org.apache.nifi.web.api.dto.action.component.details.ComponentDetailsDTO;
import org.apache.nifi.web.api.dto.action.component.details.ExtensionDetailsDTO;
import org.apache.nifi.web.api.dto.action.component.details.RemoteProcessGroupDetailsDTO;
import org.apache.nifi.web.api.dto.action.details.ActionDetailsDTO;
import org.apache.nifi.web.api.dto.action.details.ConfigureDetailsDTO;
import org.apache.nifi.web.api.dto.action.details.ConnectDetailsDTO;
import org.apache.nifi.web.api.dto.action.details.MoveDetailsDTO;
import org.apache.nifi.web.api.dto.action.details.PurgeDetailsDTO;
import org.apache.nifi.web.api.dto.flow.FlowBreadcrumbDTO;
import org.apache.nifi.web.api.dto.flow.FlowDTO;
import org.apache.nifi.web.api.dto.flow.ProcessGroupFlowDTO;
import org.apache.nifi.web.api.dto.provenance.lineage.LineageDTO;
import org.apache.nifi.web.api.dto.provenance.lineage.LineageRequestDTO;
import org.apache.nifi.web.api.dto.provenance.lineage.LineageRequestDTO.LineageRequestType;
import org.apache.nifi.web.api.dto.provenance.lineage.LineageResultsDTO;
import org.apache.nifi.web.api.dto.provenance.lineage.ProvenanceLinkDTO;
import org.apache.nifi.web.api.dto.provenance.lineage.ProvenanceNodeDTO;
import org.apache.nifi.web.api.dto.status.ConnectionStatusDTO;
import org.apache.nifi.web.api.dto.status.ConnectionStatusSnapshotDTO;
import org.apache.nifi.web.api.dto.status.PortStatusDTO;
import org.apache.nifi.web.api.dto.status.PortStatusSnapshotDTO;
import org.apache.nifi.web.api.dto.status.ProcessGroupStatusDTO;
import org.apache.nifi.web.api.dto.status.ProcessGroupStatusSnapshotDTO;
import org.apache.nifi.web.api.dto.status.ProcessorStatusDTO;
import org.apache.nifi.web.api.dto.status.ProcessorStatusSnapshotDTO;
import org.apache.nifi.web.api.dto.status.RemoteProcessGroupStatusDTO;
import org.apache.nifi.web.api.dto.status.RemoteProcessGroupStatusSnapshotDTO;
import org.apache.nifi.web.revision.RevisionManager;

import javax.ws.rs.WebApplicationException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

public final class DtoFactory {

    @SuppressWarnings("rawtypes")
    private final static Comparator<Class> CLASS_NAME_COMPARATOR = new Comparator<Class>() {
        @Override
        public int compare(Class class1, Class class2) {
            return Collator.getInstance(Locale.US).compare(class1.getSimpleName(), class2.getSimpleName());
        }
    };

    private ControllerServiceProvider controllerServiceProvider;
    private EntityFactory entityFactory;
    private Authorizer authorizer;

    /**
     * Creates an ActionDTO for the specified Action.
     *
     * @param action action
     * @return dto
     */
    public ActionDTO createActionDto(final Action action) {
        final ActionDTO actionDto = new ActionDTO();
        actionDto.setId(action.getId());
        actionDto.setSourceId(action.getSourceId());
        actionDto.setSourceName(action.getSourceName());
        actionDto.setSourceType(action.getSourceType().name());
        actionDto.setTimestamp(action.getTimestamp());
        actionDto.setUserDn(action.getUserIdentity());
        actionDto.setUserName(action.getUserName());
        actionDto.setOperation(action.getOperation().name());
        actionDto.setActionDetails(createActionDetailsDto(action.getActionDetails()));
        actionDto.setComponentDetails(createComponentDetailsDto(action.getComponentDetails()));

        return actionDto;
    }

    /**
     * Creates an ActionDetailsDTO for the specified ActionDetails.
     *
     * @param actionDetails details
     * @return dto
     */
    private ActionDetailsDTO createActionDetailsDto(final ActionDetails actionDetails) {
        if (actionDetails == null) {
            return null;
        }

        if (actionDetails instanceof FlowChangeConfigureDetails) {
            final ConfigureDetailsDTO configureDetails = new ConfigureDetailsDTO();
            configureDetails.setName(((ConfigureDetails) actionDetails).getName());
            configureDetails.setPreviousValue(((ConfigureDetails) actionDetails).getPreviousValue());
            configureDetails.setValue(((ConfigureDetails) actionDetails).getValue());
            return configureDetails;
        } else if (actionDetails instanceof FlowChangeConnectDetails) {
            final ConnectDetailsDTO connectDetails = new ConnectDetailsDTO();
            connectDetails.setSourceId(((ConnectDetails) actionDetails).getSourceId());
            connectDetails.setSourceName(((ConnectDetails) actionDetails).getSourceName());
            connectDetails.setSourceType(((ConnectDetails) actionDetails).getSourceType().toString());
            connectDetails.setRelationship(((ConnectDetails) actionDetails).getRelationship());
            connectDetails.setDestinationId(((ConnectDetails) actionDetails).getDestinationId());
            connectDetails.setDestinationName(((ConnectDetails) actionDetails).getDestinationName());
            connectDetails.setDestinationType(((ConnectDetails) actionDetails).getDestinationType().toString());
            return connectDetails;
        } else if (actionDetails instanceof FlowChangeMoveDetails) {
            final MoveDetailsDTO moveDetails = new MoveDetailsDTO();
            moveDetails.setPreviousGroup(((MoveDetails) actionDetails).getPreviousGroup());
            moveDetails.setPreviousGroupId(((MoveDetails) actionDetails).getPreviousGroupId());
            moveDetails.setGroup(((MoveDetails) actionDetails).getGroup());
            moveDetails.setGroupId(((MoveDetails) actionDetails).getGroupId());
            return moveDetails;
        } else if (actionDetails instanceof FlowChangePurgeDetails) {
            final PurgeDetailsDTO purgeDetails = new PurgeDetailsDTO();
            purgeDetails.setEndDate(((PurgeDetails) actionDetails).getEndDate());
            return purgeDetails;
        } else {
            throw new WebApplicationException(new IllegalArgumentException(String.format("Unrecognized type of action details encountered %s during serialization.", actionDetails.toString())));
        }
    }

    /**
     * Creates a ComponentDetailsDTO for the specified ComponentDetails.
     *
     * @param componentDetails details
     * @return dto
     */
    private ComponentDetailsDTO createComponentDetailsDto(final ComponentDetails componentDetails) {
        if (componentDetails == null) {
            return null;
        }

        if (componentDetails instanceof FlowChangeExtensionDetails) {
            final ExtensionDetailsDTO processorDetails = new ExtensionDetailsDTO();
            processorDetails.setType(((ExtensionDetails) componentDetails).getType());
            return processorDetails;
        } else if (componentDetails instanceof FlowChangeRemoteProcessGroupDetails) {
            final RemoteProcessGroupDetailsDTO remoteProcessGroupDetails = new RemoteProcessGroupDetailsDTO();
            remoteProcessGroupDetails.setUri(((RemoteProcessGroupDetails) componentDetails).getUri());
            return remoteProcessGroupDetails;
        } else {
            throw new WebApplicationException(new IllegalArgumentException(String.format("Unrecognized type of component details encountered %s during serialization. ", componentDetails.toString())));
        }
    }

    /**
     * Creates a HistoryDTO from the specified History.
     *
     * @param history history
     * @return dto
     */
    public HistoryDTO createHistoryDto(final History history) {
        final HistoryDTO historyDto = new HistoryDTO();
        historyDto.setTotal(history.getTotal());
        historyDto.setLastRefreshed(history.getLastRefreshed());

        if (history.getActions() != null) {
            List<ActionDTO> actionDtos = new ArrayList<>();
            for (Action action : history.getActions()) {
                actionDtos.add(createActionDto(action));
            }
            historyDto.setActions(actionDtos);
        }

        return historyDto;
    }

    /**
     * Creates a ComponentStateDTO for the given component and state's.
     *
     * @param componentId component id
     * @param localState local state
     * @param clusterState cluster state
     * @return dto
     */
    public ComponentStateDTO createComponentStateDTO(final String componentId, final Class<?> componentClass, final StateMap localState, final StateMap clusterState) {
        final ComponentStateDTO dto = new ComponentStateDTO();
        dto.setComponentId(componentId);
        dto.setStateDescription(getStateDescription(componentClass));
        dto.setLocalState(createStateMapDTO(Scope.LOCAL, localState));
        dto.setClusterState(createStateMapDTO(Scope.CLUSTER, clusterState));
        return dto;
    }

    /**
     * Gets the description of the state this component persists.
     *
     * @param componentClass the component class
     * @return state description
     */
    private String getStateDescription(final Class<?> componentClass) {
        final Stateful capabilityDesc = componentClass.getAnnotation(Stateful.class);
        if (capabilityDesc != null) {
            return capabilityDesc.description();
        } else {
            return null;
        }
    }

    /**
     * Creates a StateMapDTO for the given scope and state map.
     *
     * @param scope the scope
     * @param stateMap the state map
     * @return dto
     */
    public StateMapDTO createStateMapDTO(final Scope scope, final StateMap stateMap) {
        if (stateMap == null) {
            return null;
        }

        final StateMapDTO dto = new StateMapDTO();
        dto.setScope(scope.toString());

        final TreeMap<String, String> sortedState = new TreeMap<>(SortedStateUtils.getKeyComparator());
        final Map<String, String> state = stateMap.toMap();
        sortedState.putAll(state);

        int count = 0;
        final List<StateEntryDTO> stateEntries = new ArrayList<>();
        final Set<Map.Entry<String, String>> entrySet = sortedState.entrySet();
        for (final Iterator<Entry<String, String>> iter = entrySet.iterator(); iter.hasNext() && count++ < SortedStateUtils.MAX_COMPONENT_STATE_ENTRIES;) {
            final Map.Entry<String, String> entry = iter.next();
            final StateEntryDTO entryDTO = new StateEntryDTO();
            entryDTO.setKey(entry.getKey());
            entryDTO.setValue(entry.getValue());
            stateEntries.add(entryDTO);
        }
        dto.setTotalEntryCount(state.size());
        dto.setState(stateEntries);

        return dto;
    }

    /**
     * Creates CounterDTOs for each Counter specified.
     *
     * @param counterDtos dtos
     * @return dto
     */
    public CountersSnapshotDTO createCountersDto(final Collection<CounterDTO> counterDtos) {
        final CountersSnapshotDTO dto = new CountersSnapshotDTO();
        dto.setCounters(counterDtos);
        dto.setGenerated(new Date());
        return dto;
    }

    /**
     * Creates a CounterDTO from the specified Counter.
     *
     * @param counter counter
     * @return dto
     */
    public CounterDTO createCounterDto(final Counter counter) {
        final CounterDTO dto = new CounterDTO();
        dto.setId(counter.getIdentifier());
        dto.setContext(counter.getContext());
        dto.setName(counter.getName());
        dto.setValueCount(counter.getValue());
        dto.setValue(FormatUtils.formatCount(counter.getValue()));
        return dto;
    }

    /**
     * Creates a PositionDTO from the specified position
     *
     * @param position position
     * @return dto
     */
    public PositionDTO createPositionDto(final Position position) {
        return new PositionDTO(position.getX(), position.getY());
    }

    private boolean isDropRequestComplete(final DropFlowFileState state) {
        return DropFlowFileState.COMPLETE.equals(state) || DropFlowFileState.CANCELED.equals(state) || DropFlowFileState.FAILURE.equals(state);
    }

    /**
     * Creates a DropRequestDTO from the specified flow file status.
     *
     * @param dropRequest dropRequest
     * @return dto
     */
    public DropRequestDTO createDropRequestDTO(final DropFlowFileStatus dropRequest) {
        final DropRequestDTO dto = new DropRequestDTO();
        dto.setId(dropRequest.getRequestIdentifier());
        dto.setSubmissionTime(new Date(dropRequest.getRequestSubmissionTime()));
        dto.setLastUpdated(new Date(dropRequest.getLastUpdated()));
        dto.setState(dropRequest.getState().toString());
        dto.setFailureReason(dropRequest.getFailureReason());
        dto.setFinished(isDropRequestComplete(dropRequest.getState()));

        final QueueSize dropped = dropRequest.getDroppedSize();
        dto.setDroppedCount(dropped.getObjectCount());
        dto.setDroppedSize(dropped.getByteCount());
        dto.setDropped(FormatUtils.formatCount(dropped.getObjectCount()) + " / " + FormatUtils.formatDataSize(dropped.getByteCount()));

        final QueueSize current = dropRequest.getCurrentSize();
        dto.setCurrentCount(current.getObjectCount());
        dto.setCurrentSize(current.getByteCount());
        dto.setCurrent(FormatUtils.formatCount(current.getObjectCount()) + " / " + FormatUtils.formatDataSize(current.getByteCount()));

        final QueueSize original = dropRequest.getOriginalSize();
        dto.setOriginalCount(original.getObjectCount());
        dto.setOriginalSize(original.getByteCount());
        dto.setOriginal(FormatUtils.formatCount(original.getObjectCount()) + " / " + FormatUtils.formatDataSize(original.getByteCount()));

        if (isDropRequestComplete(dropRequest.getState())) {
            dto.setPercentCompleted(100);
        } else {
            dto.setPercentCompleted((dropped.getObjectCount() * 100) / original.getObjectCount());
        }

        return dto;
    }

    private boolean isListingRequestComplete(final ListFlowFileState state) {
        return ListFlowFileState.COMPLETE.equals(state) || ListFlowFileState.CANCELED.equals(state) || ListFlowFileState.FAILURE.equals(state);
    }

    private QueueSizeDTO createQueueSizeDTO(final QueueSize queueSize) {
        final QueueSizeDTO dto = new QueueSizeDTO();
        dto.setByteCount(queueSize.getByteCount());
        dto.setObjectCount(queueSize.getObjectCount());
        return dto;
    }

    /**
     * Creates a ListingRequestDTO from the specified ListFlowFileStatus.
     *
     * @param listingRequest listingRequest
     * @return dto
     */
    public ListingRequestDTO createListingRequestDTO(final ListFlowFileStatus listingRequest) {
        final ListingRequestDTO dto = new ListingRequestDTO();
        dto.setId(listingRequest.getRequestIdentifier());
        dto.setSubmissionTime(new Date(listingRequest.getRequestSubmissionTime()));
        dto.setLastUpdated(new Date(listingRequest.getLastUpdated()));
        dto.setState(listingRequest.getState().toString());
        dto.setFailureReason(listingRequest.getFailureReason());
        dto.setFinished(isListingRequestComplete(listingRequest.getState()));
        dto.setMaxResults(listingRequest.getMaxResults());
        dto.setPercentCompleted(listingRequest.getCompletionPercentage());

        dto.setQueueSize(createQueueSizeDTO(listingRequest.getQueueSize()));

        if (isListingRequestComplete(listingRequest.getState())) {
            final List<FlowFileSummary> flowFileSummaries = listingRequest.getFlowFileSummaries();
            if (flowFileSummaries != null) {
                final Date now = new Date();
                final List<FlowFileSummaryDTO> summaryDtos = new ArrayList<>(flowFileSummaries.size());
                for (final FlowFileSummary summary : flowFileSummaries) {
                    summaryDtos.add(createFlowFileSummaryDTO(summary, now));
                }
                dto.setFlowFileSummaries(summaryDtos);
            }
        }

        return dto;
    }

    /**
     * Creates a FlowFileSummaryDTO from the specified FlowFileSummary.
     *
     * @param summary summary
     * @return dto
     */
    public FlowFileSummaryDTO createFlowFileSummaryDTO(final FlowFileSummary summary, final Date now) {
        final FlowFileSummaryDTO dto = new FlowFileSummaryDTO();
        dto.setUuid(summary.getUuid());
        dto.setFilename(summary.getFilename());
        dto.setPenalized(summary.isPenalized());
        dto.setPosition(summary.getPosition());
        dto.setSize(summary.getSize());

        final long queuedDuration = now.getTime() - summary.getLastQueuedTime();
        dto.setQueuedDuration(queuedDuration);

        final long age = now.getTime() - summary.getLineageStartDate();
        dto.setLineageDuration(age);

        return dto;
    }

    /**
     * Creates a FlowFileDTO from the specified FlowFileRecord.
     *
     * @param record record
     * @return dto
     */
    public FlowFileDTO createFlowFileDTO(final FlowFileRecord record) {
        final Date now = new Date();
        final FlowFileDTO dto = new FlowFileDTO();
        dto.setUuid(record.getAttribute(CoreAttributes.UUID.key()));
        dto.setFilename(record.getAttribute(CoreAttributes.FILENAME.key()));
        dto.setPenalized(record.isPenalized());
        dto.setSize(record.getSize());
        dto.setAttributes(record.getAttributes());

        final long queuedDuration = now.getTime() - record.getLastQueueDate();
        dto.setQueuedDuration(queuedDuration);

        final long age = now.getTime() - record.getLineageStartDate();
        dto.setLineageDuration(age);

        final ContentClaim contentClaim = record.getContentClaim();
        if (contentClaim != null) {
            final ResourceClaim resourceClaim = contentClaim.getResourceClaim();
            dto.setContentClaimSection(resourceClaim.getSection());
            dto.setContentClaimContainer(resourceClaim.getContainer());
            dto.setContentClaimIdentifier(resourceClaim.getId());
            dto.setContentClaimOffset(contentClaim.getOffset() + record.getContentClaimOffset());
            dto.setContentClaimFileSizeBytes(record.getSize());
            dto.setContentClaimFileSize(FormatUtils.formatDataSize(record.getSize()));
        }

        return dto;
    }

    /**
     * Creates a ConnectionDTO from the specified Connection.
     *
     * @param connection connection
     * @return dto
     */
    public ConnectionDTO createConnectionDto(final Connection connection) {
        if (connection == null) {
            return null;
        }

        final ConnectionDTO dto = new ConnectionDTO();

        dto.setId(connection.getIdentifier());
        dto.setParentGroupId(connection.getProcessGroup().getIdentifier());

        final List<PositionDTO> bendPoints = new ArrayList<>();
        for (final Position bendPoint : connection.getBendPoints()) {
            bendPoints.add(createPositionDto(bendPoint));
        }
        dto.setBends(bendPoints);
        dto.setName(connection.getName());
        dto.setLabelIndex(connection.getLabelIndex());
        dto.setzIndex(connection.getZIndex());
        dto.setSource(createConnectableDto(connection.getSource()));
        dto.setDestination(createConnectableDto(connection.getDestination()));

        dto.setBackPressureObjectThreshold(connection.getFlowFileQueue().getBackPressureObjectThreshold());
        dto.setBackPressureDataSizeThreshold(connection.getFlowFileQueue().getBackPressureDataSizeThreshold());
        dto.setFlowFileExpiration(connection.getFlowFileQueue().getFlowFileExpiration());
        dto.setPrioritizers(new ArrayList<String>());
        for (final FlowFilePrioritizer comparator : connection.getFlowFileQueue().getPriorities()) {
            dto.getPrioritizers().add(comparator.getClass().getCanonicalName());
        }

        // For ports, we do not want to populate the relationships.
        for (final Relationship selectedRelationship : connection.getRelationships()) {
            if (!Relationship.ANONYMOUS.equals(selectedRelationship)) {
                if (dto.getSelectedRelationships() == null) {
                    dto.setSelectedRelationships(new TreeSet<String>(Collator.getInstance(Locale.US)));
                }

                dto.getSelectedRelationships().add(selectedRelationship.getName());
            }
        }

        // For ports, we do not want to populate the relationships.
        for (final Relationship availableRelationship : connection.getSource().getRelationships()) {
            if (!Relationship.ANONYMOUS.equals(availableRelationship)) {
                if (dto.getAvailableRelationships() == null) {
                    dto.setAvailableRelationships(new TreeSet<String>(Collator.getInstance(Locale.US)));
                }

                dto.getAvailableRelationships().add(availableRelationship.getName());
            }
        }

        return dto;
    }

    /**
     * Creates a ConnectableDTO from the specified Connectable.
     *
     * @param connectable connectable
     * @return dto
     */
    public ConnectableDTO createConnectableDto(final Connectable connectable) {
        if (connectable == null) {
            return null;
        }

        final ConnectableDTO dto = new ConnectableDTO();
        dto.setId(connectable.getIdentifier());
        dto.setName(connectable.getName());
        dto.setType(connectable.getConnectableType().name());

        if (connectable instanceof RemoteGroupPort) {
            final RemoteGroupPort remoteGroupPort = (RemoteGroupPort) connectable;
            final RemoteProcessGroup remoteGroup = remoteGroupPort.getRemoteProcessGroup();
            dto.setGroupId(remoteGroup.getIdentifier());
            dto.setRunning(remoteGroupPort.isTargetRunning());
            dto.setTransmitting(remoteGroupPort.isRunning());
            dto.setExists(remoteGroupPort.getTargetExists());
            dto.setComments(remoteGroup.getComments());
        } else {
            dto.setGroupId(connectable.getProcessGroup().getIdentifier());
            dto.setRunning(connectable.isRunning());
            dto.setComments(connectable.getComments());
        }

        return dto;
    }

    /**
     * Creates a LabelDTO from the specified Label.
     *
     * @param label label
     * @return dto
     */
    public LabelDTO createLabelDto(final Label label) {
        if (label == null) {
            return null;
        }

        final LabelDTO dto = new LabelDTO();
        dto.setId(label.getIdentifier());
        dto.setPosition(createPositionDto(label.getPosition()));
        dto.setStyle(label.getStyle());
        dto.setHeight(label.getSize().getHeight());
        dto.setWidth(label.getSize().getWidth());
        dto.setLabel(label.getValue());
        dto.setParentGroupId(label.getProcessGroup().getIdentifier());

        return dto;
    }

    /**
     * Creates a FunnelDTO from the specified Funnel.
     *
     * @param funnel funnel
     * @return dto
     */
    public FunnelDTO createFunnelDto(final Funnel funnel) {
        if (funnel == null) {
            return null;
        }

        final FunnelDTO dto = new FunnelDTO();
        dto.setId(funnel.getIdentifier());
        dto.setPosition(createPositionDto(funnel.getPosition()));
        dto.setParentGroupId(funnel.getProcessGroup().getIdentifier());

        return dto;
    }

    /**
     * Creates a SnippetDTO from the specified Snippet.
     *
     * @param snippet snippet
     * @return dto
     */
    public SnippetDTO createSnippetDto(final Snippet snippet) {
        final SnippetDTO dto = new SnippetDTO();
        dto.setId(snippet.getId());
        dto.setParentGroupId(snippet.getParentGroupId());
        dto.setLinked(snippet.isLinked());

        // populate the snippet contents ids
        dto.setConnections(mapRevisionToDto(snippet.getConnections()));
        dto.setFunnels(mapRevisionToDto(snippet.getFunnels()));
        dto.setInputPorts(mapRevisionToDto(snippet.getInputPorts()));
        dto.setLabels(mapRevisionToDto(snippet.getLabels()));
        dto.setOutputPorts(mapRevisionToDto(snippet.getOutputPorts()));
        dto.setProcessGroups(mapRevisionToDto(snippet.getProcessGroups()));
        dto.setProcessors(mapRevisionToDto(snippet.getProcessors()));
        dto.setRemoteProcessGroups(mapRevisionToDto(snippet.getRemoteProcessGroups()));

        return dto;
    }

    private Map<String, RevisionDTO> mapRevisionToDto(final Map<String, Revision> revisionMap) {
        final Map<String, RevisionDTO> dtos = new HashMap<>(revisionMap.size());
        for (final Map.Entry<String, Revision> entry : revisionMap.entrySet()) {
            final Revision revision = entry.getValue();
            final RevisionDTO revisionDto = new RevisionDTO();
            revisionDto.setClientId(revision.getClientId());
            revisionDto.setVersion(revision.getVersion());

            dtos.put(entry.getKey(), revisionDto);
        }
        return dtos;
    }

    /**
     * Creates a TemplateDTO from the specified template.
     *
     * @param template template
     * @return dto
     */
    public TemplateDTO createTemplateDTO(final Template template) {
        if (template == null) {
            return null;
        }

        final TemplateDTO original = template.getDetails();

        final TemplateDTO copy = new TemplateDTO();
        copy.setId(original.getId());
        copy.setName(original.getName());
        copy.setDescription(original.getDescription());
        copy.setTimestamp(original.getTimestamp());
        copy.setUri(original.getUri());
        return copy;
    }


    public RemoteProcessGroupStatusDTO createRemoteProcessGroupStatusDto(final RemoteProcessGroupStatus remoteProcessGroupStatus) {
        final RemoteProcessGroupStatusDTO dto = new RemoteProcessGroupStatusDTO();
        dto.setId(remoteProcessGroupStatus.getId());
        dto.setGroupId(remoteProcessGroupStatus.getGroupId());
        dto.setTargetUri(remoteProcessGroupStatus.getTargetUri());
        dto.setName(remoteProcessGroupStatus.getName());
        dto.setTransmissionStatus(remoteProcessGroupStatus.getTransmissionStatus().toString());
        dto.setStatsLastRefreshed(new Date());

        final RemoteProcessGroupStatusSnapshotDTO snapshot = new RemoteProcessGroupStatusSnapshotDTO();
        dto.setAggregateSnapshot(snapshot);

        snapshot.setId(remoteProcessGroupStatus.getId());
        snapshot.setGroupId(remoteProcessGroupStatus.getGroupId());
        snapshot.setName(remoteProcessGroupStatus.getName());
        snapshot.setTargetUri(remoteProcessGroupStatus.getTargetUri());
        snapshot.setTransmissionStatus(remoteProcessGroupStatus.getTransmissionStatus().toString());

        snapshot.setActiveThreadCount(remoteProcessGroupStatus.getActiveThreadCount());
        snapshot.setFlowFilesSent(remoteProcessGroupStatus.getSentCount());
        snapshot.setBytesSent(remoteProcessGroupStatus.getSentContentSize());
        snapshot.setFlowFilesReceived(remoteProcessGroupStatus.getReceivedCount());
        snapshot.setBytesReceived(remoteProcessGroupStatus.getReceivedContentSize());
        snapshot.setAuthorizationIssues(remoteProcessGroupStatus.getAuthorizationIssues());

        StatusMerger.updatePrettyPrintedFields(snapshot);
        return dto;
    }

    public ProcessGroupStatusDTO createConciseProcessGroupStatusDto(final ProcessGroupStatus processGroupStatus) {
        final ProcessGroupStatusDTO processGroupStatusDto = new ProcessGroupStatusDTO();
        processGroupStatusDto.setId(processGroupStatus.getId());
        processGroupStatusDto.setName(processGroupStatus.getName());
        processGroupStatusDto.setStatsLastRefreshed(new Date());

        final ProcessGroupStatusSnapshotDTO snapshot = new ProcessGroupStatusSnapshotDTO();
        processGroupStatusDto.setAggregateSnapshot(snapshot);

        snapshot.setId(processGroupStatus.getId());
        snapshot.setName(processGroupStatus.getName());

        snapshot.setFlowFilesQueued(processGroupStatus.getQueuedCount());
        snapshot.setBytesQueued(processGroupStatus.getQueuedContentSize());
        snapshot.setBytesRead(processGroupStatus.getBytesRead());
        snapshot.setBytesWritten(processGroupStatus.getBytesWritten());
        snapshot.setFlowFilesIn(processGroupStatus.getInputCount());
        snapshot.setBytesIn(processGroupStatus.getInputContentSize());
        snapshot.setFlowFilesOut(processGroupStatus.getOutputCount());
        snapshot.setBytesOut(processGroupStatus.getOutputContentSize());
        snapshot.setFlowFilesTransferred(processGroupStatus.getFlowFilesTransferred());
        snapshot.setBytesTransferred(processGroupStatus.getBytesTransferred());
        snapshot.setFlowFilesSent(processGroupStatus.getFlowFilesSent());
        snapshot.setBytesSent(processGroupStatus.getBytesSent());
        snapshot.setFlowFilesReceived(processGroupStatus.getFlowFilesReceived());
        snapshot.setBytesReceived(processGroupStatus.getBytesReceived());
        snapshot.setActiveThreadCount(processGroupStatus.getActiveThreadCount());
        StatusMerger.updatePrettyPrintedFields(snapshot);
        return processGroupStatusDto;
    }

    public ProcessGroupStatusDTO createProcessGroupStatusDto(final ProcessGroupStatus processGroupStatus) {
        final ProcessGroupStatusDTO processGroupStatusDto = createConciseProcessGroupStatusDto(processGroupStatus);
        final ProcessGroupStatusSnapshotDTO snapshot = processGroupStatusDto.getAggregateSnapshot();

        // processor status
        final Collection<ProcessorStatusSnapshotDTO> processorStatDtoCollection = new ArrayList<>();
        snapshot.setProcessorStatusSnapshots(processorStatDtoCollection);
        final Collection<ProcessorStatus> processorStatusCollection = processGroupStatus.getProcessorStatus();
        if (processorStatusCollection != null) {
            for (final ProcessorStatus processorStatus : processorStatusCollection) {
                final ProcessorStatusDTO processorStatusDto = createProcessorStatusDto(processorStatus);
                processorStatDtoCollection.add(processorStatusDto.getAggregateSnapshot());
            }
        }

        // connection status
        final Collection<ConnectionStatusSnapshotDTO> connectionStatusDtoCollection = new ArrayList<>();
        snapshot.setConnectionStatusSnapshots(connectionStatusDtoCollection);
        final Collection<ConnectionStatus> connectionStatusCollection = processGroupStatus.getConnectionStatus();
        if (connectionStatusCollection != null) {
            for (final ConnectionStatus connectionStatus : connectionStatusCollection) {
                final ConnectionStatusDTO connectionStatusDto = createConnectionStatusDto(connectionStatus);
                connectionStatusDtoCollection.add(connectionStatusDto.getAggregateSnapshot());
            }
        }

        // local child process groups
        final Collection<ProcessGroupStatusSnapshotDTO> childProcessGroupStatusDtoCollection = new ArrayList<>();
        snapshot.setProcessGroupStatusSnapshots(childProcessGroupStatusDtoCollection);
        final Collection<ProcessGroupStatus> childProcessGroupStatusCollection = processGroupStatus.getProcessGroupStatus();
        if (childProcessGroupStatusCollection != null) {
            for (final ProcessGroupStatus childProcessGroupStatus : childProcessGroupStatusCollection) {
                final ProcessGroupStatusDTO childProcessGroupStatusDto = createProcessGroupStatusDto(childProcessGroupStatus);
                childProcessGroupStatusDtoCollection.add(childProcessGroupStatusDto.getAggregateSnapshot());
            }
        }

        // remote child process groups
        final Collection<RemoteProcessGroupStatusSnapshotDTO> childRemoteProcessGroupStatusDtoCollection = new ArrayList<>();
        snapshot.setRemoteProcessGroupStatusSnapshots(childRemoteProcessGroupStatusDtoCollection);
        final Collection<RemoteProcessGroupStatus> childRemoteProcessGroupStatusCollection = processGroupStatus.getRemoteProcessGroupStatus();
        if (childRemoteProcessGroupStatusCollection != null) {
            for (final RemoteProcessGroupStatus childRemoteProcessGroupStatus : childRemoteProcessGroupStatusCollection) {
                final RemoteProcessGroupStatusDTO childRemoteProcessGroupStatusDto = createRemoteProcessGroupStatusDto(childRemoteProcessGroupStatus);
                childRemoteProcessGroupStatusDtoCollection.add(childRemoteProcessGroupStatusDto.getAggregateSnapshot());
            }
        }

        // input ports
        final Collection<PortStatusSnapshotDTO> inputPortStatusDtoCollection = new ArrayList<>();
        snapshot.setInputPortStatusSnapshots(inputPortStatusDtoCollection);
        final Collection<PortStatus> inputPortStatusCollection = processGroupStatus.getInputPortStatus();
        if (inputPortStatusCollection != null) {
            for (final PortStatus portStatus : inputPortStatusCollection) {
                final PortStatusDTO portStatusDto = createPortStatusDto(portStatus);
                inputPortStatusDtoCollection.add(portStatusDto.getAggregateSnapshot());
            }
        }

        // output ports
        final Collection<PortStatusSnapshotDTO> outputPortStatusDtoCollection = new ArrayList<>();
        snapshot.setOutputPortStatusSnapshots(outputPortStatusDtoCollection);
        final Collection<PortStatus> outputPortStatusCollection = processGroupStatus.getOutputPortStatus();
        if (outputPortStatusCollection != null) {
            for (final PortStatus portStatus : outputPortStatusCollection) {
                final PortStatusDTO portStatusDto = createPortStatusDto(portStatus);
                outputPortStatusDtoCollection.add(portStatusDto.getAggregateSnapshot());
            }
        }

        return processGroupStatusDto;
    }

    public ConnectionStatusDTO createConnectionStatusDto(final ConnectionStatus connectionStatus) {
        final ConnectionStatusDTO connectionStatusDto = new ConnectionStatusDTO();
        connectionStatusDto.setGroupId(connectionStatus.getGroupId());
        connectionStatusDto.setId(connectionStatus.getId());
        connectionStatusDto.setName(connectionStatus.getName());
        connectionStatusDto.setSourceId(connectionStatus.getSourceId());
        connectionStatusDto.setSourceName(connectionStatus.getSourceName());
        connectionStatusDto.setDestinationId(connectionStatus.getDestinationId());
        connectionStatusDto.setDestinationName(connectionStatus.getDestinationName());
        connectionStatusDto.setStatsLastRefreshed(new Date());

        final ConnectionStatusSnapshotDTO snapshot = new ConnectionStatusSnapshotDTO();
        connectionStatusDto.setAggregateSnapshot(snapshot);

        snapshot.setId(connectionStatus.getId());
        snapshot.setGroupId(connectionStatus.getGroupId());
        snapshot.setName(connectionStatus.getName());
        snapshot.setSourceName(connectionStatus.getSourceName());
        snapshot.setDestinationName(connectionStatus.getDestinationName());

        snapshot.setFlowFilesQueued(connectionStatus.getQueuedCount());
        snapshot.setBytesQueued(connectionStatus.getQueuedBytes());

        snapshot.setFlowFilesIn(connectionStatus.getInputCount());
        snapshot.setBytesIn(connectionStatus.getInputBytes());

        snapshot.setFlowFilesOut(connectionStatus.getOutputCount());
        snapshot.setBytesOut(connectionStatus.getOutputBytes());
        StatusMerger.updatePrettyPrintedFields(snapshot);

        return connectionStatusDto;
    }

    public ProcessorStatusDTO createProcessorStatusDto(final ProcessorStatus procStatus) {
        final ProcessorStatusDTO dto = new ProcessorStatusDTO();
        dto.setId(procStatus.getId());
        dto.setGroupId(procStatus.getGroupId());
        dto.setName(procStatus.getName());
        dto.setStatsLastRefreshed(new Date());

        final ProcessorStatusSnapshotDTO snapshot = new ProcessorStatusSnapshotDTO();
        dto.setAggregateSnapshot(snapshot);

        snapshot.setId(procStatus.getId());
        snapshot.setGroupId(procStatus.getGroupId());
        snapshot.setName(procStatus.getName());

        snapshot.setFlowFilesOut(procStatus.getOutputCount());
        snapshot.setBytesOut(procStatus.getOutputBytes());

        snapshot.setFlowFilesIn(procStatus.getInputCount());
        snapshot.setBytesIn(procStatus.getInputBytes());

        snapshot.setBytesRead(procStatus.getBytesRead());
        snapshot.setBytesWritten(procStatus.getBytesWritten());

        snapshot.setTaskCount(procStatus.getInvocations());
        snapshot.setTasksDurationNanos(procStatus.getProcessingNanos());
        snapshot.setTasksDuration(FormatUtils.formatHoursMinutesSeconds(procStatus.getProcessingNanos(), TimeUnit.NANOSECONDS));

        // determine the run status
        snapshot.setRunStatus(procStatus.getRunStatus().toString());

        snapshot.setActiveThreadCount(procStatus.getActiveThreadCount());
        snapshot.setType(procStatus.getType());

        StatusMerger.updatePrettyPrintedFields(snapshot);
        return dto;
    }

    /**
     * Creates a PortStatusDTO for the specified PortStatus.
     *
     * @param portStatus status
     * @return dto
     */
    public PortStatusDTO createPortStatusDto(final PortStatus portStatus) {
        final PortStatusDTO dto = new PortStatusDTO();
        dto.setId(portStatus.getId());
        dto.setGroupId(portStatus.getGroupId());
        dto.setName(portStatus.getName());
        dto.setRunStatus(portStatus.getRunStatus().toString());
        dto.setTransmitting(portStatus.isTransmitting());
        dto.setStatsLastRefreshed(new Date());

        final PortStatusSnapshotDTO snapshot = new PortStatusSnapshotDTO();
        dto.setAggregateSnapshot(snapshot);

        snapshot.setId(portStatus.getId());
        snapshot.setGroupId(portStatus.getGroupId());
        snapshot.setName(portStatus.getName());
        snapshot.setRunStatus(portStatus.getRunStatus().toString());

        snapshot.setActiveThreadCount(portStatus.getActiveThreadCount());
        snapshot.setFlowFilesOut(portStatus.getOutputCount());
        snapshot.setBytesOut(portStatus.getOutputBytes());

        snapshot.setFlowFilesIn(portStatus.getInputCount());
        snapshot.setBytesIn(portStatus.getInputBytes());
        StatusMerger.updatePrettyPrintedFields(snapshot);

        return dto;
    }

    /**
     * Copies the specified snippet.
     *
     * @param originalSnippet snippet
     * @return dto
     */
    public FlowSnippetDTO copySnippetContents(FlowSnippetDTO originalSnippet) {
        final FlowSnippetDTO copySnippet = new FlowSnippetDTO();

        if (originalSnippet.getConnections() != null) {
            for (final ConnectionDTO connection : originalSnippet.getConnections()) {
                copySnippet.getConnections().add(copy(connection));
            }
        }
        if (originalSnippet.getInputPorts() != null) {
            for (final PortDTO port : originalSnippet.getInputPorts()) {
                copySnippet.getInputPorts().add(copy(port));
            }
        }
        if (originalSnippet.getOutputPorts() != null) {
            for (final PortDTO port : originalSnippet.getOutputPorts()) {
                copySnippet.getOutputPorts().add(copy(port));
            }
        }
        if (originalSnippet.getProcessGroups() != null) {
            for (final ProcessGroupDTO processGroup : originalSnippet.getProcessGroups()) {
                copySnippet.getProcessGroups().add(copy(processGroup, true));
            }
        }
        if (originalSnippet.getProcessors() != null) {
            for (final ProcessorDTO processor : originalSnippet.getProcessors()) {
                copySnippet.getProcessors().add(copy(processor));
            }
        }
        if (originalSnippet.getLabels() != null) {
            for (final LabelDTO label : originalSnippet.getLabels()) {
                copySnippet.getLabels().add(copy(label));
            }
        }
        if (originalSnippet.getFunnels() != null) {
            for (final FunnelDTO funnel : originalSnippet.getFunnels()) {
                copySnippet.getFunnels().add(copy(funnel));
            }
        }
        if (originalSnippet.getRemoteProcessGroups() != null) {
            for (final RemoteProcessGroupDTO remoteGroup : originalSnippet.getRemoteProcessGroups()) {
                copySnippet.getRemoteProcessGroups().add(copy(remoteGroup));
            }
        }
        if (originalSnippet.getControllerServices() != null) {
            for (final ControllerServiceDTO controllerService : originalSnippet.getControllerServices()) {
                copySnippet.getControllerServices().add(copy(controllerService));
            }
        }

        return copySnippet;
    }

    /**
     * Creates a PortDTO from the specified Port.
     *
     * @param port port
     * @return dto
     */
    public PortDTO createPortDto(final Port port) {
        if (port == null) {
            return null;
        }

        final PortDTO dto = new PortDTO();
        dto.setId(port.getIdentifier());
        dto.setPosition(createPositionDto(port.getPosition()));
        dto.setName(port.getName());
        dto.setComments(port.getComments());
        dto.setConcurrentlySchedulableTaskCount(port.getMaxConcurrentTasks());
        dto.setParentGroupId(port.getProcessGroup().getIdentifier());
        dto.setState(port.getScheduledState().toString());
        dto.setType(port.getConnectableType().name());

        // if this port is on the root group, determine if its actually connected to another nifi
        if (port instanceof RootGroupPort) {
            final RootGroupPort rootGroupPort = (RootGroupPort) port;
            dto.setTransmitting(rootGroupPort.isTransmitting());
            dto.setGroupAccessControl(rootGroupPort.getGroupAccessControl());
            dto.setUserAccessControl(rootGroupPort.getUserAccessControl());
        }

        final Collection<ValidationResult> validationErrors = port.getValidationErrors();
        if (validationErrors != null && !validationErrors.isEmpty()) {
            final List<String> errors = new ArrayList<>();
            for (final ValidationResult validationResult : validationErrors) {
                errors.add(validationResult.toString());
            }

            dto.setValidationErrors(errors);
        }

        return dto;
    }

    public ReportingTaskDTO createReportingTaskDto(final ReportingTaskNode reportingTaskNode) {
        final ReportingTaskDTO dto = new ReportingTaskDTO();
        dto.setId(reportingTaskNode.getIdentifier());
        dto.setName(reportingTaskNode.getName());
        dto.setType(reportingTaskNode.getReportingTask().getClass().getName());
        dto.setSchedulingStrategy(reportingTaskNode.getSchedulingStrategy().name());
        dto.setSchedulingPeriod(reportingTaskNode.getSchedulingPeriod());
        dto.setState(reportingTaskNode.getScheduledState().name());
        dto.setActiveThreadCount(reportingTaskNode.getActiveThreadCount());
        dto.setAnnotationData(reportingTaskNode.getAnnotationData());
        dto.setComments(reportingTaskNode.getComments());
        dto.setPersistsState(reportingTaskNode.getReportingTask().getClass().isAnnotationPresent(Stateful.class));

        final Map<String, String> defaultSchedulingPeriod = new HashMap<>();
        defaultSchedulingPeriod.put(SchedulingStrategy.TIMER_DRIVEN.name(), SchedulingStrategy.TIMER_DRIVEN.getDefaultSchedulingPeriod());
        defaultSchedulingPeriod.put(SchedulingStrategy.CRON_DRIVEN.name(), SchedulingStrategy.CRON_DRIVEN.getDefaultSchedulingPeriod());
        dto.setDefaultSchedulingPeriod(defaultSchedulingPeriod);

        // sort a copy of the properties
        final Map<PropertyDescriptor, String> sortedProperties = new TreeMap<>(new Comparator<PropertyDescriptor>() {
            @Override
            public int compare(PropertyDescriptor o1, PropertyDescriptor o2) {
                return Collator.getInstance(Locale.US).compare(o1.getName(), o2.getName());
            }
        });
        sortedProperties.putAll(reportingTaskNode.getProperties());

        // get the property order from the reporting task
        final ReportingTask reportingTask = reportingTaskNode.getReportingTask();
        final Map<PropertyDescriptor, String> orderedProperties = new LinkedHashMap<>();
        final List<PropertyDescriptor> descriptors = reportingTask.getPropertyDescriptors();
        if (descriptors != null && !descriptors.isEmpty()) {
            for (PropertyDescriptor descriptor : descriptors) {
                orderedProperties.put(descriptor, null);
            }
        }
        orderedProperties.putAll(sortedProperties);

        // build the descriptor and property dtos
        dto.setDescriptors(new LinkedHashMap<String, PropertyDescriptorDTO>());
        dto.setProperties(new LinkedHashMap<String, String>());
        for (final Map.Entry<PropertyDescriptor, String> entry : orderedProperties.entrySet()) {
            final PropertyDescriptor descriptor = entry.getKey();

            // store the property descriptor
            dto.getDescriptors().put(descriptor.getName(), createPropertyDescriptorDto(descriptor, "root"));

            // determine the property value - don't include sensitive properties
            String propertyValue = entry.getValue();
            if (propertyValue != null && descriptor.isSensitive()) {
                propertyValue = "********";
            }

            // set the property value
            dto.getProperties().put(descriptor.getName(), propertyValue);
        }

        // add the validation errors
        final Collection<ValidationResult> validationErrors = reportingTaskNode.getValidationErrors();
        if (validationErrors != null && !validationErrors.isEmpty()) {
            final List<String> errors = new ArrayList<>();
            for (final ValidationResult validationResult : validationErrors) {
                errors.add(validationResult.toString());
            }

            dto.setValidationErrors(errors);
        }

        return dto;
    }

    public ControllerServiceDTO createControllerServiceDto(final ControllerServiceNode controllerServiceNode) {
        final ControllerServiceDTO dto = new ControllerServiceDTO();
        dto.setId(controllerServiceNode.getIdentifier());
        dto.setName(controllerServiceNode.getName());
        dto.setType(controllerServiceNode.getControllerServiceImplementation().getClass().getName());
        dto.setState(controllerServiceNode.getState().name());
        dto.setAnnotationData(controllerServiceNode.getAnnotationData());
        dto.setComments(controllerServiceNode.getComments());
        dto.setPersistsState(controllerServiceNode.getControllerServiceImplementation().getClass().isAnnotationPresent(Stateful.class));

        // sort a copy of the properties
        final Map<PropertyDescriptor, String> sortedProperties = new TreeMap<>(new Comparator<PropertyDescriptor>() {
            @Override
            public int compare(PropertyDescriptor o1, PropertyDescriptor o2) {
                return Collator.getInstance(Locale.US).compare(o1.getName(), o2.getName());
            }
        });
        sortedProperties.putAll(controllerServiceNode.getProperties());

        // get the property order from the controller service
        final ControllerService controllerService = controllerServiceNode.getControllerServiceImplementation();
        final Map<PropertyDescriptor, String> orderedProperties = new LinkedHashMap<>();
        final List<PropertyDescriptor> descriptors = controllerService.getPropertyDescriptors();
        if (descriptors != null && !descriptors.isEmpty()) {
            for (PropertyDescriptor descriptor : descriptors) {
                orderedProperties.put(descriptor, null);
            }
        }
        orderedProperties.putAll(sortedProperties);

        // build the descriptor and property dtos
        dto.setDescriptors(new LinkedHashMap<String, PropertyDescriptorDTO>());
        dto.setProperties(new LinkedHashMap<String, String>());
        for (final Map.Entry<PropertyDescriptor, String> entry : orderedProperties.entrySet()) {
            final PropertyDescriptor descriptor = entry.getKey();

            // store the property descriptor
            dto.getDescriptors().put(descriptor.getName(), createPropertyDescriptorDto(descriptor, controllerServiceNode.getProcessGroup().getIdentifier()));

            // determine the property value - don't include sensitive properties
            String propertyValue = entry.getValue();
            if (propertyValue != null && descriptor.isSensitive()) {
                propertyValue = "********";
            }

            // set the property value
            dto.getProperties().put(descriptor.getName(), propertyValue);
        }

        // add the validation errors
        final Collection<ValidationResult> validationErrors = controllerServiceNode.getValidationErrors();
        if (validationErrors != null && !validationErrors.isEmpty()) {
            final List<String> errors = new ArrayList<>();
            for (final ValidationResult validationResult : validationErrors) {
                errors.add(validationResult.toString());
            }

            dto.setValidationErrors(errors);
        }

        return dto;
    }

    public ControllerServiceReferencingComponentDTO createControllerServiceReferencingComponentDTO(final ConfiguredComponent component) {
        final ControllerServiceReferencingComponentDTO dto = new ControllerServiceReferencingComponentDTO();
        dto.setId(component.getIdentifier());
        dto.setName(component.getName());

        String processGroupId = null;
        List<PropertyDescriptor> propertyDescriptors = null;
        Collection<ValidationResult> validationErrors = null;
        if (component instanceof ProcessorNode) {
            final ProcessorNode node = ((ProcessorNode) component);
            dto.setGroupId(node.getProcessGroup().getIdentifier());
            dto.setState(node.getScheduledState().name());
            dto.setActiveThreadCount(node.getActiveThreadCount());
            dto.setType(node.getProcessor().getClass().getName());
            dto.setReferenceType(Processor.class.getSimpleName());

            propertyDescriptors = node.getProcessor().getPropertyDescriptors();
            validationErrors = node.getValidationErrors();
            processGroupId = node.getProcessGroup().getIdentifier();
        } else if (component instanceof ControllerServiceNode) {
            final ControllerServiceNode node = ((ControllerServiceNode) component);
            dto.setState(node.getState().name());
            dto.setType(node.getControllerServiceImplementation().getClass().getName());
            dto.setReferenceType(ControllerService.class.getSimpleName());

            propertyDescriptors = node.getControllerServiceImplementation().getPropertyDescriptors();
            validationErrors = node.getValidationErrors();
            processGroupId = node.getProcessGroup().getIdentifier();
        } else if (component instanceof ReportingTaskNode) {
            final ReportingTaskNode node = ((ReportingTaskNode) component);
            dto.setState(node.getScheduledState().name());
            dto.setActiveThreadCount(node.getActiveThreadCount());
            dto.setType(node.getReportingTask().getClass().getName());
            dto.setReferenceType(ReportingTask.class.getSimpleName());

            propertyDescriptors = node.getReportingTask().getPropertyDescriptors();
            validationErrors = node.getValidationErrors();
            processGroupId = "root";
        }

        if (propertyDescriptors != null && !propertyDescriptors.isEmpty()) {
            final Map<PropertyDescriptor, String> sortedProperties = new TreeMap<>(new Comparator<PropertyDescriptor>() {
                @Override
                public int compare(PropertyDescriptor o1, PropertyDescriptor o2) {
                    return Collator.getInstance(Locale.US).compare(o1.getName(), o2.getName());
                }
            });
            sortedProperties.putAll(component.getProperties());

            final Map<PropertyDescriptor, String> orderedProperties = new LinkedHashMap<>();
            for (PropertyDescriptor descriptor : propertyDescriptors) {
                orderedProperties.put(descriptor, null);
            }
            orderedProperties.putAll(sortedProperties);

            // build the descriptor and property dtos
            dto.setDescriptors(new LinkedHashMap<String, PropertyDescriptorDTO>());
            dto.setProperties(new LinkedHashMap<String, String>());
            for (final Map.Entry<PropertyDescriptor, String> entry : orderedProperties.entrySet()) {
                final PropertyDescriptor descriptor = entry.getKey();

                // store the property descriptor
                dto.getDescriptors().put(descriptor.getName(), createPropertyDescriptorDto(descriptor, processGroupId));

                // determine the property value - don't include sensitive properties
                String propertyValue = entry.getValue();
                if (propertyValue != null && descriptor.isSensitive()) {
                    propertyValue = "********";
                }

                // set the property value
                dto.getProperties().put(descriptor.getName(), propertyValue);
            }
        }

        if (validationErrors != null && !validationErrors.isEmpty()) {
            final List<String> errors = new ArrayList<>();
            for (final ValidationResult validationResult : validationErrors) {
                errors.add(validationResult.toString());
            }

            dto.setValidationErrors(errors);
        }

        return dto;
    }

    public RemoteProcessGroupPortDTO createRemoteProcessGroupPortDto(final RemoteGroupPort port) {
        if (port == null) {
            return null;
        }

        final RemoteProcessGroupPortDTO dto = new RemoteProcessGroupPortDTO();
        dto.setId(port.getIdentifier());
        dto.setName(port.getName());
        dto.setComments(port.getComments());
        dto.setTransmitting(port.isRunning());
        dto.setTargetRunning(port.isTargetRunning());
        dto.setConcurrentlySchedulableTaskCount(port.getMaxConcurrentTasks());
        dto.setUseCompression(port.isUseCompression());
        dto.setExists(port.getTargetExists());

        // determine if this port is currently connected to another component locally
        if (ConnectableType.REMOTE_OUTPUT_PORT.equals(port.getConnectableType())) {
            dto.setConnected(!port.getConnections().isEmpty());
        } else {
            dto.setConnected(port.hasIncomingConnection());
        }

        return dto;
    }

    /**
     * Creates a RemoteProcessGroupDTO from the specified RemoteProcessGroup.
     *
     * @param group group
     * @return dto
     */
    public RemoteProcessGroupDTO createRemoteProcessGroupDto(final RemoteProcessGroup group) {
        if (group == null) {
            return null;
        }

        final Set<RemoteProcessGroupPortDTO> inputPorts = new TreeSet<>(new DtoFactory.SortedRemoteGroupPortComparator());
        final Set<RemoteProcessGroupPortDTO> outputPorts = new TreeSet<>(new DtoFactory.SortedRemoteGroupPortComparator());

        int activeRemoteInputPortCount = 0;
        int inactiveRemoteInputPortCount = 0;
        for (final Port port : group.getInputPorts()) {
            inputPorts.add(createRemoteProcessGroupPortDto((RemoteGroupPort) port));

            if (port.hasIncomingConnection()) {
                if (port.isRunning()) {
                    activeRemoteInputPortCount++;
                } else {
                    inactiveRemoteInputPortCount++;
                }
            }
        }

        int activeRemoteOutputPortCount = 0;
        int inactiveRemoteOutputPortCount = 0;
        for (final Port port : group.getOutputPorts()) {
            outputPorts.add(createRemoteProcessGroupPortDto((RemoteGroupPort) port));

            if (!port.getConnections().isEmpty()) {
                if (port.isRunning()) {
                    activeRemoteOutputPortCount++;
                } else {
                    inactiveRemoteOutputPortCount++;
                }
            }
        }

        final RemoteProcessGroupContentsDTO contents = new RemoteProcessGroupContentsDTO();
        contents.setInputPorts(inputPorts);
        contents.setOutputPorts(outputPorts);

        final RemoteProcessGroupDTO dto = new RemoteProcessGroupDTO();
        dto.setId(group.getIdentifier());
        dto.setName(group.getName());
        dto.setPosition(createPositionDto(group.getPosition()));
        dto.setComments(group.getComments());
        dto.setTransmitting(group.isTransmitting());
        dto.setCommunicationsTimeout(group.getCommunicationsTimeout());
        dto.setYieldDuration(group.getYieldDuration());
        dto.setParentGroupId(group.getProcessGroup().getIdentifier());
        dto.setTargetUri(group.getTargetUri().toString());
        dto.setFlowRefreshed(group.getLastRefreshTime());
        dto.setContents(contents);

        // only specify the secure flag if we know the target system has site to site enabled
        if (group.isSiteToSiteEnabled()) {
            dto.setTargetSecure(group.getSecureFlag());
        }

        if (group.getAuthorizationIssue() != null) {
            dto.setAuthorizationIssues(Arrays.asList(group.getAuthorizationIssue()));
        }

        dto.setActiveRemoteInputPortCount(activeRemoteInputPortCount);
        dto.setInactiveRemoteInputPortCount(inactiveRemoteInputPortCount);
        dto.setActiveRemoteOutputPortCount(activeRemoteOutputPortCount);
        dto.setInactiveRemoteOutputPortCount(inactiveRemoteOutputPortCount);

        final ProcessGroupCounts counts = group.getCounts();
        if (counts != null) {
            dto.setInputPortCount(counts.getInputPortCount());
            dto.setOutputPortCount(counts.getOutputPortCount());
        }

        return dto;
    }

    /**
     * Creates a FlowBreadcrumbDTO from the specified parent ProcessGroup.
     *
     * @param parentGroup group
     * @return dto
     */
    private FlowBreadcrumbDTO createBreadcrumbDto(final ProcessGroup parentGroup) {
        if (parentGroup == null) {
            return null;
        }

        final FlowBreadcrumbDTO dto = new FlowBreadcrumbDTO();
        dto.setId(parentGroup.getIdentifier());
        dto.setName(parentGroup.getName());

        if (parentGroup.getParent() != null) {
            dto.setParentBreadcrumb(createBreadcrumbDto(parentGroup.getParent()));
        }

        return dto;
    }

    public AccessPolicyDTO createAccessPolicyDto(final Authorizable authorizable) {
        final AccessPolicyDTO dto = new AccessPolicyDTO();
        dto.setCanRead(authorizable.isAuthorized(authorizer, RequestAction.READ));
        dto.setCanWrite(authorizable.isAuthorized(authorizer, RequestAction.WRITE));
        return dto;
    }

    /**
     * Creates a ProcessGroupDTO from the specified ProcessGroup.
     *
     * @param group group
     * @return dto
     */
    public ProcessGroupDTO createProcessGroupDto(final ProcessGroup group) {
        return createProcessGroupDto(group, false);
    }

    public ProcessGroupFlowDTO createProcessGroupFlowDto(final ProcessGroup group, final ProcessGroupStatus groupStatus, final RevisionManager revisionManager) {
        final ProcessGroupFlowDTO dto = new ProcessGroupFlowDTO();
        dto.setId(group.getIdentifier());
        dto.setLastRefreshed(new Date());
        dto.setBreadcrumb(createBreadcrumbDto(group));
        dto.setFlow(createFlowDto(group, groupStatus, revisionManager));

        final ProcessGroup parent = group.getParent();
        if (parent != null) {
            dto.setParentGroupId(parent.getIdentifier());
        }

        return dto;
    }

    public FlowDTO createFlowDto(final ProcessGroup group, final ProcessGroupStatus groupStatus, final FlowSnippetDTO snippet, final RevisionManager revisionManager) {
        if (snippet == null) {
            return null;
        }

        final FlowDTO flow = new FlowDTO();

        for (final ConnectionDTO connection : snippet.getConnections()) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(connection.getId()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(group.getConnection(connection.getId()));
            final ConnectionStatusDTO status = getComponentStatus(
                () -> groupStatus.getConnectionStatus().stream().filter(connectionStatus -> connection.getId().equals(connectionStatus.getId())).findFirst().orElse(null),
                connectionStatus -> createConnectionStatusDto(connectionStatus)
            );
            flow.getConnections().add(entityFactory.createConnectionEntity(connection, null, accessPolicy, status));
        }

        for (final ControllerServiceDTO controllerService : snippet.getControllerServices()) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(controllerService.getId()));
            flow.getControllerServices().add(entityFactory.createControllerServiceEntity(controllerService, revision, null));
        }

        for (final FunnelDTO funnel : snippet.getFunnels()) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(funnel.getId()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(group.getFunnel(funnel.getId()));
            flow.getFunnels().add(entityFactory.createFunnelEntity(funnel, revision, accessPolicy));
        }

        for (final PortDTO inputPort : snippet.getInputPorts()) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(inputPort.getId()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(group.getInputPort(inputPort.getId()));
            final PortStatusDTO status = getComponentStatus(
                () -> groupStatus.getInputPortStatus().stream().filter(inputPortStatus -> inputPort.getId().equals(inputPortStatus.getId())).findFirst().orElse(null),
                inputPortStatus -> createPortStatusDto(inputPortStatus)
            );
            flow.getInputPorts().add(entityFactory.createPortEntity(inputPort, revision, accessPolicy, status));
        }

        for (final PortDTO outputPort : snippet.getOutputPorts()) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(outputPort.getId()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(group.getOutputPort(outputPort.getId()));
            final PortStatusDTO status = getComponentStatus(
                () -> groupStatus.getOutputPortStatus().stream().filter(outputPortStatus -> outputPort.getId().equals(outputPortStatus.getId())).findFirst().orElse(null),
                outputPortStatus -> createPortStatusDto(outputPortStatus)
            );
            flow.getOutputPorts().add(entityFactory.createPortEntity(outputPort, revision, accessPolicy, status));
        }

        for (final LabelDTO label : snippet.getLabels()) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(label.getId()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(group.getLabel(label.getId()));
            flow.getLabels().add(entityFactory.createLabelEntity(label, revision, accessPolicy));
        }

        for (final ProcessGroupDTO processGroup : snippet.getProcessGroups()) {
            // clear the contents as we only return a single level/group at a time
            processGroup.setContents(null);

            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(processGroup.getId()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(group.getProcessGroup(processGroup.getId()));
            final ProcessGroupStatusDTO status = getComponentStatus(
                () -> groupStatus.getProcessGroupStatus().stream().filter(processGroupStatus -> processGroup.getId().equals(processGroupStatus.getId())).findFirst().orElse(null),
                processGroupStatus -> createConciseProcessGroupStatusDto(processGroupStatus)
            );
            flow.getProcessGroups().add(entityFactory.createProcessGroupEntity(processGroup, revision, accessPolicy, status));
        }

        for (final ProcessorDTO processor : snippet.getProcessors()) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(processor.getId()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(group.getProcessor(processor.getId()));
            final ProcessorStatusDTO status = getComponentStatus(
                () -> groupStatus.getProcessorStatus().stream().filter(processorStatus -> processor.getId().equals(processorStatus.getId())).findFirst().orElse(null),
                processorStatus -> createProcessorStatusDto(processorStatus)
            );
            flow.getProcessors().add(entityFactory.createProcessorEntity(processor, revision, accessPolicy, status));
        }

        for (final RemoteProcessGroupDTO remoteProcessGroup : snippet.getRemoteProcessGroups()) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(remoteProcessGroup.getId()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(group.getRemoteProcessGroup(remoteProcessGroup.getId()));
            final RemoteProcessGroupStatusDTO status = getComponentStatus(
                () -> groupStatus.getRemoteProcessGroupStatus().stream().filter(rpgStatus -> remoteProcessGroup.getId().equals(rpgStatus.getId())).findFirst().orElse(null),
                remoteProcessGroupStatus -> createRemoteProcessGroupStatusDto(remoteProcessGroupStatus)
            );
            flow.getRemoteProcessGroups().add(entityFactory.createRemoteProcessGroupEntity(remoteProcessGroup, revision, accessPolicy, status));
        }

        return flow;
    }

    private <T, S> T getComponentStatus(final Supplier<S> getComponentStatus, final Function<S, T> convertToDto) {
        final T statusDTO;
        final S status = getComponentStatus.get();
        if (status != null) {
            statusDTO = convertToDto.apply(status);
        } else {
            statusDTO = null;
        }
        return statusDTO;
    }

    public FlowDTO createFlowDto(final ProcessGroup group, final ProcessGroupStatus groupStatus, final RevisionManager revisionManager) {
        final FlowDTO dto = new FlowDTO();

        for (final ProcessorNode procNode : group.getProcessors()) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(procNode.getIdentifier()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(procNode);
            final ProcessorStatusDTO status = getComponentStatus(
                () -> groupStatus.getProcessorStatus().stream().filter(processorStatus -> procNode.getIdentifier().equals(processorStatus.getId())).findFirst().orElse(null),
                processorStatus -> createProcessorStatusDto(processorStatus)
            );
            dto.getProcessors().add(entityFactory.createProcessorEntity(createProcessorDto(procNode), revision, accessPolicy, status));
        }

        for (final Connection connNode : group.getConnections()) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(connNode.getIdentifier()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(connNode);
            final ConnectionStatusDTO status = getComponentStatus(
                () -> groupStatus.getConnectionStatus().stream().filter(connectionStatus -> connNode.getIdentifier().equals(connectionStatus.getId())).findFirst().orElse(null),
                connectionStatus -> createConnectionStatusDto(connectionStatus)
            );
            dto.getConnections().add(entityFactory.createConnectionEntity(createConnectionDto(connNode), revision, accessPolicy, status));
        }

        for (final Label label : group.getLabels()) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(label.getIdentifier()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(label);
            dto.getLabels().add(entityFactory.createLabelEntity(createLabelDto(label), revision, accessPolicy));
        }

        for (final Funnel funnel : group.getFunnels()) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(funnel.getIdentifier()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(funnel);
            dto.getFunnels().add(entityFactory.createFunnelEntity(createFunnelDto(funnel), revision, accessPolicy));
        }

        for (final ProcessGroup childGroup : group.getProcessGroups()) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(childGroup.getIdentifier()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(childGroup);
            final ProcessGroupStatusDTO status = getComponentStatus(
                () -> groupStatus.getProcessGroupStatus().stream().filter(processGroupStatus -> childGroup.getIdentifier().equals(processGroupStatus.getId())).findFirst().orElse(null),
                processGroupStatus -> createConciseProcessGroupStatusDto(processGroupStatus)
            );
            dto.getProcessGroups().add(entityFactory.createProcessGroupEntity(createProcessGroupDto(childGroup), revision, accessPolicy, status));
        }

        for (final RemoteProcessGroup rpg : group.getRemoteProcessGroups()) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(rpg.getIdentifier()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(rpg);
            final RemoteProcessGroupStatusDTO status = getComponentStatus(
                () -> groupStatus.getRemoteProcessGroupStatus().stream().filter(remoteProcessGroupStatus -> rpg.getIdentifier().equals(remoteProcessGroupStatus.getId())).findFirst().orElse(null),
                remoteProcessGroupStatus -> createRemoteProcessGroupStatusDto(remoteProcessGroupStatus)
            );
            dto.getRemoteProcessGroups().add(entityFactory.createRemoteProcessGroupEntity(createRemoteProcessGroupDto(rpg), revision, accessPolicy, status));
        }

        for (final Port inputPort : group.getInputPorts()) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(inputPort.getIdentifier()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(inputPort);
            final PortStatusDTO status = getComponentStatus(
                () -> groupStatus.getInputPortStatus().stream().filter(inputPortStatus -> inputPort.getIdentifier().equals(inputPortStatus.getId())).findFirst().orElse(null),
                inputPortStatus -> createPortStatusDto(inputPortStatus)
            );
            dto.getInputPorts().add(entityFactory.createPortEntity(createPortDto(inputPort), revision, accessPolicy, status));
        }

        for (final Port outputPort : group.getOutputPorts()) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(outputPort.getIdentifier()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(outputPort);
            final PortStatusDTO status = getComponentStatus(
                () -> groupStatus.getOutputPortStatus().stream().filter(outputPortStatus -> outputPort.getIdentifier().equals(outputPortStatus.getId())).findFirst().orElse(null),
                outputPortStatus -> createPortStatusDto(outputPortStatus)
            );
            dto.getOutputPorts().add(entityFactory.createPortEntity(createPortDto(outputPort), revision, accessPolicy, status));
        }

        // TODO - controller services once they are accessible from the group
        for (final ControllerServiceNode controllerService : group.getControllerServices(false)) {
            final RevisionDTO revision = createRevisionDTO(revisionManager.getRevision(controllerService.getIdentifier()));
            final AccessPolicyDTO accessPolicy = createAccessPolicyDto(controllerService);
            dto.getControllerServices().add(entityFactory.createControllerServiceEntity(createControllerServiceDto(controllerService), revision, accessPolicy));
        }

        return dto;
    }

    /**
     * Creates a ProcessGroupDTO from the specified ProcessGroup.
     *
     * @param group group
     * @param recurse recurse
     * @return dto
     */
    public ProcessGroupDTO createProcessGroupDto(final ProcessGroup group, final boolean recurse) {
        final ProcessGroupDTO dto = createConciseProcessGroupDto(group);
        dto.setContents(createProcessGroupContentsDto(group, recurse));
        return dto;
    }

    /**
     * Creates a ProcessGroupDTO from the specified ProcessGroup.
     *
     * @param group group
     * @return dto
     */
    private ProcessGroupDTO createConciseProcessGroupDto(final ProcessGroup group) {
        if (group == null) {
            return null;
        }

        final ProcessGroupDTO dto = new ProcessGroupDTO();
        dto.setId(group.getIdentifier());
        dto.setPosition(createPositionDto(group.getPosition()));
        dto.setComments(group.getComments());
        dto.setName(group.getName());

        ProcessGroup parentGroup = group.getParent();
        if (parentGroup != null) {
            dto.setParentGroupId(parentGroup.getIdentifier());
        }

        final ProcessGroupCounts counts = group.getCounts();
        dto.setRunningCount(counts.getRunningCount());
        dto.setStoppedCount(counts.getStoppedCount());
        dto.setInvalidCount(counts.getInvalidCount());
        dto.setDisabledCount(counts.getDisabledCount());
        dto.setInputPortCount(counts.getInputPortCount());
        dto.setOutputPortCount(counts.getOutputPortCount());
        dto.setActiveRemotePortCount(counts.getActiveRemotePortCount());
        dto.setInactiveRemotePortCount(counts.getInactiveRemotePortCount());

        return dto;
    }

    /**
     * Creates a ProcessGroupContentDTO from the specified ProcessGroup.
     *
     * @param group group
     * @param recurse recurse
     * @return dto
     */
    private FlowSnippetDTO createProcessGroupContentsDto(final ProcessGroup group, final boolean recurse) {
        if (group == null) {
            return null;
        }

        final FlowSnippetDTO dto = new FlowSnippetDTO();

        for (final ProcessorNode procNode : group.getProcessors()) {
            dto.getProcessors().add(createProcessorDto(procNode));
        }

        for (final Connection connNode : group.getConnections()) {
            dto.getConnections().add(createConnectionDto(connNode));
        }

        for (final Label label : group.getLabels()) {
            dto.getLabels().add(createLabelDto(label));
        }

        for (final Funnel funnel : group.getFunnels()) {
            dto.getFunnels().add(createFunnelDto(funnel));
        }

        for (final ProcessGroup childGroup : group.getProcessGroups()) {
            if (recurse) {
                dto.getProcessGroups().add(createProcessGroupDto(childGroup, recurse));
            } else {
                dto.getProcessGroups().add(createConciseProcessGroupDto(childGroup));
            }
        }

        for (final RemoteProcessGroup remoteProcessGroup : group.getRemoteProcessGroups()) {
            dto.getRemoteProcessGroups().add(createRemoteProcessGroupDto(remoteProcessGroup));
        }

        for (final Port inputPort : group.getInputPorts()) {
            dto.getInputPorts().add(createPortDto(inputPort));
        }

        for (final Port outputPort : group.getOutputPorts()) {
            dto.getOutputPorts().add(createPortDto(outputPort));
        }

        return dto;
    }

    /**
     * Gets the capability description from the specified class.
     */
    @SuppressWarnings("deprecation")
    private String getCapabilityDescription(final Class<?> cls) {
        final CapabilityDescription capabilityDesc = cls.getAnnotation(CapabilityDescription.class);
        if (capabilityDesc != null) {
            return capabilityDesc.value();
        }

        final org.apache.nifi.processor.annotation.CapabilityDescription deprecatedCapabilityDesc
                = cls.getAnnotation(org.apache.nifi.processor.annotation.CapabilityDescription.class);

        return (deprecatedCapabilityDesc == null) ? null : deprecatedCapabilityDesc.value();
    }

    /**
     * Gets the tags from the specified class.
     */
    @SuppressWarnings("deprecation")
    private Set<String> getTags(final Class<?> cls) {
        final Set<String> tags = new HashSet<>();
        final Tags tagsAnnotation = cls.getAnnotation(Tags.class);
        if (tagsAnnotation != null) {
            for (final String tag : tagsAnnotation.value()) {
                tags.add(tag);
            }
        } else {
            final org.apache.nifi.processor.annotation.Tags deprecatedTagsAnnotation = cls.getAnnotation(org.apache.nifi.processor.annotation.Tags.class);
            if (deprecatedTagsAnnotation != null) {
                for (final String tag : deprecatedTagsAnnotation.value()) {
                    tags.add(tag);
                }
            }
        }

        return tags;
    }

    /**
     * Gets the DocumentedTypeDTOs from the specified classes.
     *
     * @param classes classes
     * @return dtos
     */
    @SuppressWarnings("rawtypes")
    public Set<DocumentedTypeDTO> fromDocumentedTypes(final Set<Class> classes) {
        final Set<DocumentedTypeDTO> types = new LinkedHashSet<>();
        final Set<Class> sortedClasses = new TreeSet<>(CLASS_NAME_COMPARATOR);
        sortedClasses.addAll(classes);

        for (final Class<?> cls : sortedClasses) {
            final DocumentedTypeDTO type = new DocumentedTypeDTO();
            type.setType(cls.getName());
            type.setDescription(getCapabilityDescription(cls));
            type.setTags(getTags(cls));
            types.add(type);
        }

        return types;
    }

    /**
     * Creates a ProcessorDTO from the specified ProcessorNode.
     *
     * @param node node
     * @return dto
     */
    public ProcessorDTO createProcessorDto(final ProcessorNode node) {
        if (node == null) {
            return null;
        }

        final ProcessorDTO dto = new ProcessorDTO();
        dto.setId(node.getIdentifier());
        dto.setPosition(createPositionDto(node.getPosition()));
        dto.setStyle(node.getStyle());
        dto.setParentGroupId(node.getProcessGroup().getIdentifier());
        dto.setInputRequirement(node.getInputRequirement().name());
        dto.setPersistsState(node.getProcessor().getClass().isAnnotationPresent(Stateful.class));

        dto.setType(node.getProcessor().getClass().getCanonicalName());
        dto.setName(node.getName());
        dto.setState(node.getScheduledState().toString());

        // build the relationship dtos
        final List<RelationshipDTO> relationships = new ArrayList<>();
        for (final Relationship rel : node.getRelationships()) {
            final RelationshipDTO relationshipDTO = new RelationshipDTO();
            relationshipDTO.setDescription(rel.getDescription());
            relationshipDTO.setName(rel.getName());
            relationshipDTO.setAutoTerminate(node.isAutoTerminated(rel));
            relationships.add(relationshipDTO);
        }

        // sort the relationships
        Collections.sort(relationships, new Comparator<RelationshipDTO>() {
            @Override
            public int compare(RelationshipDTO r1, RelationshipDTO r2) {
                return Collator.getInstance(Locale.US).compare(r1.getName(), r2.getName());
            }
        });

        // set the relationships
        dto.setRelationships(relationships);

        dto.setDescription(getCapabilityDescription(node.getClass()));
        dto.setSupportsParallelProcessing(!node.isTriggeredSerially());
        dto.setSupportsEventDriven(node.isEventDrivenSupported());
        dto.setSupportsBatching(node.isHighThroughputSupported());
        dto.setConfig(createProcessorConfigDto(node));

        final Collection<ValidationResult> validationErrors = node.getValidationErrors();
        if (validationErrors != null && !validationErrors.isEmpty()) {
            final List<String> errors = new ArrayList<>();
            for (final ValidationResult validationResult : validationErrors) {
                errors.add(validationResult.toString());
            }

            dto.setValidationErrors(errors);
        }

        return dto;
    }

    /**
     * Creates a BulletinBoardDTO for the specified bulletins.
     *
     * @param bulletins bulletins
     * @return dto
     */
    public BulletinBoardDTO createBulletinBoardDto(final List<BulletinDTO> bulletins) {
        // sort the bulletins
        Collections.sort(bulletins, new Comparator<BulletinDTO>() {
            @Override
            public int compare(BulletinDTO bulletin1, BulletinDTO bulletin2) {
                if (bulletin1 == null && bulletin2 == null) {
                    return 0;
                } else if (bulletin1 == null) {
                    return 1;
                } else if (bulletin2 == null) {
                    return -1;
                }

                final Date timestamp1 = bulletin1.getTimestamp();
                final Date timestamp2 = bulletin2.getTimestamp();
                if (timestamp1 == null && timestamp2 == null) {
                    return 0;
                } else if (timestamp1 == null) {
                    return 1;
                } else if (timestamp2 == null) {
                    return -1;
                } else {
                    return timestamp1.compareTo(timestamp2);
                }
            }
        });

        // create the bulletin board
        final BulletinBoardDTO bulletinBoard = new BulletinBoardDTO();
        bulletinBoard.setBulletins(bulletins);
        bulletinBoard.setGenerated(new Date());
        return bulletinBoard;
    }

    /**
     * Creates BulletinDTOs for the specified Bulletins.
     *
     * @param bulletins bulletin
     * @return dto
     */
    public List<BulletinDTO> createBulletinDtos(final List<Bulletin> bulletins) {
        final List<BulletinDTO> bulletinDtos = new ArrayList<>(bulletins.size());
        for (final Bulletin bulletin : bulletins) {
            bulletinDtos.add(createBulletinDto(bulletin));
        }
        return bulletinDtos;
    }

    /**
     * Creates a BulletinDTO for the specified Bulletin.
     *
     * @param bulletin bulletin
     * @return dto
     */
    public BulletinDTO createBulletinDto(final Bulletin bulletin) {
        final BulletinDTO dto = new BulletinDTO();
        dto.setId(bulletin.getId());
        dto.setNodeAddress(bulletin.getNodeAddress());
        dto.setTimestamp(bulletin.getTimestamp());
        dto.setGroupId(bulletin.getGroupId());
        dto.setSourceId(bulletin.getSourceId());
        dto.setSourceName(bulletin.getSourceName());
        dto.setCategory(bulletin.getCategory());
        dto.setLevel(bulletin.getLevel());
        dto.setMessage(bulletin.getMessage());
        return dto;
    }

    /**
     * Creates a ProvenanceEventNodeDTO for the specified ProvenanceEventLineageNode.
     *
     * @param node node
     * @return dto
     */
    @SuppressWarnings("deprecation")
    public ProvenanceNodeDTO createProvenanceEventNodeDTO(final ProvenanceEventLineageNode node) {
        final ProvenanceNodeDTO dto = new ProvenanceNodeDTO();
        dto.setId(node.getIdentifier());
        dto.setType("EVENT");
        dto.setEventType(node.getEventType().toString());
        dto.setTimestamp(new Date(node.getTimestamp()));
        dto.setMillis(node.getTimestamp());
        dto.setFlowFileUuid(node.getFlowFileUuid());
        dto.setParentUuids(node.getParentUuids());
        dto.setChildUuids(node.getChildUuids());
        dto.setClusterNodeIdentifier(node.getClusterNodeIdentifier());
        return dto;
    }

    /**
     * Creates a FlowFileNodeDTO for the specified LineageNode.
     *
     * @param node node
     * @return dto
     */
    @SuppressWarnings("deprecation")
    public ProvenanceNodeDTO createFlowFileNodeDTO(final LineageNode node) {
        final ProvenanceNodeDTO dto = new ProvenanceNodeDTO();
        dto.setId(node.getIdentifier());
        dto.setType("FLOWFILE");
        dto.setTimestamp(new Date(node.getTimestamp()));
        dto.setMillis(node.getTimestamp());
        dto.setFlowFileUuid(node.getFlowFileUuid());
        dto.setClusterNodeIdentifier(node.getClusterNodeIdentifier());
        return dto;
    }

    /**
     * Creates a ProvenanceLinkDTO for the specified LineageEdge.
     *
     * @param edge edge
     * @return dto
     */
    public ProvenanceLinkDTO createProvenanceLinkDTO(final LineageEdge edge) {
        final LineageNode source = edge.getSource();
        final LineageNode target = edge.getDestination();

        final ProvenanceLinkDTO dto = new ProvenanceLinkDTO();
        dto.setTimestamp(new Date(target.getTimestamp()));
        dto.setMillis(target.getTimestamp());
        dto.setFlowFileUuid(edge.getUuid());
        dto.setSourceId(source.getIdentifier());
        dto.setTargetId(target.getIdentifier());
        return dto;
    }

    /**
     * Creates a LineageDTO for the specified Lineage.
     *
     * @param computeLineageSubmission submission
     * @return dto
     */
    public LineageDTO createLineageDto(final ComputeLineageSubmission computeLineageSubmission) {
        // build the lineage dto
        final LineageDTO dto = new LineageDTO();
        final LineageRequestDTO requestDto = new LineageRequestDTO();
        final LineageResultsDTO resultsDto = new LineageResultsDTO();

        // include the original request and results
        dto.setRequest(requestDto);
        dto.setResults(resultsDto);

        // rebuild the request from the submission object
        switch (computeLineageSubmission.getLineageComputationType()) {
            case EXPAND_CHILDREN:
                requestDto.setEventId(computeLineageSubmission.getExpandedEventId());
                requestDto.setLineageRequestType(LineageRequestType.CHILDREN);
                break;
            case EXPAND_PARENTS:
                requestDto.setEventId(computeLineageSubmission.getExpandedEventId());
                requestDto.setLineageRequestType(LineageRequestType.PARENTS);
                break;
            case FLOWFILE_LINEAGE:
                final Collection<String> uuids = computeLineageSubmission.getLineageFlowFileUuids();
                if (uuids.size() == 1) {
                    requestDto.setUuid(uuids.iterator().next());
                }
                requestDto.setLineageRequestType(LineageRequestType.FLOWFILE);
                break;
        }

        // include lineage details
        dto.setId(computeLineageSubmission.getLineageIdentifier());
        dto.setSubmissionTime(computeLineageSubmission.getSubmissionTime());

        // create the results dto
        final ComputeLineageResult results = computeLineageSubmission.getResult();
        dto.setFinished(results.isFinished());
        dto.setPercentCompleted(results.getPercentComplete());
        dto.setExpiration(results.getExpiration());

        final List<LineageNode> nodes = results.getNodes();
        final List<LineageEdge> edges = results.getEdges();

        final List<ProvenanceNodeDTO> nodeDtos = new ArrayList<>();
        if (results.isFinished()) {
            // create the node dto's
            for (final LineageNode node : nodes) {
                switch (node.getNodeType()) {
                    case FLOWFILE_NODE:
                        nodeDtos.add(createFlowFileNodeDTO(node));
                        break;
                    case PROVENANCE_EVENT_NODE:
                        nodeDtos.add(createProvenanceEventNodeDTO((ProvenanceEventLineageNode) node));
                        break;
                }
            }
        }
        resultsDto.setNodes(nodeDtos);

        // include any errors
        if (results.getError() != null) {
            final Set<String> errors = new HashSet<>();
            errors.add(results.getError());
            resultsDto.setErrors(errors);
        }

        // create the link dto's
        final List<ProvenanceLinkDTO> linkDtos = new ArrayList<>();
        for (final LineageEdge edge : edges) {
            linkDtos.add(createProvenanceLinkDTO(edge));
        }
        resultsDto.setLinks(linkDtos);

        return dto;
    }

    /**
     * Creates a SystemDiagnosticsDTO for the specified SystemDiagnostics.
     *
     * @param sysDiagnostics diags
     * @return dto
     */
    public SystemDiagnosticsDTO createSystemDiagnosticsDto(final SystemDiagnostics sysDiagnostics) {

        final SystemDiagnosticsDTO dto = new SystemDiagnosticsDTO();
        final SystemDiagnosticsSnapshotDTO snapshot = new SystemDiagnosticsSnapshotDTO();
        dto.setAggregateSnapshot(snapshot);

        snapshot.setStatsLastRefreshed(new Date(sysDiagnostics.getCreationTimestamp()));

        // processors
        snapshot.setAvailableProcessors(sysDiagnostics.getAvailableProcessors());
        snapshot.setProcessorLoadAverage(sysDiagnostics.getProcessorLoadAverage());

        // threads
        snapshot.setDaemonThreads(sysDiagnostics.getDaemonThreads());
        snapshot.setTotalThreads(sysDiagnostics.getTotalThreads());

        // heap
        snapshot.setMaxHeap(FormatUtils.formatDataSize(sysDiagnostics.getMaxHeap()));
        snapshot.setMaxHeapBytes(sysDiagnostics.getMaxHeap());
        snapshot.setTotalHeap(FormatUtils.formatDataSize(sysDiagnostics.getTotalHeap()));
        snapshot.setTotalHeapBytes(sysDiagnostics.getTotalHeap());
        snapshot.setUsedHeap(FormatUtils.formatDataSize(sysDiagnostics.getUsedHeap()));
        snapshot.setUsedHeapBytes(sysDiagnostics.getUsedHeap());
        snapshot.setFreeHeap(FormatUtils.formatDataSize(sysDiagnostics.getFreeHeap()));
        snapshot.setFreeHeapBytes(sysDiagnostics.getFreeHeap());
        if (sysDiagnostics.getHeapUtilization() != -1) {
            snapshot.setHeapUtilization(FormatUtils.formatUtilization(sysDiagnostics.getHeapUtilization()));
        }

        // non heap
        snapshot.setMaxNonHeap(FormatUtils.formatDataSize(sysDiagnostics.getMaxNonHeap()));
        snapshot.setMaxNonHeapBytes(sysDiagnostics.getMaxNonHeap());
        snapshot.setTotalNonHeap(FormatUtils.formatDataSize(sysDiagnostics.getTotalNonHeap()));
        snapshot.setTotalNonHeapBytes(sysDiagnostics.getTotalNonHeap());
        snapshot.setUsedNonHeap(FormatUtils.formatDataSize(sysDiagnostics.getUsedNonHeap()));
        snapshot.setUsedNonHeapBytes(sysDiagnostics.getUsedNonHeap());
        snapshot.setFreeNonHeap(FormatUtils.formatDataSize(sysDiagnostics.getFreeNonHeap()));
        snapshot.setFreeNonHeapBytes(sysDiagnostics.getFreeNonHeap());
        if (sysDiagnostics.getNonHeapUtilization() != -1) {
            snapshot.setNonHeapUtilization(FormatUtils.formatUtilization(sysDiagnostics.getNonHeapUtilization()));
        }

        // flow file disk usage
        final SystemDiagnosticsSnapshotDTO.StorageUsageDTO flowFileRepositoryStorageUsageDto = createStorageUsageDTO(null, sysDiagnostics.getFlowFileRepositoryStorageUsage());
        snapshot.setFlowFileRepositoryStorageUsage(flowFileRepositoryStorageUsageDto);

        // content disk usage
        final Set<SystemDiagnosticsSnapshotDTO.StorageUsageDTO> contentRepositoryStorageUsageDtos = new LinkedHashSet<>();
        snapshot.setContentRepositoryStorageUsage(contentRepositoryStorageUsageDtos);
        for (final Map.Entry<String, StorageUsage> entry : sysDiagnostics.getContentRepositoryStorageUsage().entrySet()) {
            contentRepositoryStorageUsageDtos.add(createStorageUsageDTO(entry.getKey(), entry.getValue()));
        }

        // garbage collection
        final Set<SystemDiagnosticsSnapshotDTO.GarbageCollectionDTO> garbageCollectionDtos = new LinkedHashSet<>();
        snapshot.setGarbageCollection(garbageCollectionDtos);
        for (final Map.Entry<String, GarbageCollection> entry : sysDiagnostics.getGarbageCollection().entrySet()) {
            garbageCollectionDtos.add(createGarbageCollectionDTO(entry.getKey(), entry.getValue()));
        }

        return dto;
    }

    /**
     * Creates a StorageUsageDTO from the specified StorageUsage.
     *
     * @param identifier id
     * @param storageUsage usage
     * @return dto
     */
    public SystemDiagnosticsSnapshotDTO.StorageUsageDTO createStorageUsageDTO(final String identifier, final StorageUsage storageUsage) {
        final SystemDiagnosticsSnapshotDTO.StorageUsageDTO dto = new SystemDiagnosticsSnapshotDTO.StorageUsageDTO();
        dto.setIdentifier(identifier);
        dto.setFreeSpace(FormatUtils.formatDataSize(storageUsage.getFreeSpace()));
        dto.setTotalSpace(FormatUtils.formatDataSize(storageUsage.getTotalSpace()));
        dto.setUsedSpace(FormatUtils.formatDataSize(storageUsage.getUsedSpace()));
        dto.setFreeSpaceBytes(storageUsage.getFreeSpace());
        dto.setTotalSpaceBytes(storageUsage.getTotalSpace());
        dto.setUsedSpaceBytes(storageUsage.getUsedSpace());
        dto.setUtilization(FormatUtils.formatUtilization(storageUsage.getDiskUtilization()));
        return dto;
    }

    /**
     * Creates a GarbageCollectionDTO from the specified GarbageCollection.
     *
     * @param name name
     * @param garbageCollection gc
     * @return dto
     */
    public SystemDiagnosticsSnapshotDTO.GarbageCollectionDTO createGarbageCollectionDTO(final String name, final GarbageCollection garbageCollection) {
        final SystemDiagnosticsSnapshotDTO.GarbageCollectionDTO dto = new SystemDiagnosticsSnapshotDTO.GarbageCollectionDTO();
        dto.setName(name);
        dto.setCollectionCount(garbageCollection.getCollectionCount());
        dto.setCollectionTime(FormatUtils.formatHoursMinutesSeconds(garbageCollection.getCollectionTime(), TimeUnit.MILLISECONDS));
        dto.setCollectionMillis(garbageCollection.getCollectionTime());
        return dto;
    }

    /**
     * Creates a ResourceDTO from the specified Resource.
     *
     * @param resource resource
     * @return dto
     */
    public ResourceDTO createResourceDto(final Resource resource) {
        final ResourceDTO dto = new ResourceDTO();
        dto.setIdentifier(resource.getIdentifier());
        dto.setName(resource.getName());
        return dto;
    }

    /**
     * Creates a ProcessorConfigDTO from the specified ProcessorNode.
     *
     * @param procNode node
     * @return dto
     */
    public ProcessorConfigDTO createProcessorConfigDto(final ProcessorNode procNode) {
        if (procNode == null) {
            return null;
        }

        final ProcessorConfigDTO dto = new ProcessorConfigDTO();

        // sort a copy of the properties
        final Map<PropertyDescriptor, String> sortedProperties = new TreeMap<>(new Comparator<PropertyDescriptor>() {
            @Override
            public int compare(PropertyDescriptor o1, PropertyDescriptor o2) {
                return Collator.getInstance(Locale.US).compare(o1.getName(), o2.getName());
            }
        });
        sortedProperties.putAll(procNode.getProperties());

        // get the property order from the processor
        final Processor processor = procNode.getProcessor();
        final Map<PropertyDescriptor, String> orderedProperties = new LinkedHashMap<>();
        final List<PropertyDescriptor> descriptors = processor.getPropertyDescriptors();
        if (descriptors != null && !descriptors.isEmpty()) {
            for (PropertyDescriptor descriptor : descriptors) {
                orderedProperties.put(descriptor, null);
            }
        }
        orderedProperties.putAll(sortedProperties);

        // build the descriptor and property dtos
        dto.setDescriptors(new LinkedHashMap<String, PropertyDescriptorDTO>());
        dto.setProperties(new LinkedHashMap<String, String>());
        for (final Map.Entry<PropertyDescriptor, String> entry : orderedProperties.entrySet()) {
            final PropertyDescriptor descriptor = entry.getKey();

            // store the property descriptor
            dto.getDescriptors().put(descriptor.getName(), createPropertyDescriptorDto(descriptor, procNode.getProcessGroup().getIdentifier()));

            // determine the property value - don't include sensitive properties
            String propertyValue = entry.getValue();
            if (propertyValue != null && descriptor.isSensitive()) {
                propertyValue = "********";
            }

            // set the property value
            dto.getProperties().put(descriptor.getName(), propertyValue);
        }

        dto.setSchedulingPeriod(procNode.getSchedulingPeriod());
        dto.setPenaltyDuration(procNode.getPenalizationPeriod());
        dto.setYieldDuration(procNode.getYieldPeriod());
        dto.setRunDurationMillis(procNode.getRunDuration(TimeUnit.MILLISECONDS));
        dto.setConcurrentlySchedulableTaskCount(procNode.getMaxConcurrentTasks());
        dto.setLossTolerant(procNode.isLossTolerant());
        dto.setComments(procNode.getComments());
        dto.setBulletinLevel(procNode.getBulletinLevel().name());
        dto.setSchedulingStrategy(procNode.getSchedulingStrategy().name());
        dto.setAnnotationData(procNode.getAnnotationData());

        // set up the default values for concurrent tasks and scheduling period
        final Map<String, String> defaultConcurrentTasks = new HashMap<>();
        defaultConcurrentTasks.put(SchedulingStrategy.TIMER_DRIVEN.name(), String.valueOf(SchedulingStrategy.TIMER_DRIVEN.getDefaultConcurrentTasks()));
        defaultConcurrentTasks.put(SchedulingStrategy.EVENT_DRIVEN.name(), String.valueOf(SchedulingStrategy.EVENT_DRIVEN.getDefaultConcurrentTasks()));
        defaultConcurrentTasks.put(SchedulingStrategy.CRON_DRIVEN.name(), String.valueOf(SchedulingStrategy.CRON_DRIVEN.getDefaultConcurrentTasks()));
        dto.setDefaultConcurrentTasks(defaultConcurrentTasks);

        final Map<String, String> defaultSchedulingPeriod = new HashMap<>();
        defaultSchedulingPeriod.put(SchedulingStrategy.TIMER_DRIVEN.name(), SchedulingStrategy.TIMER_DRIVEN.getDefaultSchedulingPeriod());
        defaultSchedulingPeriod.put(SchedulingStrategy.CRON_DRIVEN.name(), SchedulingStrategy.CRON_DRIVEN.getDefaultSchedulingPeriod());
        dto.setDefaultSchedulingPeriod(defaultSchedulingPeriod);

        return dto;
    }

    /**
     * Creates a PropertyDesriptorDTO from the specified PropertyDesriptor.
     *
     * @param propertyDescriptor descriptor
     * @param groupId the Identifier of the Process Group that the component belongs to
     * @return dto
     */
    public PropertyDescriptorDTO createPropertyDescriptorDto(final PropertyDescriptor propertyDescriptor, final String groupId) {
        if (propertyDescriptor == null) {
            return null;
        }

        final PropertyDescriptorDTO dto = new PropertyDescriptorDTO();

        dto.setName(propertyDescriptor.getName());
        dto.setDisplayName(propertyDescriptor.getDisplayName());
        dto.setRequired(propertyDescriptor.isRequired());
        dto.setSensitive(propertyDescriptor.isSensitive());
        dto.setDynamic(propertyDescriptor.isDynamic());
        dto.setDescription(propertyDescriptor.getDescription());
        dto.setDefaultValue(propertyDescriptor.getDefaultValue());
        dto.setSupportsEl(propertyDescriptor.isExpressionLanguageSupported());

        // set the identifies controller service is applicable
        if (propertyDescriptor.getControllerServiceDefinition() != null) {
            dto.setIdentifiesControllerService(propertyDescriptor.getControllerServiceDefinition().getName());
        }

        final Class<? extends ControllerService> serviceDefinition = propertyDescriptor.getControllerServiceDefinition();
        if (propertyDescriptor.getAllowableValues() == null) {
            if (serviceDefinition == null) {
                dto.setAllowableValues(null);
            } else {
                final List<AllowableValueDTO> allowableValues = new ArrayList<>();
                for (final String serviceIdentifier : controllerServiceProvider.getControllerServiceIdentifiers(serviceDefinition, groupId)) {
                    final ControllerServiceNode service = controllerServiceProvider.getControllerServiceNode(serviceIdentifier);
                    final String displayName = service.isAuthorized(authorizer, RequestAction.READ) ? service.getName() : serviceIdentifier;

                    final AllowableValueDTO allowableValue = new AllowableValueDTO();
                    allowableValue.setDisplayName(displayName);
                    allowableValue.setValue(serviceIdentifier);
                    allowableValues.add(allowableValue);
                }
                dto.setAllowableValues(allowableValues);
            }
        } else {
            final List<AllowableValueDTO> allowableValues = new ArrayList<>();
            for (final AllowableValue allowableValue : propertyDescriptor.getAllowableValues()) {
                final AllowableValueDTO allowableValueDto = new AllowableValueDTO();
                allowableValueDto.setDisplayName(allowableValue.getDisplayName());
                allowableValueDto.setValue(allowableValue.getValue());
                allowableValueDto.setDescription(allowableValue.getDescription());
                allowableValues.add(allowableValueDto);
            }
            dto.setAllowableValues(allowableValues);
        }

        return dto;
    }

    // Copy methods
    public LabelDTO copy(final LabelDTO original) {
        final LabelDTO copy = new LabelDTO();
        copy.setId(original.getId());
        copy.setParentGroupId(original.getParentGroupId());
        copy.setLabel(original.getLabel());
        copy.setStyle(copy(original.getStyle()));
        copy.setPosition(original.getPosition());
        copy.setWidth(original.getWidth());
        copy.setHeight(original.getHeight());

        return copy;
    }

    public ControllerServiceDTO copy(final ControllerServiceDTO original) {
        final ControllerServiceDTO copy = new ControllerServiceDTO();
        copy.setAnnotationData(original.getAnnotationData());
        copy.setComments(original.getComments());
        copy.setCustomUiUrl(original.getCustomUiUrl());
        copy.setDescriptors(copy(original.getDescriptors()));
        copy.setId(original.getId());
        copy.setName(original.getName());
        copy.setProperties(copy(original.getProperties()));
        copy.setReferencingComponents(copy(original.getReferencingComponents()));
        copy.setState(original.getState());
        copy.setType(original.getType());
        copy.setValidationErrors(copy(original.getValidationErrors()));
        return copy;
    }

    public FunnelDTO copy(final FunnelDTO original) {
        final FunnelDTO copy = new FunnelDTO();
        copy.setId(original.getId());
        copy.setParentGroupId(original.getParentGroupId());
        copy.setPosition(original.getPosition());

        return copy;
    }

    private <T> List<T> copy(final List<T> original) {
        if (original == null) {
            return null;
        } else {
            return new ArrayList<>(original);
        }
    }

    private <T> List<T> copy(final Collection<T> original) {
        if (original == null) {
            return null;
        } else {
            return new ArrayList<>(original);
        }
    }

    private <T> Set<T> copy(final Set<T> original) {
        if (original == null) {
            return null;
        } else {
            return new LinkedHashSet<>(original);
        }
    }

    private <S, T> Map<S, T> copy(final Map<S, T> original) {
        if (original == null) {
            return null;
        } else {
            return new LinkedHashMap<>(original);
        }
    }

    public ProcessorDTO copy(final ProcessorDTO original) {
        final ProcessorDTO copy = new ProcessorDTO();
        copy.setConfig(copy(original.getConfig()));
        copy.setPosition(original.getPosition());
        copy.setId(original.getId());
        copy.setName(original.getName());
        copy.setDescription(original.getDescription());
        copy.setParentGroupId(original.getParentGroupId());
        copy.setRelationships(copy(original.getRelationships()));
        copy.setState(original.getState());
        copy.setStyle(copy(original.getStyle()));
        copy.setType(original.getType());
        copy.setSupportsParallelProcessing(original.getSupportsParallelProcessing());
        copy.setSupportsEventDriven(original.getSupportsEventDriven());
        copy.setValidationErrors(copy(original.getValidationErrors()));

        return copy;
    }

    private ProcessorConfigDTO copy(final ProcessorConfigDTO original) {
        final ProcessorConfigDTO copy = new ProcessorConfigDTO();
        copy.setAnnotationData(original.getAnnotationData());
        copy.setAutoTerminatedRelationships(copy(original.getAutoTerminatedRelationships()));
        copy.setComments(original.getComments());
        copy.setSchedulingStrategy(original.getSchedulingStrategy());
        copy.setConcurrentlySchedulableTaskCount(original.getConcurrentlySchedulableTaskCount());
        copy.setCustomUiUrl(original.getCustomUiUrl());
        copy.setDescriptors(copy(original.getDescriptors()));
        copy.setProperties(copy(original.getProperties()));
        copy.setSchedulingPeriod(original.getSchedulingPeriod());
        copy.setPenaltyDuration(original.getPenaltyDuration());
        copy.setYieldDuration(original.getYieldDuration());
        copy.setRunDurationMillis(original.getRunDurationMillis());
        copy.setBulletinLevel(original.getBulletinLevel());
        copy.setDefaultConcurrentTasks(original.getDefaultConcurrentTasks());
        copy.setDefaultSchedulingPeriod(original.getDefaultSchedulingPeriod());
        copy.setLossTolerant(original.isLossTolerant());

        return copy;
    }

    public ConnectionDTO copy(final ConnectionDTO original) {
        final ConnectionDTO copy = new ConnectionDTO();
        copy.setAvailableRelationships(copy(original.getAvailableRelationships()));
        copy.setDestination(original.getDestination());
        copy.setPosition(original.getPosition());
        copy.setId(original.getId());
        copy.setName(original.getName());
        copy.setParentGroupId(original.getParentGroupId());
        copy.setSelectedRelationships(copy(original.getSelectedRelationships()));
        copy.setFlowFileExpiration(original.getFlowFileExpiration());
        copy.setBackPressureObjectThreshold(original.getBackPressureObjectThreshold());
        copy.setBackPressureDataSizeThreshold(original.getBackPressureDataSizeThreshold());
        copy.setPrioritizers(copy(original.getPrioritizers()));
        copy.setSource(original.getSource());
        copy.setzIndex(original.getzIndex());
        copy.setLabelIndex(original.getLabelIndex());
        copy.setBends(copy(original.getBends()));

        return copy;
    }

    public BulletinDTO copy(final BulletinDTO original) {
        final BulletinDTO copy = new BulletinDTO();
        copy.setId(original.getId());
        copy.setTimestamp(original.getTimestamp());
        copy.setGroupId(original.getGroupId());
        copy.setSourceId(original.getSourceId());
        copy.setSourceName(original.getSourceName());
        copy.setCategory(original.getCategory());
        copy.setLevel(original.getLevel());
        copy.setMessage(original.getMessage());
        copy.setNodeAddress(original.getNodeAddress());
        return copy;
    }

    public PortDTO copy(final PortDTO original) {
        final PortDTO copy = new PortDTO();
        copy.setPosition(original.getPosition());
        copy.setId(original.getId());
        copy.setName(original.getName());
        copy.setComments(original.getComments());
        copy.setParentGroupId(original.getParentGroupId());
        copy.setState(original.getState());
        copy.setType(original.getType());
        copy.setTransmitting(original.isTransmitting());
        copy.setConcurrentlySchedulableTaskCount(original.getConcurrentlySchedulableTaskCount());
        copy.setUserAccessControl(copy(original.getUserAccessControl()));
        copy.setGroupAccessControl(copy(original.getGroupAccessControl()));
        copy.setValidationErrors(copy(original.getValidationErrors()));
        return copy;
    }

    public RemoteProcessGroupPortDTO copy(final RemoteProcessGroupPortDTO original) {
        final RemoteProcessGroupPortDTO copy = new RemoteProcessGroupPortDTO();
        copy.setId(original.getId());
        copy.setGroupId(original.getGroupId());
        copy.setName(original.getName());
        copy.setComments(original.getComments());
        copy.setConnected(original.isConnected());
        copy.setTargetRunning(original.isTargetRunning());
        copy.setTransmitting(original.isTransmitting());
        copy.setConcurrentlySchedulableTaskCount(original.getConcurrentlySchedulableTaskCount());
        copy.setUseCompression(original.getUseCompression());
        copy.setExists(original.getExists());
        return copy;
    }

    public ProcessGroupDTO copy(final ProcessGroupDTO original, final boolean deep) {
        final ProcessGroupDTO copy = new ProcessGroupDTO();
        copy.setComments(original.getComments());
        copy.setPosition(original.getPosition());
        copy.setId(original.getId());
        copy.setInputPortCount(original.getInputPortCount());
        copy.setInvalidCount(original.getInvalidCount());
        copy.setName(original.getName());
        copy.setOutputPortCount(original.getOutputPortCount());
        copy.setParentGroupId(original.getParentGroupId());

        copy.setRunning(original.isRunning());
        copy.setRunningCount(original.getRunningCount());
        copy.setStoppedCount(original.getStoppedCount());
        copy.setDisabledCount(original.getDisabledCount());
        copy.setActiveRemotePortCount(original.getActiveRemotePortCount());
        copy.setInactiveRemotePortCount(original.getInactiveRemotePortCount());

        return copy;
    }

    public RemoteProcessGroupDTO copy(final RemoteProcessGroupDTO original) {
        final RemoteProcessGroupContentsDTO originalContents = original.getContents();
        final RemoteProcessGroupContentsDTO copyContents = new RemoteProcessGroupContentsDTO();

        if (originalContents.getInputPorts() != null) {
            final Set<RemoteProcessGroupPortDTO> inputPorts = new HashSet<>();
            for (final RemoteProcessGroupPortDTO port : originalContents.getInputPorts()) {
                inputPorts.add(copy(port));
            }
            copyContents.setInputPorts(inputPorts);
        }

        if (originalContents.getOutputPorts() != null) {
            final Set<RemoteProcessGroupPortDTO> outputPorts = new HashSet<>();
            for (final RemoteProcessGroupPortDTO port : originalContents.getOutputPorts()) {
                outputPorts.add(copy(port));
            }
            copyContents.setOutputPorts(outputPorts);
        }

        final RemoteProcessGroupDTO copy = new RemoteProcessGroupDTO();
        copy.setComments(original.getComments());
        copy.setPosition(original.getPosition());
        copy.setId(original.getId());
        copy.setCommunicationsTimeout(original.getCommunicationsTimeout());
        copy.setYieldDuration(original.getYieldDuration());
        copy.setName(original.getName());
        copy.setInputPortCount(original.getInputPortCount());
        copy.setOutputPortCount(original.getOutputPortCount());
        copy.setActiveRemoteInputPortCount(original.getActiveRemoteInputPortCount());
        copy.setInactiveRemoteInputPortCount(original.getInactiveRemoteInputPortCount());
        copy.setActiveRemoteOutputPortCount(original.getActiveRemoteOutputPortCount());
        copy.setInactiveRemoteOutputPortCount(original.getInactiveRemoteOutputPortCount());
        copy.setParentGroupId(original.getParentGroupId());
        copy.setTargetUri(original.getTargetUri());

        copy.setContents(copyContents);

        return copy;
    }

    public ConnectableDTO createConnectableDto(final PortDTO port, final ConnectableType type) {
        final ConnectableDTO connectable = new ConnectableDTO();
        connectable.setGroupId(port.getParentGroupId());
        connectable.setId(port.getId());
        connectable.setName(port.getName());
        connectable.setType(type.name());
        return connectable;
    }

    public ConnectableDTO createConnectableDto(final ProcessorDTO processor) {
        final ConnectableDTO connectable = new ConnectableDTO();
        connectable.setGroupId(processor.getParentGroupId());
        connectable.setId(processor.getId());
        connectable.setName(processor.getName());
        connectable.setType(ConnectableType.PROCESSOR.name());
        return connectable;
    }

    public ConnectableDTO createConnectableDto(final FunnelDTO funnel) {
        final ConnectableDTO connectable = new ConnectableDTO();
        connectable.setGroupId(funnel.getParentGroupId());
        connectable.setId(funnel.getId());
        connectable.setType(ConnectableType.FUNNEL.name());
        return connectable;
    }

    public ConnectableDTO createConnectableDto(final RemoteProcessGroupPortDTO remoteGroupPort, final ConnectableType type) {
        final ConnectableDTO connectable = new ConnectableDTO();
        connectable.setGroupId(remoteGroupPort.getGroupId());
        connectable.setId(remoteGroupPort.getId());
        connectable.setName(remoteGroupPort.getName());
        connectable.setType(type.name());
        return connectable;
    }

    /**
     *
     * @param original orig
     * @param deep if <code>true</code>, all Connections, ProcessGroups, Ports, Processors, etc. will be copied. If <code>false</code>, the copy will have links to the same objects referenced by
     * <code>original</code>.
     *
     * @return dto
     */
    private FlowSnippetDTO copy(final FlowSnippetDTO original, final boolean deep) {
        final FlowSnippetDTO copy = new FlowSnippetDTO();

        final Set<ConnectionDTO> connections = new LinkedHashSet<>();
        final Set<ProcessGroupDTO> groups = new LinkedHashSet<>();
        final Set<PortDTO> inputPorts = new LinkedHashSet<>();
        final Set<PortDTO> outputPorts = new LinkedHashSet<>();
        final Set<LabelDTO> labels = new LinkedHashSet<>();
        final Set<ProcessorDTO> processors = new LinkedHashSet<>();
        final Set<RemoteProcessGroupDTO> remoteProcessGroups = new LinkedHashSet<>();
        final Set<FunnelDTO> funnels = new LinkedHashSet<>();

        if (deep) {
            for (final ProcessGroupDTO group : original.getProcessGroups()) {
                groups.add(copy(group, deep));
            }

            for (final PortDTO port : original.getInputPorts()) {
                inputPorts.add(copy(port));
            }

            for (final PortDTO port : original.getOutputPorts()) {
                outputPorts.add(copy(port));
            }

            for (final LabelDTO label : original.getLabels()) {
                labels.add(copy(label));
            }

            for (final ProcessorDTO processor : original.getProcessors()) {
                processors.add(copy(processor));
            }

            for (final RemoteProcessGroupDTO remoteGroup : original.getRemoteProcessGroups()) {
                remoteProcessGroups.add(copy(remoteGroup));
            }

            for (final FunnelDTO funnel : original.getFunnels()) {
                funnels.add(copy(funnel));
            }

            for (final ConnectionDTO connection : original.getConnections()) {
                connections.add(copy(connection));
            }
        } else {
            if (original.getConnections() != null) {
                connections.addAll(copy(original.getConnections()));
            }
            if (original.getProcessGroups() != null) {
                groups.addAll(copy(original.getProcessGroups()));
            }
            if (original.getInputPorts() != null) {
                inputPorts.addAll(copy(original.getInputPorts()));
            }
            if (original.getOutputPorts() != null) {
                outputPorts.addAll(copy(original.getOutputPorts()));
            }
            if (original.getLabels() != null) {
                labels.addAll(copy(original.getLabels()));
            }
            if (original.getProcessors() != null) {
                processors.addAll(copy(original.getProcessors()));
            }
            if (original.getRemoteProcessGroups() != null) {
                remoteProcessGroups.addAll(copy(original.getRemoteProcessGroups()));
            }
            if (original.getFunnels() != null) {
                funnels.addAll(copy(original.getFunnels()));
            }
        }

        copy.setConnections(connections);
        copy.setProcessGroups(groups);
        copy.setInputPorts(inputPorts);
        copy.setLabels(labels);
        copy.setOutputPorts(outputPorts);
        copy.setProcessors(processors);
        copy.setRemoteProcessGroups(remoteProcessGroups);
        copy.setFunnels(funnels);

        return copy;
    }

    private static class SortedRemoteGroupPortComparator implements Comparator<RemoteProcessGroupPortDTO> {

        @Override
        public int compare(final RemoteProcessGroupPortDTO o1, final RemoteProcessGroupPortDTO o2) {
            if (o2 == null) {
                return -1;
            } else if (o1 == null) {
                return 1;
            }

            final String name1 = o1.getName();
            final String name2 = o2.getName();
            if (name2 == null) {
                return -1;
            } else if (name1 == null) {
                return 1;
            } else {
                int compareResult = Collator.getInstance(Locale.US).compare(name2, name2);

                // if the names are same, use the id
                if (compareResult == 0) {
                    final String id1 = o1.getId();
                    final String id2 = o2.getId();
                    if (id2 == null) {
                        compareResult = -1;
                    } else if (id1 == null) {
                        compareResult = 1;
                    } else {
                        compareResult = id1.compareTo(id2);
                    }
                }

                return compareResult;
            }

        }
    }

    /**
     * Factory method for creating a new RevisionDTO based on this controller.
     *
     * @param lastMod mod
     * @return dto
     */
    public RevisionDTO createRevisionDTO(FlowModification lastMod) {
        final Revision revision = lastMod.getRevision();

        // create the dto
        final RevisionDTO revisionDTO = new RevisionDTO();
        revisionDTO.setVersion(revision.getVersion());
        revisionDTO.setClientId(revision.getClientId());
        revisionDTO.setLastModifier(lastMod.getLastModifier());

        return revisionDTO;
    }

    public RevisionDTO createRevisionDTO(final Revision revision) {
        final RevisionDTO dto = new RevisionDTO();
        dto.setVersion(revision.getVersion());
        dto.setClientId(revision.getClientId());
        return dto;
    }

    public NodeDTO createNodeDTO(Node node, NodeHeartbeat nodeHeartbeat, List<Event> events, boolean primary) {
        final NodeDTO nodeDto = new NodeDTO();

        // populate node dto
        final NodeIdentifier nodeId = node.getNodeId();
        nodeDto.setNodeId(nodeId.getId());
        nodeDto.setAddress(nodeId.getApiAddress());
        nodeDto.setApiPort(nodeId.getApiPort());
        nodeDto.setStatus(node.getStatus().name());
        nodeDto.setPrimary(primary);
        final Date connectionRequested = new Date(node.getConnectionRequestedTimestamp());
        nodeDto.setConnectionRequested(connectionRequested);

        // only connected nodes have heartbeats
        if (nodeHeartbeat != null) {
            final Date heartbeat = new Date(nodeHeartbeat.getTimestamp());
            nodeDto.setHeartbeat(heartbeat);
            nodeDto.setNodeStartTime(new Date(nodeHeartbeat.getSystemStartTime()));
            nodeDto.setActiveThreadCount(nodeHeartbeat.getActiveThreadCount());
            nodeDto.setQueued(FormatUtils.formatCount(nodeHeartbeat.getFlowFileCount()) + " / " + FormatUtils.formatDataSize(nodeHeartbeat.getFlowFileBytes()));
        }

        // populate node events
        final List<Event> nodeEvents = new ArrayList<>(events);
        Collections.sort(nodeEvents, new Comparator<Event>() {
            @Override
            public int compare(Event event1, Event event2) {
                return new Date(event2.getTimestamp()).compareTo(new Date(event1.getTimestamp()));
            }
        });

        // create the node event dtos
        final List<NodeEventDTO> nodeEventDtos = new ArrayList<>();
        for (final Event event : nodeEvents) {
            // create node event dto
            final NodeEventDTO nodeEventDto = new NodeEventDTO();
            nodeEventDtos.add(nodeEventDto);

            // populate node event dto
            nodeEventDto.setMessage(event.getMessage());
            nodeEventDto.setCategory(event.getCategory().name());
            nodeEventDto.setTimestamp(new Date(event.getTimestamp()));
        }
        nodeDto.setEvents(nodeEventDtos);

        return nodeDto;
    }

    /* setters */
    public void setControllerServiceProvider(ControllerServiceProvider controllerServiceProvider) {
        this.controllerServiceProvider = controllerServiceProvider;
    }

    public void setAuthorizer(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    public void setEntityFactory(EntityFactory entityFactory) {
        this.entityFactory = entityFactory;
    }
}
