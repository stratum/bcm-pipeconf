/*
 * Copyright 2020-present Open Networking Foundation
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.stratumproject.pipeconf.bcm;

import org.onlab.packet.VlanId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.VlanIdCriterion;
import org.onosproject.net.flow.instructions.L2ModificationInstruction;
import org.onosproject.net.flowobjective.DefaultNextTreatment;
import org.onosproject.net.flowobjective.NextObjective;
import org.onosproject.net.flowobjective.NextTreatment;
import org.onosproject.net.flowobjective.ObjectiveError;
import org.onosproject.net.group.*;
import org.onosproject.net.pi.model.PiActionProfileId;
import org.onosproject.net.pi.model.PiTableId;
import org.onosproject.net.pi.runtime.PiGroupKey;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The translator that translates NextObjective to
 * flows and groups of the BCM pipeline.
 */
public class NextObjectiveTranslator extends AbstractObjectiveTranslator<NextObjective> {

    private BcmObjectiveStore bcmObjectiveStore;

    NextObjectiveTranslator(DeviceId deviceId,
                            BcmPipelineCapabilities capabilities,
                            BcmObjectiveStore bcmObjectiveStore) {
        super(deviceId, capabilities);
        this.bcmObjectiveStore = bcmObjectiveStore;
    }

    @Override
    public ObjectiveTranslation doTranslate(NextObjective obj) throws BcmPipelinerException {
        final ObjectiveTranslation.Builder resultBuilder =
                ObjectiveTranslation.builder();
        switch (obj.type()) {
            case SIMPLE:
                simpleNext(obj, resultBuilder);
                break;
            case HASHED:
                hashedNext(obj, resultBuilder);
                break;
            case BROADCAST:
                log.warn("Unsupported NextObjective type '{}', ignore it", obj);
                break;
            default:
                log.warn("Unsupported NextObjective type '{}'", obj);
                return ObjectiveTranslation.ofError(ObjectiveError.UNSUPPORTED);
        }
        return resultBuilder.build();
    }


    private void simpleNext(NextObjective obj,
                            ObjectiveTranslation.Builder resultBuilder) throws BcmPipelinerException {
        // Next objective for will be hashed next is it's L3
        if (BcmPipelineUtils.isL3NextObj(obj)) {
            hashedNext(obj, resultBuilder);
        }

        // Otherwise we will hold it now and combine it to forwarding objective later.
    }

    private void hashedNext(NextObjective obj,
                            ObjectiveTranslation.Builder resultBuilder) throws BcmPipelinerException {
        if (BcmPipelineUtils.isMplsOp(obj, L2ModificationInstruction.L2SubType.MPLS_PUSH)) {
            // Push MPLS
            resultBuilder.addGroup(buildL3HashedGroup(obj, BcmPipelineConstants.L3_FWD_TABLE, BcmPipelineConstants.L3_FWD_WCMP_ACTION_PROFILE));
        } else if(BcmPipelineUtils.isMplsOp(obj, L2ModificationInstruction.L2SubType.MPLS_POP) ||
                  BcmPipelineUtils.isMplsOp(obj, L2ModificationInstruction.L2SubType.MPLS_LABEL) ||
                  withMplsSegmentRoutingMeta(obj.meta())) {
            // Swap or pop MPLS
            resultBuilder.addGroup(buildL3HashedGroup(obj, BcmPipelineConstants.L3_MPLS_TABLE, BcmPipelineConstants.L3_FWD_MPLS_ECMP_ACTION_PROFILE));
        } else {
            // Normal L3 next
            resultBuilder.addGroup(buildL3HashedGroup(obj, BcmPipelineConstants.L3_FWD_TABLE, BcmPipelineConstants.L3_FWD_WCMP_ACTION_PROFILE));
        }
    }

    private boolean withMplsSegmentRoutingMeta(TrafficSelector meta) {
        return ForwardingFunctionType.matchFft(meta.criteria(), ForwardingFunctionType.MPLS_SEGMENT_ROUTING);
    }

    private DefaultGroupDescription buildL3HashedGroup(NextObjective obj,
                                                       PiTableId tableId,
                                                       PiActionProfileId actionProfileId) {
        final VlanIdCriterion vlanIdCriterion = obj.meta() == null ? null
                : (VlanIdCriterion) BcmPipelineUtils.criterion(obj.meta().criteria(), Criterion.Type.VLAN_VID);
        final VlanId vlanId = vlanIdCriterion == null ? null : vlanIdCriterion.vlanId();

        final List<TrafficTreatment> piTreatments = obj.nextTreatments().stream()
                .filter(nt -> nt.type() == NextTreatment.Type.TREATMENT)
                .map(nt -> (DefaultNextTreatment)nt)
                .map(DefaultNextTreatment::treatment)
                .map(t -> {
                    if (vlanId != null) {
                        // Include VLAN ID
                        t = DefaultTrafficTreatment.builder(t)
                                .setVlanId(vlanIdCriterion.vlanId())
                                .build();
                    }
                    try {
                        return mapTreatmentToPiIfNeeded(t, tableId);
                    } catch (BcmPipelinerException e) {
                        log.warn(e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        final List<GroupBucket> bucketList = piTreatments.stream()
                .map(DefaultGroupBucket::createSelectGroupBucket)
                .collect(Collectors.toList());

        final int groupId = obj.id();
        final PiGroupKey groupKey = new PiGroupKey(tableId, actionProfileId, groupId);
        return new DefaultGroupDescription(
                deviceId,
                GroupDescription.Type.SELECT,
                new GroupBuckets(bucketList),
                groupKey,
                groupId,
                obj.appId()
        );
    }


}
