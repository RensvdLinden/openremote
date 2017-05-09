/*
 * Copyright 2017, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.manager.server.asset;

import org.apache.camel.builder.RouteBuilder;
import org.openremote.agent3.protocol.Protocol;
import org.openremote.container.Container;
import org.openremote.container.ContainerService;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.message.MessageBrokerSetupService;
import org.openremote.container.timer.TimerService;
import org.openremote.container.web.socket.WebsocketAuth;
import org.openremote.manager.server.agent.AgentService;
import org.openremote.manager.server.datapoint.AssetDatapointService;
import org.openremote.manager.server.event.EventService;
import org.openremote.manager.server.rules.RulesService;
import org.openremote.manager.server.security.ManagerIdentityService;
import org.openremote.manager.shared.security.ClientRole;
import org.openremote.model.asset.*;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.attribute.AttributeExecuteStatus;
import org.openremote.model.value.Values;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.openremote.agent3.protocol.Protocol.SENSOR_QUEUE;
import static org.openremote.manager.server.event.EventService.INCOMING_EVENT_TOPIC;
import static org.openremote.manager.server.event.EventService.getWebsocketAuth;
import static org.openremote.model.asset.agent.AgentLink.getAgentLink;

/**
 * Receives {@link AttributeEvent}s and processes them.
 * <p>
 * {@link AttributeEvent}s can come from various sources:
 * <ul>
 * <li>Protocol ({@link org.openremote.agent3.protocol.AbstractProtocol#updateLinkedAttribute})</li>
 * <li>User/Client initiated ({@link #updateAttributeValue})</li>
 * <li>Rules ({@link org.openremote.model.rules.Assets#dispatch})</li>
 * </ul>
 * The {@link AttributeEvent}s are first validated (link and value type checking) then they are
 * converted into {@link AssetState} messages which are then passed through the processing chain of consumers.
 * <p>
 * The regular processing chain is:
 * <ul>
 * <li>{@link RulesService}</li>
 * <li>{@link AgentService}</li>
 * <li>{@link AssetStorageService}</li>
 * <li>{@link AssetDatapointService}</li>
 * </ul>
 * <h2>Rules Service processing logic</h2>
 * <p>
 * Checks if attribute is {@link AssetAttribute#isRuleState} or {@link AssetAttribute#isRuleEvent}, and if
 * it does then the message is passed through the rule engines that are in scope for the asset.
 * <p>
 * For {@link AssetState} messages, the rules service keeps the facts and thus the state of each rules
 * knowledge session in sync with the asset state changes that occur. If an asset attribute value changes,
 * the {@link AssetState} in each rules session will be updated to reflect the change.
 * <p>
 * For {@link AssetEvent} messages, they are inserted in the rules sessions in scope
 * and expired automatically either by a) the rules session if no time-pattern can possibly match the event source
 * timestamp anymore or b) by the rules service if the event lifetime set in {@link RulesService#RULE_EVENT_EXPIRES} is
 * reached or c) by the rules service if the event lifetime set in the attribute {@link AssetMeta#RULE_EVENT_EXPIRES}
 * is reached.
 * <h2>Agent service processing logic</h2>
 * <p>
 * When the attribute event came from a Protocol (i.e. generated by a call from
 * {@link org.openremote.agent3.protocol.AbstractProtocol#updateLinkedAttribute} then
 * the agent service will ignore the message and just pass it onto the next consumer.
 * <p>
 * When the attribute event came from any other source then the agent service will check if it relates
 * to a {@link AssetType#THING} and if so it will check if the attribute is linked to an {@link AssetType#AGENT}.
 * If it is not linked to an agent then it ignores the message and just passes it to the next consumer. If it
 * is linked to an agent and the agent link is invalid then the message status is set to
 * {@link AssetState.ProcessingStatus#ERROR} and the message will not be able to progress through the processing chain.
 * <p>
 * If the message is for a valid linked agent then an {@link AttributeEvent} is sent on the
 * {@link org.openremote.agent3.protocol.AbstractProtocol#ACTUATOR_TOPIC} which the protocol will receive in
 * {@link org.openremote.agent3.protocol.AbstractProtocol#processLinkedAttributeWrite} for execution on an actual device or
 * service 'things'.
 * <p>
 * This means that a protocol implementation is responsible for producing a new {@link AttributeEvent} to
 * indicate to the system that the attribute value has/has not changed. The protocol should know best when to
 * do this and will vary from protocol to protocol; some 'things' might respond to an actuator command immediately
 * with a new sensor read, or they might send a separate sensor changed message or both or neither (fire and
 * forget). The protocol must decide what the best course of action is based on the 'things' it communicates with
 * and the transport layer it uses etc.
 * <h2>Asset Storage Service processing logic</h2>
 * <p>
 * Always tries to persist the attribute value in the DB and allows the message to continue if the commit was
 * successful.
 * <h2>Asset Datapoint Service processing logic</h2>
 * <p>
 * Checks if attribute has {@link org.openremote.model.asset.AssetMeta#STORE_DATA_POINTS} meta item with a value of true
 * and if it does then the {@link AttributeEvent} is stored in a time series DB. Then allows the message to continue
 * if the commit was successful. TODO Should the datapoint service only store northbound updates?
 */
