# SPDX-License-Identifier: AGPL-3.0-or-later
#
# OpenWrt package for the knit-spool native binary (ADR 2026-11.zmc3). It installs the prebuilt
# aarch64 musl archive a GitHub release ships (ADR 2026-11.bxce) and wraps it in a procd service.
# Nothing is compiled here, because nothing can be: native-image needs a JVM and an arm64 host, and
# OpenWrt's buildroot has neither.
#
# The release workflow builds it with the OpenWrt SDK for mediatek/filogic (25.12 for .apk, 24.10
# for .ipk), the archive pre-seeded into dl/ and KNIT_SPOOL_VERSION / KNIT_SPOOL_HASH passed on the
# make command line. By hand, from an SDK checkout:
#
#   echo "src-link knitspool /path/to/knit-spool/deploy/openwrt" >> feeds.conf.default
#   ./scripts/feeds update knitspool && ./scripts/feeds install knit-spool
#   make defconfig && make package/knit-spool/compile V=s
#
# The version and hash below name the release the feed builds as checked out; the chore commit after
# each release moves them (CONTRIBUTING.md, "Releases"). Until the first release that ships the
# archive, the hash is `skip` and a by-hand build needs the archive in dl/ already.

include $(TOPDIR)/rules.mk

KNIT_SPOOL_VERSION ?= 0.9.0
KNIT_SPOOL_HASH ?= skip

PKG_NAME:=knit-spool
PKG_VERSION:=$(KNIT_SPOOL_VERSION)
PKG_RELEASE:=1

PKG_SOURCE:=knit-spool-$(PKG_VERSION)-linux-aarch64-musl.tar.gz
PKG_SOURCE_URL:=https://github.com/getknit/knit-spool/releases/download/v$(PKG_VERSION)/
PKG_HASH:=$(KNIT_SPOOL_HASH)
# The archive unpacks to knit-spool-<version>/, which is what the default Build/Prepare expects here.
PKG_BUILD_DIR:=$(BUILD_DIR)/knit-spool-$(PKG_VERSION)

PKG_LICENSE:=AGPL-3.0-or-later
PKG_LICENSE_FILES:=LICENSE THIRD-PARTY-NOTICES.md
PKG_MAINTAINER:=Jeffrey Walter Mixon <jeff.mixon@gmail.com>

# A GraalVM native image is not something the toolchain's strip was tested against. The image is
# already built without debug info, and a strip that broke it would show up only on the device.
RSTRIP:=:

include $(INCLUDE_DIR)/package.mk

define Package/knit-spool
  SECTION:=net
  CATEGORY:=Network
  TITLE:=Knit Spool, a blinded store-and-forward relay for Knit
  URL:=https://github.com/getknit/knit-spool
  # @aarch64: the archive is an aarch64 musl binary. +zlib: the one library it needs from the host
  # besides musl's libc (scripts/native-acceptance.sh asserts the NEEDED set is exactly that).
  DEPENDS:=@aarch64 +zlib
  USERID:=knit-spool=9470:knit-spool=9470
endef

define Package/knit-spool/description
 The reference relay daemon for the Knit messenger, as a native aarch64 binary that needs no JVM.
 It holds sealed frames it cannot read, for scope ids it cannot map to anyone, and drops them on a
 timer. The box side is configured in /etc/config/knit-spool; everything the daemon itself
 understands goes in /etc/knit-spool/env, its reload file, which `service knit-spool reload`
 re-reads without a restart. Put a TLS proxy in front: the listener is plain WebSocket on loopback.
endef

define Package/knit-spool/conffiles
/etc/config/knit-spool
/etc/knit-spool/env
endef

# Nothing to configure or compile: the archive is the build.
define Build/Configure
endef

define Build/Compile
endef

define Package/knit-spool/install
	$(INSTALL_DIR) $(1)/usr/lib/knit-spool
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/knit-spool $(1)/usr/lib/knit-spool/knit-spool
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/libsqlitejdbc.so $(1)/usr/lib/knit-spool/libsqlitejdbc.so
	$(INSTALL_DIR) $(1)/usr/bin
	$(LN) ../lib/knit-spool/knit-spool $(1)/usr/bin/knit-spool
	$(INSTALL_DIR) $(1)/etc/init.d
	$(INSTALL_BIN) ./files/knit-spool.init $(1)/etc/init.d/knit-spool
	$(INSTALL_DIR) $(1)/etc/config
	$(INSTALL_CONF) ./files/knit-spool.config $(1)/etc/config/knit-spool
	$(INSTALL_DIR) $(1)/etc/knit-spool
	$(INSTALL_CONF) ./files/env.example $(1)/etc/knit-spool/env
	$(INSTALL_DIR) $(1)/etc/nginx/conf.d
	$(INSTALL_DATA) ./files/nginx-knit-spool.conf $(1)/etc/nginx/conf.d/knit-spool.conf.example
	$(INSTALL_DIR) $(1)/usr/share/licenses/knit-spool
	$(INSTALL_DATA) $(PKG_BUILD_DIR)/LICENSE $(1)/usr/share/licenses/knit-spool/
	$(INSTALL_DATA) $(PKG_BUILD_DIR)/THIRD-PARTY-NOTICES.md $(1)/usr/share/licenses/knit-spool/
endef

# The env file holds the Spool's tokens: readable by root and the service user, nobody else. Package
# files install as root:root, and the user is created by the same install, so this runs after both.
define Package/knit-spool/postinst
#!/bin/sh
[ -n "$${IPKG_INSTROOT}" ] || { chgrp knit-spool /etc/knit-spool/env && chmod 0640 /etc/knit-spool/env; }
exit 0
endef

$(eval $(call BuildPackage,knit-spool))
