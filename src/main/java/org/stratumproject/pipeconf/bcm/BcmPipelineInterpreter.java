/*
 * Copyright 2020-present Open Networking Foundation
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.stratumproject.pipeconf.bcm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.onlab.packet.DeserializationException;
import org.onlab.packet.Ethernet;
import org.onlab.util.ImmutableByteSequence;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.driver.AbstractHandlerBehaviour;
import org.onosproject.net.driver.DriverHandler;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.flow.instructions.L2ModificationInstruction.ModEtherInstruction;
import org.onosproject.net.flow.instructions.L2ModificationInstruction.ModMplsLabelInstruction;
import org.onosproject.net.flow.instructions.L2ModificationInstruction.ModVlanIdInstruction;
import org.onosproject.net.packet.*;
import org.onosproject.net.pi.model.*;
import org.onosproject.net.pi.runtime.*;
import org.onosproject.net.pi.service.PiPipeconfService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static org.onlab.util.ImmutableByteSequence.copyFrom;
import static org.onosproject.net.PortNumber.FLOOD;
import static org.onosproject.net.flow.instructions.Instruction.Type.OUTPUT;
import static org.onosproject.net.flow.instructions.L2ModificationInstruction.L2SubType.*;
import static org.onosproject.net.flow.instructions.L2ModificationInstruction.L2SubType.MPLS_LABEL;
import static org.onosproject.net.pi.model.PiPacketOperationType.PACKET_OUT;
import static org.stratumproject.pipeconf.bcm.BcmPipelineUtils.l2Instruction;

/**
 * The pipeline interpreter of BCM pipeline.
 */
public class BcmPipelineInterpreter extends AbstractHandlerBehaviour implements PiPipelineInterpreter {

    private static final Logger log = LoggerFactory.getLogger(BcmPipelineInterpreter.class);
    private static final ImmutableMap<Criterion.Type, PiMatchFieldId> CRITERION_MAP =
            ImmutableMap.<Criterion.Type, PiMatchFieldId>builder()
                    .put(Criterion.Type.IN_PORT, BcmPipelineConstants.STANDARD_METADATA_INGRESS_PORT)
                    .put(Criterion.Type.IN_PHY_PORT, BcmPipelineConstants.STANDARD_METADATA_INGRESS_PORT)
                    .put(Criterion.Type.ACTSET_OUTPUT, BcmPipelineConstants.STANDARD_METADATA_EGRESS_SPEC)
                    .put(Criterion.Type.ETH_TYPE, BcmPipelineConstants.HDR_ETHERNET_ETHER_TYPE)
                    .put(Criterion.Type.IPV4_SRC, BcmPipelineConstants.HDR_IPV4_BASE_SRC_ADDR)
                    .put(Criterion.Type.IPV4_DST, BcmPipelineConstants.HDR_IPV4_BASE_DST_ADDR)
                    .put(Criterion.Type.IP_PROTO, BcmPipelineConstants.HDR_IPV4_BASE_PROTOCOL)
                    .put(Criterion.Type.ICMPV4_CODE, BcmPipelineConstants.LOCAL_METADATA_ICMP_CODE)
                    .put(Criterion.Type.VLAN_VID, BcmPipelineConstants.HDR_VLAN_TAG_VID)
                    .put(Criterion.Type.VLAN_PCP, BcmPipelineConstants.HDR_VLAN_TAG_PCP)
                    .put(Criterion.Type.ETH_DST, BcmPipelineConstants.HDR_ETHERNET_DST_ADDR)
                    .put(Criterion.Type.MPLS_LABEL, BcmPipelineConstants.HDR_MPLS_LABEL)
            .build();
    private BcmPipelineCapabilities capabilities;
    private DeviceService deviceService;
    private DeviceId deviceId;

    public BcmPipelineInterpreter() {
    }

    @Override
    public void setHandler(DriverHandler handler) {
        super.setHandler(handler);
        final PiPipeconfService pipeconfService = handler().get(PiPipeconfService.class);
        this.deviceId = handler().data().deviceId();
        this.capabilities = pipeconfService.getPipeconf(deviceId)
            .map(BcmPipelineCapabilities::new)
            .orElse(null);
        this.deviceService = handler().get(DeviceService.class);

    }