public class AssetProcessingService extends RouteBuilder implements ContainerService {

    private static final Logger LOG = Logger.getLogger(AssetProcessingService.class.getName());

    // TODO: Some of these options should be configurable depending on expected load etc.
    // Message topic for communicating from client to asset/thing layer
    String ASSET_QUEUE = "seda://AssetQueue?waitForTaskToComplete=NEVER&purgeWhenStopping=true&discardIfNoConsumers=false&size=1000";

    protected TimerService timerService;
    protected ManagerIdentityService managerIdentityService;
    protected RulesService rulesService;
    protected AgentService agentService;
    protected AssetStorageService assetStorageService;
    protected AssetDatapointService assetDatapointService;
    protected MessageBrokerService messageBrokerService;
    protected EventService eventService;

    final protected List<Consumer<AssetState>> processors = new ArrayList<>();

    @Override
    public void init(Container container) throws Exception {
        timerService = container.getService(TimerService.class);
        managerIdentityService = container.getService(ManagerIdentityService.class);
        rulesService = container.getService(RulesService.class);
        agentService = container.getService(AgentService.class);
        assetStorageService = container.getService(AssetStorageService.class);
        assetDatapointService = container.getService(AssetDatapointService.class);
        messageBrokerService = container.getService(MessageBrokerService.class);
        eventService = container.getService(EventService.class);

        eventService.addSubscriptionAuthorizer((auth, subscription) -> {
            if (!subscription.isEventType(AttributeEvent.class)) {
                return false;
            }

            // Always must have a filter, as you can't subscribe to ALL asset attribute events
            if (subscription.getFilter() != null && subscription.getFilter() instanceof AttributeEvent.EntityIdFilter) {
                AttributeEvent.EntityIdFilter filter = (AttributeEvent.EntityIdFilter) subscription.getFilter();

                // If the asset doesn't exist, subscription must fail
                Asset asset = assetStorageService.find(filter.getEntityId());
                if (asset == null)
                    return false;

                // Superuser can get attribute events for any asset
                if (auth.isSuperUser())
                    return true;

                // Regular user must have role
                if (!auth.isUserInRole(ClientRole.READ_ASSETS.getValue())) {
                    return false;
                }

                if (managerIdentityService.isRestrictedUser(auth.getUserId())) {
                    // Restricted users can only get attribute events for their linked assets
                    if (assetStorageService.isUserAsset(auth.getUserId(), filter.getEntityId()))
                        return true;
                } else {
                    // Regular users can only get attribute events for assets in their realm
                    if (asset.getTenantRealm().equals(auth.getAuthenticatedRealm()))
                        return true;
                }
            }
            return false;
        });

        processors.add(rulesService);
        processors.add(agentService);
        processors.add(assetStorageService);
        processors.add(assetDatapointService);

        container.getService(MessageBrokerSetupService.class).getContext().addRoutes(this);
    }

