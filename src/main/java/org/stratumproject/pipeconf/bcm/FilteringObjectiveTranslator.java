/*
 * Copyright 2020-present Open Networking Foundation
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.stratumproject.pipeconf.bcm;

import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.criteria.*;
import org.onosproject.net.flowobjective.FilteringObjective;

import static org.stratumproject.pipeconf.bcm.BcmPipelineUtils.criterion;

/**
 * The translator that translates FilteringObjective to flows
 * of the BCM pipeline.
 */
public class FilteringObjectiveTranslator
        extends AbstractObjectiveTranslator<FilteringObjective> {

    FilteringObjectiveTranslator(DeviceId deviceId,
                                 BcmPipelineCapabilities capabilities) {
        super(deviceId, capabilities);
    }

    @Override
    public ObjectiveTranslation doTranslate(FilteringObjective obj)
            throws BcmPipelinerException {
        ObjectiveTranslation.Builder resultBuilder = ObjectiveTranslation.builder();
        final EthCriterion ethDst = (EthCriterion) criterion(
                obj.conditions(), Criterion.Type.ETH_DST);

        if (ethDst != null) {
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchEthDst(ethDst.mac())
                    .build();

            resultBuilder.addFlowRule(
                    flowRule(obj,
                             BcmPipelineConstants.MY_STATION_TABLE,
                             selector,
                             DefaultTrafficTreatment.emptyTreatment())
            );
        }
        return resultBuilder.build();
    }
}