    public BcmPipelineInterpreter(BcmPipelineCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    @Override
    public Optional<PiMatchFieldId> mapCriterionType(Criterion.Type type) {
        return Optional.ofNullable(CRITERION_MAP.get(type));
    }

    @Override
    public Optional<PiTableId> mapFlowRuleTableId(int flowRuleTableId) {
        return Optional.empty();
    }

    @Override
    public PiAction mapTreatment(TrafficTreatment treatment,
                                 PiTableId piTableId) throws PiInterpreterException {
        PortNumber outPort = BcmPipelineUtils.outputPort(treatment);
        final ModEtherInstruction ethSrc =
                (ModEtherInstruction) l2Instruction(treatment, ETH_SRC);
        final ModEtherInstruction ethDst =
                (ModEtherInstruction) l2Instruction(treatment, ETH_DST);
        ModMplsLabelInstruction mplsLabel =
                (ModMplsLabelInstruction) l2Instruction(treatment, MPLS_LABEL);

        if (piTableId.equals(BcmPipelineConstants.MY_STATION_TABLE)) {
            return PiAction.builder().withId(BcmPipelineConstants.SET_L3_ADMIT).build();
        }

        if (piTableId.equals(BcmPipelineConstants.L2_UNICAST_TABLE)) {
            checkNotNull(outPort);
            PiActionParam param = new PiActionParam(BcmPipelineConstants.PORT, outPort.toLong());
            return PiAction.builder().withId(BcmPipelineConstants.L2_FWD_SET_EGRESS_PORT)
                    .withParameter(param)
                    .build();
        }

        if (piTableId.equals(BcmPipelineConstants.L3_FWD_TABLE)) {
            checkNotNull(outPort);
            checkNotNull(ethSrc);
            checkNotNull(ethDst);

            PiAction.Builder actionBuilder = PiAction.builder();
            actionBuilder.withParameter(new PiActionParam(BcmPipelineConstants.PORT, outPort.toLong()));
            actionBuilder.withParameter(new PiActionParam(BcmPipelineConstants.SMAC, ethSrc.mac().toBytes()));
            actionBuilder.withParameter(new PiActionParam(BcmPipelineConstants.DMAC, ethDst.mac().toBytes()));

            // encap_mpls(PortNum port, EthernetAddress smac, EthernetAddress dmac, bit<20> mpls_label, bit<8> mpls_ttl)
            if (mplsLabel != null) {
                actionBuilder.withParameter(new PiActionParam(BcmPipelineConstants.MPLS_LABEL, mplsLabel.label().toInt()));
                actionBuilder.withParameter(BcmPipelineConstants.DEFATUL_MPLS_TTL);

                return actionBuilder
                    .withId(BcmPipelineConstants.L3_FWD_ENCAP_MPLS)
                    .build();
            }

            // set_nexthop(PortNum port, EthernetAddress smac, EthernetAddress dmac, bit<12> dst_vlan)
            final ModVlanIdInstruction vlanId =
                    (ModVlanIdInstruction) l2Instruction(treatment, VLAN_ID);
            checkNotNull(vlanId);

            actionBuilder.withParameter(new PiActionParam(BcmPipelineConstants.DST_VLAN, vlanId.vlanId().toShort()));
            return actionBuilder
                .withId(BcmPipelineConstants.L3_FWD_SET_NEXTHOP)
                .build();
        }

        if (piTableId == BcmPipelineConstants.L3_MPLS_TABLE) {
            checkNotNull(outPort);
            checkNotNull(ethSrc);
            checkNotNull(ethDst);

            PiAction.Builder actionBuilder = PiAction.builder();
            actionBuilder.withParameter(new PiActionParam(BcmPipelineConstants.PORT, outPort.toLong()));
            actionBuilder.withParameter(new PiActionParam(BcmPipelineConstants.SMAC, ethSrc.mac().toBytes()));
            actionBuilder.withParameter(new PiActionParam(BcmPipelineConstants.DMAC, ethDst.mac().toBytes()));

            if (mplsLabel != null) {
                // swap_mpls(PortNum port, EthernetAddress smac, EthernetAddress dmac, bit<20> mpls_label)
                actionBuilder.withParameter(new PiActionParam(BcmPipelineConstants.MPLS_LABEL, mplsLabel.label().toInt()));
                return actionBuilder
                    .withId(BcmPipelineConstants.L3_FWD_SWAP_MPLS)
                    .build();
            }
            // decap_mpls(PortNum port, EthernetAddress smac, EthernetAddress dmac)
            return actionBuilder
                .withId(BcmPipelineConstants.L3_FWD_DECAP_MPLS)
                .build();
        }

        return null;
    }

    private PiPacketOperation createPiPacketOperation(ByteBuffer data, long portNumber)
            throws PiInterpreterException {
        try {
            PiPacketMetadata metadata = PiPacketMetadata.builder()
                    .withId(BcmPipelineConstants.HDR_PACKET_OUT_EGRESS_PHYSICAL_PORT)
                    .withValue(ImmutableByteSequence.copyFrom(portNumber).fit(BcmPipelineConstants.PORT_BITWIDTH)).build();
            return PiPacketOperation.builder()
                    .withType(PACKET_OUT)
                    .withData(copyFrom(data))
                    .withMetadatas(ImmutableList.of(metadata))
                    .build();
        } catch (ImmutableByteSequence.ByteSequenceTrimException e) {
            throw new PiInterpreterException(format(
                    "Port number '%d' too big, %s", portNumber, e.getMessage()));
        }
    }

    @Override
    public Collection<PiPacketOperation> mapOutboundPacket(OutboundPacket packet) throws PiInterpreterException {

        TrafficTreatment treatment = packet.treatment();

        // Packet-out in main.p4 supports only setting the output port,
        // i.e. we only understand OUTPUT instructions.
        List<Instructions.OutputInstruction> outInstructions = treatment
                .allInstructions()
                .stream()
                .filter(i -> i.type().equals(OUTPUT))
                .map(i -> (Instructions.OutputInstruction) i)
                .collect(toList());

        if (treatment.allInstructions().size() != outInstructions.size()) {
            // There are other instructions that are not of type OUTPUT.
            throw new PiInterpreterException("Treatment not supported: " + treatment);
        }

        ImmutableList.Builder<PiPacketOperation> builder = ImmutableList.builder();
        for (Instructions.OutputInstruction outInst : outInstructions) {
            if (outInst.port().isLogical() && !outInst.port().equals(FLOOD)) {
                throw new PiInterpreterException(format(
                        "Packet-out on logical port '%s' not supported",
                        outInst.port()));
            } else if (outInst.port().equals(FLOOD)) {
                // To emulate flooding, we create a packet-out operation for
                // each switch port.
                final DeviceService deviceService = handler().get(DeviceService.class);
                for (Port port : deviceService.getPorts(packet.sendThrough())) {
                    builder.add(buildPacketOut(packet.data(), port.number().toLong()));
                }
            } else {
                PortNumber outPortNumber = outInst.port();
                Port outPort = deviceService.getPort(deviceId, outPortNumber);

                if (outPort.type() != Port.Type.COPPER) {
                    // Ignore non-copper ports
                    log.debug("Ignore non-copper port {}", outPort);
                    continue;
                }

                // Create only one packet-out for the given OUTPUT instruction.
                builder.add(buildPacketOut(packet.data(), outInst.port().toLong()));
            }
        }
        return builder.build();
    }


    /**
     * Builds a pipeconf-specific packet-out instance with the given payload and
     * egress port.
     *
     * @param pktData    packet payload
     * @param portNumber egress port
     * @return packet-out
     * @throws PiInterpreterException if packet-out cannot be built
     */
    private PiPacketOperation buildPacketOut(ByteBuffer pktData, long portNumber)
            throws PiInterpreterException {

        // Make sure port number can fit in v1model port metadata bitwidth.
        final ImmutableByteSequence portBytes;
        try {
            portBytes = copyFrom(portNumber).fit(BcmPipelineConstants.PORT_BITWIDTH);
        } catch (ImmutableByteSequence.ByteSequenceTrimException e) {
            throw new PiInterpreterException(format(
                    "Port number %d too big, %s", portNumber, e.getMessage()));
        }

        // Create metadata instance for egress port.
        final String outPortMetadataName = "egress_physical_port";
        final PiPacketMetadata outPortMetadata = PiPacketMetadata.builder()
                .withId(PiPacketMetadataId.of(outPortMetadataName))
                .withValue(portBytes)
                .build();

        // Build packet out.
        return PiPacketOperation.builder()
                .withType(PACKET_OUT)
                .withData(copyFrom(pktData))
                .withMetadata(outPortMetadata)
                .build();
    }

    @Override
    public InboundPacket mapInboundPacket(PiPacketOperation packetIn, DeviceId deviceId) throws PiInterpreterException {

        // Find the ingress_port metadata.
        final String inportMetadataName = "ingress_physical_port";
        Optional<PiPacketMetadata> inportMetadata = packetIn.metadatas()
                .stream()
                .filter(meta -> meta.id().id().equals(inportMetadataName))
                .findFirst();

        if (inportMetadata.isEmpty()) {
            throw new PiInterpreterException(format(
                    "Missing metadata '%s' in packet-in received from '%s': %s",
                    inportMetadataName, deviceId, packetIn));
        }

        // Build ONOS InboundPacket instance with the given ingress port.

        // 1. Parse packet-in object into Ethernet packet instance.
        final byte[] payloadBytes = packetIn.data().asArray();
        final ByteBuffer rawData = ByteBuffer.wrap(payloadBytes);
        final Ethernet ethPkt;
        try {
            ethPkt = Ethernet.deserializer().deserialize(
                    payloadBytes, 0, packetIn.data().size());
        } catch (DeserializationException dex) {
            throw new PiInterpreterException(dex.getMessage());
        }

        // 2. Get ingress port
        final ImmutableByteSequence portBytes;
        try {
            portBytes = inportMetadata.get().value().fit(BcmPipelineConstants.PORT_BITWIDTH);
        } catch (ImmutableByteSequence.ByteSequenceTrimException e) {
            throw new PiInterpreterException(e.getMessage());
        }
        final short portNum = portBytes.asReadOnlyBuffer().getShort();
        final ConnectPoint receivedFrom = new ConnectPoint(
                deviceId, PortNumber.portNumber(portNum));

        return new DefaultInboundPacket(receivedFrom, ethPkt, rawData);
    }

    @Override
    public Optional<Integer> mapLogicalPortNumber(PortNumber port) {
        if (capabilities == null) {

        }
        if (port.equals(PortNumber.CONTROLLER)) {
            return capabilities.cpuPort();
        }
        return Optional.empty();
    }
}