    @Override
    public void start(Container container) throws Exception {

    }

    @Override
    public void stop(Container container) throws Exception {

    }

    @Override
    public void configure() throws Exception {

        // Process attribute events from protocols
        from(SENSOR_QUEUE)
            .filter(body().isInstanceOf(AttributeEvent.class))
            .process(exchange -> {
                AttributeEvent event = exchange.getIn().getBody(AttributeEvent.class);
                String protocolName = exchange.getIn().getHeader(
                    Protocol.SENSOR_QUEUE_SOURCE_PROTOCOL, "Unknown Protocol", String.class
                );
                LOG.fine("Received from protocol '" + protocolName + "' on sensor queue: " + event);
                // Can either come from onUpdateSensor or sendAttributeUpdate
                boolean isSensorUpdate = (boolean) exchange.getIn().getHeader("isSensorUpdate", true);
                if (isSensorUpdate) {
                    processSensorUpdate(protocolName, event);
                } else {
                    updateAttributeValue(event, true);
                }
            });

        // Process attribute events from clients
        from(ASSET_QUEUE)
            .filter(body().isInstanceOf(AttributeEvent.class))
            .process(exchange -> {
                AttributeEvent event = exchange.getIn().getBody(AttributeEvent.class);
                LOG.fine("Received on asset queue: " + event);
                updateAttributeValue(event, false);
            });

        // React if a client wants to write attribute state
        from(INCOMING_EVENT_TOPIC)
            .filter(body().isInstanceOf(AttributeEvent.class))
            .process(exchange -> {
                AttributeEvent event = exchange.getIn().getBody(AttributeEvent.class);
                LOG.fine("Handling from client: " + event);

                if (event.getEntityId() == null || event.getEntityId().isEmpty())
                    return;

                WebsocketAuth auth = getWebsocketAuth(exchange);

                // Superuser can write all
                if (auth.isSuperUser()) {
                    sendAttributeEvent(event);
                    return;
                }

                // Regular user must have role
                if (!auth.isUserInRole(ClientRole.WRITE_ASSETS.getValue())) {
                    return;
                }

                ServerAsset asset;
                // Restricted users can only write attribute events for their linked assets
                if (managerIdentityService.isRestrictedUser(auth.getUserId())) {
                    if (!assetStorageService.isUserAsset(auth.getUserId(), event.getEntityId()))
                        return;
                    asset = assetStorageService.find(event.getEntityId(), true, true);
                } else {
                    asset = assetStorageService.find(event.getEntityId(), true);
                }

                if (asset == null)
                    return;

                // Attribute must exist
                if (!asset.getAttribute(event.getAttributeName()).isPresent())
                    return;

                // Regular users can only write attribute events for assets in their realm
                if (!asset.getTenantRealm().equals(auth.getAuthenticatedRealm()))
                    return;

                // Put it on the asset queue
                sendAttributeEvent(event);
            });
    }

    public void sendAttributeEvent(AttributeEvent attributeEvent) {
        messageBrokerService.getProducerTemplate().sendBody(ASSET_QUEUE, attributeEvent);
    }

