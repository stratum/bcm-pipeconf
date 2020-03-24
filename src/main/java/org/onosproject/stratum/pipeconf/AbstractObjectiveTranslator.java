/*
 * Copyright 2020-present Open Networking Foundation
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.onosproject.stratum.pipeconf;

import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.*;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flowobjective.Objective;
import org.onosproject.net.flowobjective.ObjectiveError;
import org.onosproject.net.pi.model.PiPipelineInterpreter;
import org.onosproject.net.pi.model.PiTableId;
import org.onosproject.net.pi.runtime.PiAction;
import org.slf4j.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Abstract implementation of a ObjectiveTranslator logic for the BCM pipeconf.
 */
abstract class AbstractObjectiveTranslator<T extends Objective> {

    protected final Logger log = getLogger(this.getClass());

    protected final DeviceId deviceId;
    protected final BcmPipelineCapabilities capabilities;
    protected final PiPipelineInterpreter interpreter;

    AbstractObjectiveTranslator(DeviceId deviceId, BcmPipelineCapabilities capabilities) {
        this.deviceId = checkNotNull(deviceId);
        this.capabilities = capabilities;
        this.interpreter = new BcmPipelineInterpreter(capabilities);
    }

    public ObjectiveTranslation translate(T obj) {
        try {
            return doTranslate(obj);
        } catch (BcmPipelinerException e) {
            log.warn("Cannot translate {}: {} [{}]",
                     obj.getClass().getSimpleName(), e.getMessage(), obj);
            return ObjectiveTranslation.ofError(e.objectiveError());
        }
    }

    public abstract ObjectiveTranslation doTranslate(T obj)
            throws BcmPipelinerException;

    public FlowRule flowRule(T obj, PiTableId tableId, TrafficSelector selector,
                             TrafficTreatment treatment)
            throws BcmPipelinerException {
        return flowRule(obj, tableId, selector, treatment, obj.priority());
    }

    public FlowRule flowRule(T obj, PiTableId tableId, TrafficSelector selector,
                             TrafficTreatment treatment, Integer priority)
            throws BcmPipelinerException {
        return DefaultFlowRule.builder()
                .withSelector(selector)
                .withTreatment(mapTreatmentToPiIfNeeded(treatment, tableId))
                .forTable(tableId)
                .makePermanent()
                .withPriority(priority)
                .forDevice(deviceId)
                .fromApp(obj.appId())
                .build();
    }

    TrafficTreatment mapTreatmentToPiIfNeeded(TrafficTreatment treatment, PiTableId tableId)
            throws BcmPipelinerException {
        if (isTreatmentPi(treatment)) {
            return treatment;
        }
        final PiAction piAction;
        try {
            piAction = interpreter.mapTreatment(treatment, tableId);
        } catch (PiPipelineInterpreter.PiInterpreterException ex) {
            throw new BcmPipelinerException(
                    format("Unable to map treatment for table '%s': %s",
                           tableId, ex.getMessage()),
                    ObjectiveError.UNSUPPORTED);
        }
        return DefaultTrafficTreatment.builder()
                .piTableAction(piAction)
                .build();
    }

    private boolean isTreatmentPi(TrafficTreatment treatment) {
        return treatment.allInstructions().size() == 1
                && treatment.allInstructions().get(0).type() == Instruction.Type.PROTOCOL_INDEPENDENT;
    }
}
