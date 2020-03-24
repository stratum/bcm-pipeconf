# Copyright 2020-present Open Networking Foundation
# SPDX-License-Identifier: Apache-2.0
ONOS_HOST ?= localhost

p4c_fpm_img := stratumproject/p4c-fpm:latest
mvn_image := maven:3.6.1-jdk-11-slim

mkfile_path := $(abspath $(lastword $(MAKEFILE_LIST)))
curr_dir := $(patsubst %/,%,$(dir $(mkfile_path)))

p4_src_dir := $(STRATUM_ROOT)/stratum/pipelines/main
p4_build_dir := src/main/resources

pipeconf_app_name := org.onosproject.stratum-bcm-pipeconf
pipeconf_oar_file := $(shell ls -1 ${curr_dir}/target/stratum-bcm-pipeconf-*.oar 2> /dev/null)

curr_dir_sha := $(shell echo -n "$(curr_dir)" | shasum | cut -c1-7)
app_build_container_name := app-build-${curr_dir_sha}

onos_url := http://${ONOS_HOST}:8181/onos
onos_curl := curl --fail -sSL --user onos:rocks --noproxy localhost

all: pipeconf

pull-images:
	$(info *** Pulling docker images...)
	@docker pull ${p4c_fpm_img}
	@docker pull ${mvn_image}

fpm-bin:
	$(info *** Building P4 program for FPM...)
	@mkdir -p ${p4_build_dir}
	@mkdir -p p4src
	@cp ${p4_src_dir}/* p4src/
	@cp ${STRATUM_ROOT}/stratum/p4c_backends/fpm/map_data/* p4src/
	docker run --rm -v ${curr_dir}:/workdir -w /workdir ${p4c_fpm_img} \
		p4c --p4c_fe_options=" \
				-I /usr/share/p4c/p4include \
				--std=p4-16 \
				--target=BCM \
				--Wdisable=legacy \
				--Wwarn=all \
				p4src/main.p4" \
			--p4_info_file=${p4_build_dir}/p4info.txt \
			--p4_pipeline_config_text_file=${p4_build_dir}/main.pb.txt \
			--p4_pipeline_config_binary_file=${p4_build_dir}/main.pb.bin \
			--p4c_annotation_map_files=p4src/table_map.pb.txt,p4src/field_map.pb.txt \
			--target_parser_map_file=p4src/standard_parser_map.pb.txt \
			--slice_map_file=p4src/sliced_field_map.pb.txt
	echo "253" > ${p4_build_dir}/cpu-port.txt
	@rm -rf p4src
	@echo "*** P4 program compiled successfully! Output files are in ${p4_build_dir}"

# Reuse the same container to persist mvn repo cache.
_create_mvn_container:
	@if ! docker container ls -a --format '{{.Names}}' | grep -q ${app_build_container_name} ; then \
		docker create -v ${curr_dir}:/mvn-src -w /mvn-src --name ${app_build_container_name} ${mvn_image} mvn clean package; \
	fi

_mvn_package: fpm-bin
	$(info *** Building ONOS app...)
	@mkdir -p target
	@docker start -a -i ${app_build_container_name}


pipeconf: _create_mvn_container _mvn_package
	$(info *** ONOS pipeconf .oar package created succesfully)
	@ls -1 ${curr_dir}/target/*.oar

pipeconf-install:
	$(info *** Installing and activating pipeconf app in ONOS at ${ONOS_HOST}...)

	${onos_curl} -X POST -HContent-Type:application/octet-stream \
		'${onos_url}/v1/applications?activate=true' \
		--data-binary @${pipeconf_oar_file}
	@echo

clean:
	rm -rf src/main/resources

deep-clean: clean
	-rm -rf target
	-rm -rf p4c-out
	-docker rm ${mvn_container} > /dev/null 2>&1

netcfg:
	$(info *** Pushing tofino-netcfg.json to ONOS at ${ONOS_HOST}...)
	${onos_curl} -X POST -H 'Content-Type:application/json' \
		${onos_url}/v1/network/configuration -d@./bcm-netcfg.json
	@echo