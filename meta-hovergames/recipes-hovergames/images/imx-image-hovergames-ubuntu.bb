# A simple image with a Ubuntu rootfs
#
# Note that we have a tight dependency to ubuntu-base
# and that we cannot just install arbitrary Yocto packages to avoid
# rootfs pollution or destruction.
PV = "${@d.getVar('PREFERRED_VERSION_ubuntu-base', True) or '1.0'}"

IMAGE_LINGUAS = ""
IMAGE_INSTALL = ""
inherit core-image image nativeaptinstall distro_features_check
export PACKAGE_INSTALL = "${IMAGE_INSTALL}"

APTGET_CHROOT_DIR = "${IMAGE_ROOTFS}"
APTGET_SKIP_UPGRADE = "1"

ROOTFS_POSTPROCESS_COMMAND_append = "do_aptget_update; do_update_host; do_update_dns; do_enable_network_manager;"

REQUIRED_DISTRO_FEATURES = "wayland"
CORE_IMAGE_BASE_INSTALL += "weston weston-init"
CORE_IMAGE_BASE_INSTALL += \
    "${@bb.utils.contains('DISTRO_FEATURES', 'x11', 'weston-xwayland matchbox-terminal', '', d)}"

# This must be added first as it provides the foundation for
# subsequent modifications to the rootfs
IMAGE_INSTALL += "\
	ubuntu-base \
	ubuntu-base-dev \
	ubuntu-base-dbg \
	ubuntu-base-doc \
"

# Without the kernel and modules, we can't really use the Linux
IMAGE_INSTALL += "\
	kernel-devicetree \
	kernel-image \
	kernel-modules \
"

# We want to have an itb to boot from in the /boot directory to be flexible
# about U-Boot behavior
#IMAGE_INSTALL += "\
#   linux-kernelitb-norootfs-image \
#"
#####
IMAGE_FEATURES += " \
    dbg-pkgs \
    dev-pkgs \
    tools-sdk \
    debug-tweaks \
    tools-profile \
    package-management \
    splash \
    nfs-server \
    tools-debug \
    ssh-server-dropbear \
    tools-testapps \
    hwcodecs \
    ${@bb.utils.contains('DISTRO_FEATURES', 'wayland', '', \
       bb.utils.contains('DISTRO_FEATURES',     'x11', 'x11-base x11-sato', \
                                                       '', d), d)} \
"
ERPC_COMPS ?= ""
ERPC_COMPS_append_mx7ulp = "packagegroup-imx-erpc"

CORE_IMAGE_EXTRA_INSTALL += " \
    packagegroup-core-full-cmdline \
    packagegroup-tools-bluetooth \
    packagegroup-fsl-tools-audio \
    packagegroup-fsl-tools-gpu \
    packagegroup-fsl-tools-gpu-external \
    packagegroup-fsl-tools-testapps \
    packagegroup-fsl-tools-benchmark \
    packagegroup-fsl-gstreamer1.0 \
    packagegroup-fsl-gstreamer1.0-full \
    ${@bb.utils.contains('DISTRO_FEATURES', 'wayland', 'weston-init', '', d)} \
    ${@bb.utils.contains('DISTRO_FEATURES', 'x11 wayland', 'weston-xwayland xterm', '', d)} \
    ${ERPC_COMPS} \
"
#######

APTGET_EXTRA_PACKAGES_SERVICES_DISABLED += "\
	network-manager \
"
APTGET_EXTRA_PACKAGES += "\
	console-setup locales \
	mc htop \
\
	apt git vim \
	ethtool wget ftp iputils-ping lrzsz \
	net-tools \
"
APTGET_EXTRA_SOURCE_PACKAGES += "\
	iproute2 \
"

# Add user ubuntu with password ubuntu and default shell bash
USER_SHELL_BASH = "/bin/bash"
USER_PASSWD_UBUNTU = "ubuntu"
APTGET_ADD_USERS = "ubuntu:${USER_PASSWD_UBUNTU}:${USER_SHELL_BASH}"

HOST_NAME = "ubuntu-${MACHINE_ARCH}"

