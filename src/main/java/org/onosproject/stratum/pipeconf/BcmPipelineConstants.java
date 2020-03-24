/*
 * Copyright 2020-present Open Networking Foundation
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.onosproject.stratum.pipeconf;

import org.onosproject.net.pi.model.*;
import org.onosproject.net.pi.runtime.PiActionParam;

/**
 * Constants of the BCM pipeline.
 */
public final class BcmPipelineConstants {

    private BcmPipelineConstants() {
    }

    public static final int PORT_BITWIDTH = 9;

    // Match fields
    public static final PiMatchFieldId STANDARD_METADATA_INGRESS_PORT = PiMatchFieldId.of("standard_metadata.ingress_port");
    public static final PiMatchFieldId STANDARD_METADATA_EGRESS_SPEC = PiMatchFieldId.of("standard_metadata.egress_spec");
    public static final PiMatchFieldId HDR_ETHERNET_ETHER_TYPE = PiMatchFieldId.of("hdr.ethernet.ether_type");
    public static final PiMatchFieldId HDR_IPV4_BASE_DIFFSERV = PiMatchFieldId.of("hdr.ipv4_base.diffserv");
    public static final PiMatchFieldId HDR_IPV4_BASE_TTL = PiMatchFieldId.of("hdr.ipv4_base.ttl");
    public static final PiMatchFieldId HDR_IPV4_BASE_SRC_ADDR = PiMatchFieldId.of("hdr.ipv4_base.src_addr");
    public static final PiMatchFieldId HDR_IPV4_BASE_DST_ADDR = PiMatchFieldId.of("hdr.ipv4_base.dst_addr");
    public static final PiMatchFieldId HDR_IPV4_BASE_PROTOCOL = PiMatchFieldId.of("hdr.ipv4_base.protocol");
    public static final PiMatchFieldId LOCAL_METADATA_ICMP_CODE = PiMatchFieldId.of("local_metadata.icmp_code");
    public static final PiMatchFieldId LOCAL_METADATA_CLASS_ID = PiMatchFieldId.of("local_metadata.class_id");
    public static final PiMatchFieldId LOCAL_METADATA_VRF_ID = PiMatchFieldId.of("local_metadata.vrf_id");
    public static final PiMatchFieldId HDR_VLAN_TAG_VID = PiMatchFieldId.of("hdr.vlan_tag[0].vid");
    public static final PiMatchFieldId HDR_VLAN_TAG_PCP = PiMatchFieldId.of("hdr.vlan_tag[0].pcp");
    public static final PiMatchFieldId HDR_MPLS_LABEL = PiMatchFieldId.of("hdr.mpls.label");
    public static final PiMatchFieldId HDR_ETHERNET_DST_ADDR = PiMatchFieldId.of("hdr.ethernet.dst_addr");

    // PacketIO metadata
    public static final PiPacketMetadataId HDR_PACKET_OUT_EGRESS_PHYSICAL_PORT = PiPacketMetadataId.of("egress_physical_port");
    public static final PiPacketMetadataId HDR_PACKET_IN_INGRESS_PHYSICAL_PORT = PiPacketMetadataId.of("ingress_physical_port");

    // Tables
    public static final PiTableId PUNT_TABLE = PiTableId.of("ingress.punt.punt_table");
    public static final PiTableId L3_FWD_TABLE = PiTableId.of("ingress.l3_fwd.l3_fwd_table");
    public static final PiTableId L3_MPLS_TABLE = PiTableId.of("ingress.l3_fwd.l3_mpls_table");
    public static final PiTableId L2_UNICAST_TABLE = PiTableId.of("ingress.l2_fwd.l2_unicast_table");
    public static final PiTableId MY_STATION_TABLE = PiTableId.of("ingress.my_station_table");

    // Actions
    public static final PiActionId NOP = PiActionId.of("nop");
    public static final PiActionId NOACTION = PiActionId.of("NoAction");
    public static final PiActionId L3_FWD_DROP = PiActionId.of("ingress.l3_fwd.drop");
    public static final PiActionId L3_FWD_SET_NEXTHOP = PiActionId.of("ingress.l3_fwd.set_nexthop");
    public static final PiActionId L3_FWD_ENCAP_MPLS = PiActionId.of("ingress.l3_fwd.encap_mpls");
    public static final PiActionId L3_FWD_SWAP_MPLS = PiActionId.of("ingress.l3_fwd.swap_mpls");
    public static final PiActionId L3_FWD_DECAP_MPLS = PiActionId.of("ingress.l3_fwd.decap_mpls");
    public static final PiActionId L2_FWD_SET_EGRESS_PORT = PiActionId.of("ingress.l2_fwd.set_egress_port");
    public static final PiActionId PUNT_SET_QUEUE_AND_CLONE_TO_CPU = PiActionId.of("ingress.punt.set_queue_and_clone_to_cpu");
    public static final PiActionId PUNT_SET_QUEUE_AND_SEND_TO_CPU = PiActionId.of("ingress.punt.set_queue_and_send_to_cpu");
    public static final PiActionId PUNT_SET_EGRESS_PORT = PiActionId.of("ingress.punt.set_egress_port");
    public static final PiActionId SET_L3_ADMIT = PiActionId.of("ingress.set_l3_admit");

    // Action params
    public static final PiActionParamId PORT = PiActionParamId.of("port");
    public static final PiActionParamId QUEUE_ID = PiActionParamId.of("queue_id");
    public static final PiActionParamId DMAC = PiActionParamId.of("dmac");
    public static final PiActionParamId SMAC = PiActionParamId.of("smac");
    public static final PiActionParamId MPLS_LABEL = PiActionParamId.of("mpls_label");
    public static final PiActionParamId MPLS_TTL = PiActionParamId.of("mpls_ttl");
    public static final PiActionParamId DST_VLAN = PiActionParamId.of("dst_vlan");

    // Action Profile IDs
    public static final PiActionProfileId L3_FWD_WCMP_ACTION_PROFILE =
            PiActionProfileId.of("ingress.l3_fwd.wcmp_action_profile");
    public static final PiActionProfileId L3_FWD_MPLS_ECMP_ACTION_PROFILE =
            PiActionProfileId.of("ingress.l3_fwd.mpls_ecmp_action_profile");

    // Default value
    public static short DEFAULT_VRF_ID = 0;
    public static final PiActionParam DEFAULT_QUEUE_ID = new PiActionParam(QUEUE_ID, 0);
    public static final PiActionParam DEFATUL_MPLS_TTL = new PiActionParam(MPLS_TTL, 63);
}
