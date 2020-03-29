/*
 * Copyright 2020-present Open Networking Foundation
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.stratumproject.pipeconf.bcm;

import com.google.common.collect.ImmutableList;
import org.onlab.util.KryoNamespace;
import org.onlab.util.SharedExecutors;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.behaviour.NextGroup;
import org.onosproject.net.behaviour.Pipeliner;
import org.onosproject.net.behaviour.PipelinerContext;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.driver.AbstractHandlerBehaviour;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleOperations;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flowobjective.*;
import org.onosproject.net.group.GroupDescription;
import org.onosproject.net.group.GroupService;
import org.onosproject.net.pi.service.PiPipeconfService;
import org.onosproject.store.serializers.KryoNamespaces;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.stratumproject.pipeconf.bcm.BcmPipelineUtils.outputPort;
import static org.slf4j.LoggerFactory.getLogger;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Pipeliner implementation for the BCM pipeline.
 */
public class BcmPipeliner extends AbstractHandlerBehaviour implements Pipeliner {

    private static final Logger log = getLogger(BcmPipeliner.class);

    protected static final KryoNamespace KRYO = new KryoNamespace.Builder()
            .register(KryoNamespaces.API)
            .register(BcmNextGroup.class)
            .build("StratumBcmPipeliner");

    protected DeviceId deviceId;
    protected DeviceService deviceService;
    protected FlowRuleService flowRuleService;
    protected GroupService groupService;
    protected FlowObjectiveStore flowObjectiveStore;
    protected PiPipeconfService piPipeconfService;

    private FilteringObjectiveTranslator filteringTranslator;
    private ForwardingObjectiveTranslator forwardingTranslator;
    private NextObjectiveTranslator nextTranslator;

    // Let's handle one forwarding or next objective at a time
    private ReentrantLock fwdNextObjLock;
    private BcmObjectiveStore bcmObjectiveStore;

    private final ExecutorService callbackExecutor = SharedExecutors.getPoolThreadExecutor();

    @Override
    public void init(DeviceId deviceId, PipelinerContext context) {
        this.deviceId = deviceId;

        deviceService = context.directory().get(DeviceService.class);
        flowRuleService = context.directory().get(FlowRuleService.class);
        groupService = context.directory().get(GroupService.class);
        piPipeconfService = context.directory().get(PiPipeconfService.class);
        flowObjectiveStore = context.store();

        fwdNextObjLock = new ReentrantLock();
        bcmObjectiveStore = new BcmObjectiveStore();

        BcmPipelineCapabilities capabilities = piPipeconfService.getPipeconf(deviceId)
            .map(BcmPipelineCapabilities::new)
            .orElse(null);

        checkNotNull(capabilities);

        filteringTranslator = new FilteringObjectiveTranslator(deviceId, capabilities);
        forwardingTranslator = new ForwardingObjectiveTranslator(deviceId, capabilities, bcmObjectiveStore);
        nextTranslator = new NextObjectiveTranslator(deviceId, capabilities, bcmObjectiveStore);
    }

    @Override
    public void filter(FilteringObjective obj) {
        ObjectiveTranslation result = filteringTranslator.translate(obj);
        handleResult(obj, result);
    }

    @Override
    public void forward(ForwardingObjective obj) {
        try {
            fwdNextObjLock.lock();

            if (obj.nextId() != null && bcmObjectiveStore.popStoredNextObjective(obj.nextId()) == null) {
                // Next objective is not ready yet
                bcmObjectiveStore.putForwardingObjective(obj);
            }

            ObjectiveTranslation result = forwardingTranslator.translate(obj);
            handleResult(obj, result);
        } finally {
            fwdNextObjLock.unlock();
        }
    }

    @Override
    public void next(NextObjective obj) {
        if (obj.op() == Objective.Operation.VERIFY) {
            // TODO: support VERIFY operation
            log.debug("VERIFY operation not yet supported for NextObjective, will return success");
            success(obj);
            return;
        }

        if (obj.op() == Objective.Operation.MODIFY) {
            // TODO: support MODIFY operation
            log.warn("MODIFY operation not yet supported for NextObjective, will return failure :(");
            fail(obj, ObjectiveError.UNSUPPORTED);
            return;
        }

        try {
            fwdNextObjLock.lock();
            ObjectiveTranslation result = nextTranslator.translate(obj);
            handleResult(obj, result);

            bcmObjectiveStore.putNextObjective(obj);
        } finally {
            fwdNextObjLock.unlock();
        }

        // TODO: verify if this is fine or not
        Collection<ForwardingObjective> fwds = bcmObjectiveStore.popAssociatedFwdObjectives(obj.id());
        fwds.forEach(this::forward);
    }

    @Override
    public List<String> getNextMappings(NextGroup nextGroup) {
        final BcmNextGroup bcmNextGroup = KRYO.deserialize(nextGroup.data());
        return bcmNextGroup.nextMappings().stream()
                .map(m -> format("%s -> %s", bcmNextGroup.type(), m))
                .collect(Collectors.toList());
    }

