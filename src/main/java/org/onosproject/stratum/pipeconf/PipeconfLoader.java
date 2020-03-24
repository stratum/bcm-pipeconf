/*
 * Copyright 2020-present Open Networking Foundation
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.onosproject.stratum.pipeconf;

import org.onosproject.core.CoreService;
import org.onosproject.net.behaviour.Pipeliner;
import org.onosproject.net.pi.model.*;
import org.onosproject.net.pi.service.PiPipeconfService;
import org.onosproject.p4runtime.model.P4InfoParser;
import org.onosproject.p4runtime.model.P4InfoParserException;
import org.osgi.service.component.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.net.URL;

/**
 * A component which registers the BCM pipeconf to ONOS.
 */
@Component(immediate = true, service = PipeconfLoader.class)
public class PipeconfLoader {

    public static final String PIPELINE_APP_NAME = "org.onosproject.stratum-bcm-pipeconf";
    private static final PiPipeconfId PIPECONF_ID =
            new PiPipeconfId("org.onosproject.pipelines.stratum.bcm");

    private static final Logger log =
            LoggerFactory.getLogger(PipeconfLoader.class.getName());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private PiPipeconfService piPipeconfService;

    @Activate
    protected void activate() {
        coreService.registerApplication(PIPELINE_APP_NAME);
        // Registers all pipeconf at component activation.
        try {
            piPipeconfService.register(buildFpmPipeconf());
        } catch (FileNotFoundException e) {
            log.warn("Unable to register pipeconf {}: {}",
                     PIPELINE_APP_NAME, e.getMessage());
        }
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        if (piPipeconfService.getPipeconf(PIPECONF_ID).isPresent()) {
            piPipeconfService.unregister(PIPECONF_ID);
        }
        log.info("Stopped");
    }

    private PiPipeconf buildFpmPipeconf() throws FileNotFoundException {
        final URL p4InfoUrl = this.getClass().getResource("/p4info.txt");
        final URL cpuPortUrl = this.getClass().getResource("/cpu-port.txt");
        final URL fpmBinUrl = this.getClass().getResource("/main.pb.bin");

        checkFileExists(p4InfoUrl, "/p4info.txt");
        checkFileExists(cpuPortUrl, "/cpu-port.txt");
        checkFileExists(fpmBinUrl, "/pipeline_config.bin");

        return DefaultPiPipeconf.builder()
                .withId(PIPECONF_ID)
                .withPipelineModel(parseP4Info(p4InfoUrl))
                .addBehaviour(PiPipelineInterpreter.class, BcmPipelineInterpreter.class)
                .addBehaviour(Pipeliner.class, BcmPipeliner.class)
                .addExtension(PiPipeconf.ExtensionType.P4_INFO_TEXT, p4InfoUrl)
                .addExtension(PiPipeconf.ExtensionType.CPU_PORT_TXT, cpuPortUrl)
                .addExtension(PiPipeconf.ExtensionType.STRATUM_FPM_BIN, fpmBinUrl)
                .build();
    }

    private static PiPipelineModel parseP4Info(URL p4InfoUrl) {
        try {
            return P4InfoParser.parse(p4InfoUrl);
        } catch (P4InfoParserException e) {
            // FIXME: propagate exception that can be handled by whoever is
            //  trying to build pipeconfs.
            throw new IllegalStateException(e);
        }
    }

    private void checkFileExists(URL url, String name)
            throws FileNotFoundException {
        if (url == null) {
            throw new FileNotFoundException(name);
        }
    }
}
