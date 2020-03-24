/*
 * Copyright 2020-present Open Networking Foundation
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.onosproject.stratum.pipeconf;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.NextObjective;

import java.util.Collection;
import java.util.Map;

/**
 * Local cache which stores NextObjectives and ForwardingObjectives.
 */
public class BcmObjectiveStore {
    private Multimap<Integer, ForwardingObjective> nextIdToFwdObjectives;
    private Map<Integer, NextObjective> nextObjectives;

    public BcmObjectiveStore() {
        nextIdToFwdObjectives = HashMultimap.create();
        nextObjectives = Maps.newHashMap();
    }

    public void putForwardingObjective(ForwardingObjective obj) {
        if (obj.nextId() != null) {
            nextIdToFwdObjectives.put(obj.nextId(), obj);
        }
    }

    public void putNextObjective(NextObjective obj) {
        nextObjectives.put(obj.id(), obj);
    }


    public Collection<ForwardingObjective> popAssociatedFwdObjectives(int nextId) {
        return nextIdToFwdObjectives.removeAll(nextId);
    }

    public NextObjective popStoredNextObjective(int nextId) {
        return nextObjectives.remove(nextId);
    }
}