    private void handleResult(Objective obj, ObjectiveTranslation result) {
        if (result.error().isPresent()) {
            fail(obj, result.error().get());
            return;
        }
        processGroups(obj, result.groups());
        processFlows(obj, result.flowRules());
        if (obj instanceof NextObjective) {
            handleNextGroup((NextObjective) obj);
        }
        success(obj);
    }

    private void processFlows(Objective objective, Collection<FlowRule> flowRules) {
        if (flowRules.isEmpty()) {
            return;
        }
        final FlowRuleOperations.Builder ops = FlowRuleOperations.builder();
        switch (objective.op()) {
            case ADD:
            case ADD_TO_EXISTING:
                flowRules.forEach(ops::add);
                break;
            case REMOVE:
            case REMOVE_FROM_EXISTING:
                flowRules.forEach(ops::remove);
                break;
            default:
                log.warn("Unsupported Objective operation '{}'", objective.op());
                return;
        }
        flowRuleService.apply(ops.build());
    }

    private void processGroups(Objective objective, Collection<GroupDescription> groups) {
        if (groups.isEmpty()) {
            return;
        }
        switch (objective.op()) {
            case ADD:
                groups.forEach(groupService::addGroup);
                break;
            case REMOVE:
                groups.forEach(group -> groupService.removeGroup(
                        deviceId, group.appCookie(), objective.appId()));
                break;
            case ADD_TO_EXISTING:
                groups.forEach(group -> groupService.addBucketsToGroup(
                        deviceId, group.appCookie(), group.buckets(),
                        group.appCookie(), group.appId())
                );
                break;
            case REMOVE_FROM_EXISTING:
                groups.forEach(group -> groupService.removeBucketsFromGroup(
                        deviceId, group.appCookie(), group.buckets(),
                        group.appCookie(), group.appId())
                );
                break;
            default:
                log.warn("Unsupported Objective operation {}", objective.op());
        }
    }

    private void handleNextGroup(NextObjective obj) {
        switch (obj.op()) {
            case REMOVE:
                removeNextGroup(obj);
                break;
            case ADD:
            case ADD_TO_EXISTING:
            case REMOVE_FROM_EXISTING:
            case MODIFY:
                putNextGroup(obj);
                break;
            case VERIFY:
                break;
            default:
                log.error("Unknown NextObjective operation '{}'", obj.op());
        }
    }

    private void removeNextGroup(NextObjective obj) {
        final NextGroup removed = flowObjectiveStore.removeNextGroup(obj.id());
        if (removed == null) {
            log.debug("NextGroup {} was not found in FlowObjectiveStore");
        }
    }

    private void putNextGroup(NextObjective obj) {
        final List<String> nextMappings = obj.nextTreatments().stream()
                .map(this::nextTreatmentToMappingString)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        final BcmNextGroup nextGroup = new BcmNextGroup(obj.type(), nextMappings, obj.nextTreatments());
        flowObjectiveStore.putNextGroup(obj.id(), nextGroup);
    }

    private String nextTreatmentToMappingString(NextTreatment n) {
        switch (n.type()) {
            case TREATMENT:
                final PortNumber p = outputPort(n);
                return p == null ? "UNKNOWN"
                        : format("OUTPUT:%s", p.toString());
            case ID:
                final IdNextTreatment id = (IdNextTreatment) n;
                return format("NEXT_ID:%d", id.nextId());
            default:
                log.warn("Unknown NextTreatment type '{}'", n.type());
                return "???";
        }
    }

    /**
     * NextGroup implementation.
     */
    public class BcmNextGroup implements NextGroup {

        private final NextObjective.Type type;
        private final List<String> nextMappings;
        Collection<NextTreatment> nextTreatments;

        BcmNextGroup(NextObjective.Type type, List<String> nextMappings, Collection<NextTreatment> nextTreatments) {
            this.type = type;
            this.nextMappings = ImmutableList.copyOf(nextMappings);
            this.nextTreatments = ImmutableList.copyOf(nextTreatments);
        }

        NextObjective.Type type() {
            return type;
        }

        Collection<String> nextMappings() {
            return nextMappings;
        }

        public Collection<NextTreatment> nextTreatments() {
            return nextTreatments;
        }

        @Override
        public byte[] data() {
            return KRYO.serialize(this);
        }
    }

    private void fail(Objective objective, ObjectiveError error) {
        CompletableFuture.runAsync(
                () -> objective.context().ifPresent(
                        ctx -> ctx.onError(objective, error)), callbackExecutor);

    }

    private void success(Objective objective) {
        CompletableFuture.runAsync(
                () -> objective.context().ifPresent(
                        ctx -> ctx.onSuccess(objective)), callbackExecutor);
    }
}
