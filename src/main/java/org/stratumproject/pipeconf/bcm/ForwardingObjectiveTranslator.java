/*
 * Copyright 2020-present Open Networking Foundation
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.stratumproject.pipeconf.bcm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.*;
import org.onosproject.net.flow.criteria.*;
import org.onosproject.net.flowobjective.*;
import org.onosproject.net.group.*;
import org.onosproject.net.pi.runtime.PiAction;
import org.onosproject.net.pi.runtime.PiActionParam;
import org.onosproject.net.pi.runtime.PiActionProfileGroupId;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static org.onosproject.net.group.DefaultGroupBucket.createCloneGroupBucket;
import static org.stratumproject.pipeconf.bcm.BcmPipelineConstants.*;

/**
 * The translator that translates ForwardingObjective to
 * flows of the BCM pipeline.
 */
public class ForwardingObjectiveTranslator
        extends AbstractObjectiveTranslator<ForwardingObjective> {

    private static final Set<Criterion.Type> PUNT_CRITERIA = ImmutableSet.of(
            Criterion.Type.IN_PORT,
            Criterion.Type.IN_PHY_PORT,
            Criterion.Type.ETH_TYPE,
            Criterion.Type.IPV4_SRC,
            Criterion.Type.IPV4_DST,
            Criterion.Type.IP_PROTO,
            Criterion.Type.ICMPV4_CODE,
            Criterion.Type.VLAN_VID,
            Criterion.Type.VLAN_PCP,
            Criterion.Type.PROTOCOL_INDEPENDENT);
    private static final int CLONE_TO_CPU_ID = 511;

    private BcmObjectiveStore bcmObjectiveStore;

    ForwardingObjectiveTranslator(DeviceId deviceId,
                                  BcmPipelineCapabilities capabilities,
                                  BcmObjectiveStore bcmObjectiveStore) {
        super(deviceId, capabilities);
        this.bcmObjectiveStore = bcmObjectiveStore;
    }

    @Override
    public ObjectiveTranslation doTranslate(ForwardingObjective obj) throws BcmPipelinerException {
        final ObjectiveTranslation.Builder resultBuilder =
                ObjectiveTranslation.builder();
        switch (obj.flag()) {
            case SPECIFIC:
                processSpecificFwd(obj, resultBuilder);
                break;
            case VERSATILE:
                processVersatileFwd(obj, resultBuilder);
                break;
            case EGRESS:
            default:
                log.warn("Unsupported ForwardingObjective type '{}'", obj.flag());
                return ObjectiveTranslation.ofError(ObjectiveError.UNSUPPORTED);
        }
        return resultBuilder.build();
    }

    private void processSpecificFwd(ForwardingObjective obj,
                                    ObjectiveTranslation.Builder resultBuilder) throws BcmPipelinerException {

        final Set<Criterion> criteriaWithMeta = Sets.newHashSet(obj.selector().criteria());

        // FIXME: Is this really needed? Meta is such an ambiguous field...
        // Why would we match on a META field?
        if (obj.meta() != null) {
            criteriaWithMeta.addAll(obj.meta().criteria());
        }

        final ForwardingFunctionType fft = ForwardingFunctionType.getForwardingFunctionType(obj);

        switch (fft.type()) {
            case UNKNOWN:
                throw new BcmPipelinerException(
                        "unable to detect forwarding function type");
            case L2_UNICAST:
                bridgingRule(obj, criteriaWithMeta, resultBuilder);
                break;
            case IPV4_ROUTING:
                ipv4RoutingRule(obj, criteriaWithMeta, resultBuilder);
                break;
            case MPLS_SEGMENT_ROUTING:
                mplsRule(obj, criteriaWithMeta, resultBuilder);
                break;
            case L2_BROADCAST:
            case IPV4_ROUTING_MULTICAST:
                log.warn("unsupported forwarding function type '{}', ignore it", fft.type());
                break;
            case IPV6_ROUTING:
            case IPV6_ROUTING_MULTICAST:
            default:
                throw new BcmPipelinerException(format(
                        "unsupported forwarding function type '%s'", fft.type()));
        }
    }

    private void bridgingRule(ForwardingObjective obj, Set<Criterion> criteriaWithMeta,
                              ObjectiveTranslation.Builder resultBuilder)
            throws BcmPipelinerException {

        NextObjective nextObj = bcmObjectiveStore.popStoredNextObjective(obj.nextId());
        checkNotNull(nextObj);

        TrafficTreatment treatment = nextObj.nextTreatments().stream()
                .filter(t -> t.type() == NextTreatment.Type.TREATMENT)
                .map(t -> (DefaultNextTreatment)t)
                .map(DefaultNextTreatment::treatment)
                .findFirst()
                .orElse(null);

        if (treatment == null) {
            throw new BcmPipelinerException(
                    format("Unable to find next treatment for l2 unicast objective %s", obj.toString()));
        }

        PortNumber outputPort = BcmPipelineUtils.outputPort(treatment);
        if (outputPort == null) {
            throw new BcmPipelinerException(
                    format("Unable to find output port from treatment %s", treatment));
        }

        // L2 unicast table
        // Match: eth dst
        // Action: set egress port
        EthCriterion ethMatch = (EthCriterion) obj.selector().getCriterion(Criterion.Type.ETH_DST);
        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchEthDst(ethMatch.mac())
                .build();
        treatment = DefaultTrafficTreatment.builder()
                .setOutput(outputPort)
                .build();

        resultBuilder.addFlowRule(flowRule(obj, L2_UNICAST_TABLE, selector, treatment));
    }

    private void ipv4RoutingRule(ForwardingObjective obj, Set<Criterion> criteriaWithMeta,
                                 ObjectiveTranslation.Builder resultBuilder)
            throws BcmPipelinerException {

        IPCriterion ipDstCriterion = criteriaWithMeta.stream()
            .filter(c -> c.type() == Criterion.Type.IPV4_DST)
            .map(c -> (IPCriterion)c)
            .findFirst()
            .orElse(null);

        checkNotNull(ipDstCriterion);
        TrafficSelector selector = DefaultTrafficSelector.builder()
            .matchIPDst(ipDstCriterion.ip())
            .matchPi(PiCriterion.builder().matchExact(LOCAL_METADATA_VRF_ID, DEFAULT_VRF_ID).build())
            .build();

        TrafficTreatment treatment = actionProfileGroupTreatmentFromNextId(obj.nextId());

        // l3_fwd_table
        resultBuilder.addFlowRule(flowRule(
                obj,
                L3_FWD_TABLE,
                selector,
                treatment
        ));
    }

    private void mplsRule(ForwardingObjective obj, Set<Criterion> criteriaWithMeta,
                          ObjectiveTranslation.Builder resultBuilder)
            throws BcmPipelinerException {

        // match MPLS label from next objective and all possible ingress ports
        MplsCriterion mplsCriterion = criteriaWithMeta.stream()
            .filter(c -> c.type() == Criterion.Type.MPLS_LABEL)
            .map(c -> (MplsCriterion)c)
            .findFirst()
            .orElse(null);
        checkNotNull(mplsCriterion);

        TrafficTreatment treatment = actionProfileGroupTreatmentFromNextId(obj.nextId());
        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchMplsLabel(mplsCriterion.label())
                .build();
        resultBuilder.addFlowRule(flowRule(
                obj,
                L3_MPLS_TABLE,
                selector,
                treatment
        ));
    }

    private TrafficTreatment actionProfileGroupTreatmentFromNextId(int nextId) {
        return DefaultTrafficTreatment.builder()
                .piTableAction(PiActionProfileGroupId.of(nextId))
                .build();
    }

    private void processVersatileFwd(ForwardingObjective obj,
                                     ObjectiveTranslation.Builder resultBuilder)
            throws BcmPipelinerException {
        final Set<Criterion.Type> unsupportedCriteria = obj.selector().criteria()
                .stream()
                .map(Criterion::type)
                .filter(t -> !PUNT_CRITERIA.contains(t))
                .collect(Collectors.toSet());

        if (!unsupportedCriteria.isEmpty()) {
            log.warn("unsupported punt criteria {}", unsupportedCriteria.toString());
            return;
        }

        PortNumber outPort = BcmPipelineUtils.outputPort(obj.treatment());

        final PiAction puntAction;
        if (outPort != null
                && outPort.equals(PortNumber.CONTROLLER)
                && obj.treatment().allInstructions().size() == 1) {
            if (obj.treatment().clearedDeferred()) {
                // Send to CPU
                puntAction = PiAction.builder()
                        .withId(PUNT_SET_QUEUE_AND_SEND_TO_CPU)
                        .withParameter(DEFAULT_QUEUE_ID)
                        .build();
            } else {
                // Action is SET_CLONE_SESSION_ID
                if (obj.op() == Objective.Operation.ADD) {
                    // Action is ADD, create clone group
                    final DefaultGroupDescription cloneGroup =
                            createCloneGroup(obj.appId(),
                                    CLONE_TO_CPU_ID,
                                    outPort);
                    resultBuilder.addGroup(cloneGroup);
                }

                // Clone to CPU
                puntAction = PiAction.builder()
                        .withId(PUNT_SET_QUEUE_AND_CLONE_TO_CPU)
                        .withParameter(DEFAULT_QUEUE_ID)
                        .build();
            }
        } else {
            // Set egress port
            puntAction = PiAction.builder()
                    .withId(PUNT_SET_EGRESS_PORT)
                    .withParameter(new PiActionParam(PORT, outPort.toLong()))
                    .build();
        }

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
            .piTableAction(puntAction)
            .build();

        resultBuilder.addFlowRule(flowRule(obj, PUNT_TABLE, obj.selector(), treatment));
    }

    private DefaultGroupDescription createCloneGroup(
            ApplicationId appId,
            int cloneSessionId,
            PortNumber outPort) {
        final GroupKey groupKey = new DefaultGroupKey(
                BcmPipeliner.KRYO.serialize(cloneSessionId));

        final List<GroupBucket> bucketList = ImmutableList.of(
                createCloneGroupBucket(DefaultTrafficTreatment.builder()
                        .setOutput(outPort)
                        .build()));

        final DefaultGroupDescription cloneGroup = new DefaultGroupDescription(
                deviceId, GroupDescription.Type.CLONE,
                new GroupBuckets(bucketList),
                groupKey, cloneSessionId, appId);
        return cloneGroup;
    }
}
