ARG CADDY_IMAGE=caddy:2-alpine
FROM ${CADDY_IMAGE}

# The upstream image grants Caddy CAP_NET_BIND_SERVICE for ports below 1024.
# This gateway listens on 8443, so remove the file capability to keep
# no-new-privileges and cap_drop: ALL compatible with an unprivileged UID.
RUN setcap -r /usr/bin/caddy
