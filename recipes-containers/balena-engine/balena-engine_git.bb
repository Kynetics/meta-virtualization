HOMEPAGE = "https://www.balena.io/"
SUMMARY = "A Moby-based container engine for IoT"
DESCRIPTION = "Balena is a new container engine purpose-built for embedded \
and IoT use cases and compatible with Docker containers. Based on Docker’s \
Moby Project, balena supports container deltas for 10-70x more efficient \
bandwidth usage, has 3.5x smaller binaries, uses RAM and storage more \
conservatively, and focuses on atomicity and durability of container \
pulling."
LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://src/import/LICENSE;md5=4859e97a9c7780e77972d989f0823f28"

BALENA_VERSION = "19.03.13-dev"
BALENA_BRANCH= "master"

SRCREV = "074a481789174b4b6fd2d706086e8ffceb72e924"
SRC_URI = "\
	git://github.com/balena-os/balena-engine.git;branch=${BALENA_BRANCH};destsuffix=git/src/import \
	file://0001-imporve-hardcoded-CC-on-cross-compile-docker-ce.patch \
	file://balena-engine.init \
	file://balena-tmpfiles.conf \
"

GO_IMPORT = "import"

S = "${WORKDIR}/git"

PV = "${BALENA_VERSION}+git${SRCREV}"

SECURITY_CFLAGS = "${SECURITY_NOPIE_CFLAGS}"
SECURITY_LDFLAGS = ""

inherit systemd update-rc.d

SYSTEMD_PACKAGES = "${@bb.utils.contains('DISTRO_FEATURES','systemd','${PN}','',d)}"
SYSTEMD_SERVICE_${PN} = "${@bb.utils.contains('DISTRO_FEATURES','systemd','balena-engine.service','',d)}"
SYSTEMD_AUTO_ENABLE_${PN} = "enable"

INITSCRIPT_PACKAGES += "${@bb.utils.contains('DISTRO_FEATURES','sysvinit','${PN}','',d)}"
INITSCRIPT_NAME_${PN} = "${@bb.utils.contains('DISTRO_FEATURES','sysvinit','balena-engine.init','',d)}"
INITSCRIPT_PARAMS_${PN} = "defaults"

inherit useradd
USERADD_PACKAGES = "${PN}"
GROUPADD_PARAM_${PN} = "-r balena-engine"

DEPENDS_append = " systemd"
RDEPENDS_${PN} = "curl util-linux iptables tini systemd bash"
RRECOMMENDS_${PN} += "kernel-module-nf-nat kernel-module-br-netfilter kernel-module-nf-conntrack-netlink kernel-module-xt-masquerade kernel-module-xt-addrtype"

INSANE_SKIP_${PN} += "already-stripped"

FILES_${PN} += " \
	${systemd_unitdir}/system/* \
	${ROOT_HOME} \
	${localstatedir} \
"

DOCKER_PKG="github.com/docker/docker"

BUILD_TAGS ="no_buildkit no_btrfs no_cri no_devmapper no_zfs exclude_disk_quota exclude_graphdriver_btrfs exclude_graphdriver_devicemapper exclude_graphdriver_zfs"

inherit go
inherit goarch
inherit pkgconfig

do_configure[noexec] = "1"

do_compile() {
	export PATH=${STAGING_BINDIR_NATIVE}/${HOST_SYS}:$PATH

	# Set GOPATH. See 'PACKAGERS.md'. Don't rely on
	# docker to download its dependencies but rather
	# use dependencies packaged independently.
	cd ${S}/src/import
	rm -rf .gopath
	mkdir -p .gopath/src/"$(dirname "${DOCKER_PKG}")"
	ln -sf ../../../.. .gopath/src/"${DOCKER_PKG}"

	export GOPATH="${S}/src/import/.gopath:${S}/src/import/vendor:${STAGING_DIR_TARGET}/${prefix}/local/go"
	export GOROOT="${STAGING_DIR_NATIVE}/${nonarch_libdir}/${HOST_SYS}/go"

	# Pass the needed cflags/ldflags so that cgo
	# can find the needed headers files and libraries
	export GOARCH=${TARGET_GOARCH}
	export CGO_ENABLED="1"
	export CGO_CFLAGS="${CFLAGS} --sysroot=${STAGING_DIR_TARGET}"
	export CGO_LDFLAGS="${LDFLAGS}  --sysroot=${STAGING_DIR_TARGET}"
	export DOCKER_BUILDTAGS='${BUILD_TAGS}'

	export DOCKER_GITCOMMIT="${SRCREV}"
	export DOCKER_LDFLAGS="-s"

	VERSION=${BALENA_VERSION} ./hack/make.sh dynbinary-balena
}

do_install() {
	mkdir -p ${D}/${bindir}
	install -m 0755 ${S}/src/import/bundles/dynbinary-balena/balena-engine ${D}/${bindir}/balena-engine

	ln -sf balena-engine ${D}/${bindir}/balena-engine-daemon
	ln -sf balena-engine ${D}/${bindir}/balena-engine-containerd
	ln -sf balena-engine ${D}/${bindir}/balena-engine-containerd-shim
	ln -sf balena-engine ${D}/${bindir}/balena-engine-containerd-ctr
	ln -sf balena-engine ${D}/${bindir}/balena-engine-runc
	ln -sf balena-engine ${D}/${bindir}/balena-engine-proxy

	if ${@bb.utils.contains('DISTRO_FEATURES','systemd','true','false',d)}; then
		install -d ${D}${systemd_unitdir}/system
		install -m 0644 ${S}/src/import/contrib/init/systemd/balena-engine.socket ${D}/${systemd_unitdir}/system
		install -m 0644 ${S}/src/import/contrib/init/systemd/balena-engine.service ${D}/${systemd_unitdir}/system
	else
		install -d ${D}${sysconfdir}/init.d
		install -m 0755 ${WORKDIR}/balena-engine.init ${D}${sysconfdir}/init.d/balena-engine.init
	fi

	install -d ${D}${ROOT_HOME}/.docker
	ln -sf .docker ${D}${ROOT_HOME}/.balena
	ln -sf .docker ${D}${ROOT_HOME}/.balena-engine

	install -d ${D}${localstatedir}/lib/docker
	ln -sf docker ${D}${localstatedir}/lib/balena
	ln -sf docker ${D}${localstatedir}/lib/balena-engine

	install -d ${D}${sysconfdir}/tmpfiles.d
	install -m 0644 ${WORKDIR}/balena-tmpfiles.conf ${D}${sysconfdir}/tmpfiles.d/
}
