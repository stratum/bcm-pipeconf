Stratum BCM Pipeconf
----

[![<Stratum>](https://circleci.com/gh/stratum/bcm-pipeconf.svg?style=svg)](https://circleci.com/gh/stratum/bcm-pipeconf)

This repository contains ONOS pipeconf code and script for Stratum BCM pipeline.

## Requirements

 - [Stratum](stratum)
 - Docker (to run the build scripts without worrying about dependencies)
 - cURL (to interact with the ONOS REST APIs)

## Steps to build Stratum BCM pipeconf

ONOS uses "pipeconfs" to deploy and manage a given P4 program on a device.
Pipeconfs are distrubuted as ONOS applications, hence using the `.oar` packaging.
The following steps provide instructions on how to generate an oar package that includes a compiled version of `main.p4`
that works on Stratum with fixed pipeline ASIC such as Broadcom Tomahawk or Trident2.

 - `src/main/java`: contains Java code that implements the ONOS app responsible for registering the pipeconf in ONOS.

To learn more about pipeconfs and how ONOS supports Stratum devices:
<https://github.com/opennetworkinglab/ngsdn-tutorial>

### 1 - Obtain main.p4 source code from Stratum

`main.p4` is distributed as part of Stratum. We recommend using the `master` branch.

```bash
git clone https://github.com/stratum/stratum
```

Set the `STRATUM_ROOT` env variable to the location where Stratum was cloned:

```bash
export STRATUM_ROOT="$PWD/stratum"
```

### 2 - Build the pipeconf

To build `main.p4` using Stratum FPM compiler and to create the pipeconf `.oar` package:

```bash
cd bcm-pipeconf # this repo
make pipeconf
``` 

The P4 compiler outputs to include in the `.oar` package (such as main.pb.bin, main.pb.txt, and p4info.txt)
will be placed under `src/main/resources`.

## Steps to use the BCM pipeconf with ONOS

### 1 - Get and run ONOS

The minimum required ONOS version that works with this pipeconf is 2.2.1.

You can either build from sources (using the onos-2.2 or master branch), or
run one the released versions:
<https://wiki.onosproject.org/display/ONOS/Downloads>

Pre-built ONOS Docker images are available here:
<https://hub.docker.com/r/onosproject/onos/tags>

For more information on how to get and run ONOS:
<https://wiki.onosproject.org/display/ONOS/Guides>

### 2 - Start Stratum on your switch

For instructions on how to install and run Stratum on Broadcom ASIC based switchs:
<https://github.com/stratum/stratum/tree/master/stratum/hal/bin/bcm/standalone>

### 3 - Install pipeconf app in ONOS

To install the pipeconf app built in the previous step, assuming ONOS is running on the local machine:

```bash
make pipeconf-install ONOS_HOST=localhost
```

Use the `ONOS_HOST` argument to specify the hostname/IP address of the machine
where ONOS is running.

This command is a wrapper to a `curl` command that uses the ONOS REST API to
upload and activate the `.oar` package previously built.

You should see the ONOS log updating with messages notifying the registration of
new BCM pipeconf in the system.

```
New pipeconf registered: org.onosproject.pipelines.stratum.bcm (fingerprint=...)
```

To check all pipeconfs registered in the system, use the ONOS CLI:


```
onos> pipeconfs
```

### 4 - Connect ONOS to a Stratum switch

For ONOS to be able to discover your switch, you need to push a JSON file,
usually referred to as the "netcfg" file. We provide an example of such
`bcm-netcfg.json` file in this repository. Make sure to modify the following
values:

* `managementAddress` is expected to contain a valid URI with host and port of
  the Stratum gRPC server running on the switch;
* The `device_id` URI query parameter is the P4Runtime-internal `device_id`,
  also known as the Stratum "Node ID". Usually, you can leave this value set to
  `1`;
* Use the `pipeconf` field to specify which pipeconf to deploy on
  the switch. Currently, We only have one pipeconf `org.onosproject.pipelines.stratum.bcm`.
  
 Push the `tofino-netcfg.json` to ONOS using the command:
 
```bash
make netcfg ONOS_HOST=localhost
```
 
Like before, this command is a wrapper to a `curl` command that uses the ONOS
REST API to push the `bcm-netcfg.json` file.

Check the ONOS log for potential errors.

## Using Trellis with Stratum+BCM ASIC switches

Check the official Trellis documentation here:
<https://docs.trellisfabric.org>

In the "Device Configuration" section:
<https://docs.trellisfabric.org/configuration/device-config.html>

make sure to replace the `basic` JSON node for OpenFlow devices with the one
provided in `bcm-netcfg.json`, for example:

```json
{
  "devices" : {
    "device:leaf-1" : {
      "segmentrouting" : {
        "ipv4NodeSid" : 101,
        "ipv4Loopback" : "192.168.0.201",
        "ipv6NodeSid" : 111,
        "ipv6Loopback" : "2000::c0a8:0201",
        "routerMac" : "00:00:00:00:02:01",
        "isEdgeRouter" : true,
        "adjacencySids" : []
      },
      "basic": {
        "managementAddress": "grpc://10.0.0.1:28000?device_id=1",
        "driver": "stratum-fpm",
        "pipeconf": "org.onosproject.pipelines.stratum.bcm"
      }
    }
  }
}
```

## Support

To get help with Stratum and the FPM compiler, please contact
<stratum-dev@lists.stratumproject.org>

To get help with ONOS and the pipeconf, please contact
<brigade-p4@onosproject.org>

[stratum]: https://github.com/stratum/stratum
[trellis]: https://www.opennetworking.org/trellis