    /**
     * This is the entry point for any attribute value change event in the entire system.
     * <p>
     * Ingestion is asynchronous, through either
     * {@link org.openremote.agent3.protocol.Protocol#SENSOR_QUEUE} or {@link #ASSET_QUEUE}.
     * <p>
     * This deals with single attribute value changes and pushes them through the attribute event
     * processing chain where each consumer is given the opportunity to consume the event or allow
     * it progress to the next consumer {@link AssetState.ProcessingStatus}.
     * <p>
     * NOTE: An attribute value can be changed during Asset CRUD but this does not come through
     * this route but is handled separately see {@link AssetResourceImpl}. Any attribute values
     * assigned during Asset CRUD can be thought of as the attributes initial value and is subject
     * to change by the following actors (depending on attribute meta etc.) All actors use this
     * entry point to initiate an attribute value change: Sensor updates from protocols, attribute
     * write requests from clients, and attribute write dispatching as rules RHS action.
     */
    private void updateAttributeValue(AttributeEvent attributeEvent, boolean fromProtocol) {
        // Check this event relates to a valid asset
        ServerAsset asset = assetStorageService.find(attributeEvent.getEntityId(), true);

        if (asset == null) {
            LOG.warning("Processing event failed, asset not found: " + attributeEvent);
            return;
        }

        // Prevent editing of individual agent attributes
        if (asset.getWellKnownType() == AssetType.AGENT) {
            throw new IllegalArgumentException(
                "Agent attributes can not be updated individually, update the whole asset instead: " + asset
            );
        }

        // Pass attribute event through the processing chain
        LOG.fine("Processing " + attributeEvent + " for: " + asset);
        Optional<AssetAttribute> attribute = asset.getAttribute(attributeEvent.getAttributeName());

        if (!attribute.isPresent()) {
            LOG.warning("Ignoring " + attributeEvent + ", attribute doesn't exist on asset: " + asset);
            return;
        }

        // Prevent editing of read only attributes
        // TODO This also means a rule RHS can't write a read-only attribute with Assets#dispatch!
        // TODO should protocols be allowed to write to read-only attributes
        if (!fromProtocol && attribute.get().isReadOnly()) {
            LOG.warning("Ignoring " + attributeEvent + ", attribute is read-only in: " + asset);
            return;
        }

        // If attribute is marked as executable and not from northbound only allow write AttributeExecuteStatus to be sent
        if (!fromProtocol && attribute.get().isExecutable()) {
            Optional<AttributeExecuteStatus> status = attributeEvent.getValue()
                .flatMap(Values::getString)
                .flatMap(AttributeExecuteStatus::fromString);

            if (!status.isPresent()) {
                LOG.warning("Attribute event doesn't contain a valid AttributeExecuteStatus value: " + attributeEvent);
                return;
            }

            if (!status.get().isWrite()) {
                LOG.warning("Only AttributeExecuteStatus write value can be written to an attribute: " + attributeEvent);
                return;
            }
        }

        processUpdate(asset, attribute.get(), attributeEvent, false);
    }

    /**
     * We get here if a protocol pushes a sensor update message.
     */
    protected void processSensorUpdate(String protocolName, AttributeEvent attributeEvent) {
        ServerAsset asset = assetStorageService.find(attributeEvent.getEntityId(), true);

        if (asset == null) {
            LOG.warning(
                "Sensor update received from protocol '" + protocolName + "' for an asset that cannot be found: "
                    + attributeEvent.getEntityId()
            );
            return;
        }

        LOG.fine("Processing sensor " + attributeEvent + " for asset: " + asset);

        // Get the attribute and check it is actually linked to an agent (although the
        // event comes from a Protocol, we can not assume that the attribute is still linked,
        // consider a protocol that receives a batch of messages because a gateway was offline
        // for a day)
        AssetAttribute attribute = asset.getAttribute(attributeEvent.getAttributeName()).orElse(null);

        if (attribute == null) {
            LOG.warning(
                "Processing sensor update from protocol '" + protocolName
                    + "' failed, no attribute or not linked to an agent: " + attributeEvent
            );
            return;
        }

        Optional<AssetAttribute> protocolConfiguration =
            getAgentLink(attribute)
                .flatMap(agentService::getProtocolConfiguration);

        if (!protocolConfiguration.isPresent()) {
            LOG.warning(
                "Processing sensor update from protocol '" + protocolName
                    + "' failed, linked agent protocol configuration not found: " + attributeEvent
            );
            return;
        }

        // Protocols can write to readonly attributes (i.e. sensor attributes) so no need to check readonly flag
        processUpdate(asset, attribute, attributeEvent, true);
    }