##############################################################################
# NOTE: We cannot install arbitrary Yocto packages as they will
# conflict with the content of the prebuilt Ubuntu rootfs and pull
# in dependencies that may break the rootfs.
# Any package addition needs to be carefully evaluated with respect
# to the final image that we build.
##############################################################################

# Minimum support for LS2 and S32V specific elements.
IMAGE_INSTALL_append_fsl-lsch3 += "\
    mc-utils-image \
    restool \
"

# We want easy installation of the BlueBox image to the target
DEPENDS_append_fsl-lsch3 = " \
    bbdeployscripts \
"

fakeroot do_update_host() {
	set -x

	echo >"${APTGET_CHROOT_DIR}/etc/hostname" "${HOST_NAME}"

	echo  >"${APTGET_CHROOT_DIR}/etc/hosts" "127.0.0.1 localhost"
	echo >>"${APTGET_CHROOT_DIR}/etc/hosts" "127.0.1.1 ${HOST_NAME}"
	echo >>"${APTGET_CHROOT_DIR}/etc/hosts" ""
	echo >>"${APTGET_CHROOT_DIR}/etc/hosts" "# The following lines are desirable for IPv6 capable hosts"
	echo >>"${APTGET_CHROOT_DIR}/etc/hosts" "::1 ip6-localhost ip6-loopback"
	echo >>"${APTGET_CHROOT_DIR}/etc/hosts" "fe00::0 ip6-localnet"
	echo >>"${APTGET_CHROOT_DIR}/etc/hosts" "ff00::0 ip6-mcastprefix"
	echo >>"${APTGET_CHROOT_DIR}/etc/hosts" "ff02::1 ip6-allnodes"
	echo >>"${APTGET_CHROOT_DIR}/etc/hosts" "ff02::2 ip6-allrouters"
	echo >>"${APTGET_CHROOT_DIR}/etc/hosts" "ff02::3 ip6-allhosts"

	set +x
}

fakeroot do_update_dns() {
	set -x

	if [ ! -L "${APTGET_CHROOT_DIR}/etc/resolv.conf" ]; then
		if [ -e "${APTGET_CHROOT_DIR}/etc/resolveconf" ]; then
			mkdir -p "/run/resolveconf"
			if [ -f "${APTGET_CHROOT_DIR}/etc/resolv.conf" ]; then
				mv -f "${APTGET_CHROOT_DIR}/etc/resolv.conf" "/run/resolveconf/resolv.conf"
			fi
			ln -sf  "/run/resolveconf/resolv.conf" "${APTGET_CHROOT_DIR}/etc/resolv.conf"
		elif [ -e "${APTGET_CHROOT_DIR}/etc/dhcp/dhclient-enter-hooks.d/resolved" ]; then
			mkdir -p "/run/systemd/resolve"
			if [ -f "${APTGET_CHROOT_DIR}/etc/resolv.conf" ]; then
				mv -f "${APTGET_CHROOT_DIR}/etc/resolv.conf" "/run/systemd/resolve/resolv.conf"
			fi
			ln -sf  "/run/systemd/resolve/resolv.conf" "${APTGET_CHROOT_DIR}/etc/resolv.conf"
		else
			touch "${APTGET_CHROOT_DIR}/etc/resolv.conf"
		fi
	fi

	set +x
}

fakeroot do_enable_network_manager() {
	set -x

	# In bionic, but not in xenial. We want all [network] interfaces to be managed
	# so that we do not have to mess with interface files individually
	if [ -e "${APTGET_CHROOT_DIR}/usr/lib/NetworkManager/conf.d/10-globally-managed-devices.conf" ]; then
		sed -i -E "s/^unmanaged-devices\=\*/unmanaged-devices\=none/g" "${APTGET_CHROOT_DIR}/usr/lib/NetworkManager/conf.d/10-globally-managed-devices.conf"
	fi

	set +x
}


IMAGE_ROOTFS_SIZE ?= "8192"
IMAGE_ROOTFS_EXTRA_SPACE_append = "${@bb.utils.contains("DISTRO_FEATURES", "systemd", " + 4096", "" ,d)}"

COMPATIBLE_MACHINE ="(.*ubuntu)"