    protected void processUpdate(ServerAsset asset,
                                 AssetAttribute attribute,
                                 AttributeEvent attributeEvent,
                                 boolean northbound) {
        // Ensure timestamp of event is not in the future as that would essentially block access to
        // the attribute until after that time (maybe that is desirable behaviour)
        // Allow a leniency of 1s
        long currentMillis = timerService.getCurrentTimeMillis();
        if (attributeEvent.getTimestamp() - currentMillis > 1000) {
            // TODO: Decide how to handle update events in the future - ignore or change timestamp
            LOG.warning("Ignoring future " + attributeEvent
                + ", current time: " + new Date(currentMillis) + "/" + currentMillis
                + ", event time: " + new Date(attributeEvent.getTimestamp()) + "/" + attributeEvent.getTimestamp() + " in: " + asset);
            return;
        }

        // Hold on to existing attribute state so we can use it during processing
        Optional<AttributeEvent> lastStateEvent = attribute.getStateEvent();

        // Check the last update timestamp of the attribute, ignoring any event that is older than last update
        // TODO: This means we drop out-of-sequence events, we might need better at-least-once handling
        if (lastStateEvent.isPresent() && lastStateEvent.get().getTimestamp() >= 0 && attributeEvent.getTimestamp() <= lastStateEvent.get().getTimestamp()) {
            LOG.warning("Ignoring outdated " + attributeEvent
                + ", last asset state time: " + lastStateEvent.map(event -> new Date(event.getTimestamp()).toString()).orElse("-1") + "/" + lastStateEvent.map(AttributeEvent::getTimestamp).orElse(-1L)
                + ", event time: " + new Date(attributeEvent.getTimestamp()) + "/" + attributeEvent.getTimestamp() + " in: " + asset);

            return;
        }

        // Set new value and event timestamp on attribute, thus validating any attribute constraints
        try {
            attribute.setValue(attributeEvent.getValue().orElse(null), attributeEvent.getTimestamp());
        } catch (IllegalArgumentException ex) {
            LOG.log(Level.WARNING, "Ignoring " + attributeEvent + ", attribute constraint violation in: " + asset, ex);
            return;
        }

        processUpdate(
            new AssetState(
                asset,
                attribute,
                lastStateEvent.flatMap(AttributeEvent::getValue).orElse(null),
                lastStateEvent.map(AttributeEvent::getTimestamp).orElse(-1L),
                northbound)
        );
    }

    protected void processUpdate(AssetState assetState) {
        try {
            long currentMillis = timerService.getCurrentTimeMillis();
            LOG.fine(">>> Processing start " +
                "(event time: " + new Date(assetState.getValueTimestamp()) + "/" + assetState.getValueTimestamp() +
                ", processing time: " + new Date(currentMillis) + "/" + currentMillis
                + ") : " + assetState);
            processorLoop:
            for (Consumer<AssetState> processor : processors) {
                try {
                    LOG.fine("==> Processor " + processor + " accepts: " + assetState);
                    processor.accept(assetState);
                } catch (Throwable t) {
                    LOG.log(Level.SEVERE, "Asset update consumer '" + processor + "' threw an exception whilst consuming: " + assetState, t);
                    assetState.setProcessingStatus(AssetState.ProcessingStatus.ERROR);
                    assetState.setError(t);
                }

                switch (assetState.getProcessingStatus()) {
                    case HANDLED:
                        LOG.fine("<== Processor " + processor + " finally handled: " + assetState);
                        break processorLoop;
                    case ERROR:
                        LOG.log(Level.SEVERE, "Processor " + processor + " error: " + assetState, assetState.getError());
                        break processorLoop;
                }
            }
            if (assetState.getProcessingStatus() != AssetState.ProcessingStatus.ERROR) {
                assetState.setProcessingStatus(AssetState.ProcessingStatus.COMPLETED);
                publishEvent(assetState);
            }

        } finally {
            LOG.fine("<<< Processing complete: " + assetState);
        }
    }

    protected void publishEvent(AssetState assetState) {
        eventService.publishEvent(
            new AttributeEvent(assetState.getId(), assetState.getAttributeName(), assetState.getValue())
        );
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
            '}';
    }
}
