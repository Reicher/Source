#!/usr/bin/env python3
"""Validate the explicit private IPv4 address used by Source deployment."""

import ipaddress
import sys


PRIVATE_NETWORKS = tuple(
    ipaddress.ip_network(value) for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
)


def valid_lan_address(value: str) -> bool:
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        return False
    return address.version == 4 and any(address in network for network in PRIVATE_NETWORKS)


def main() -> int:
    value = sys.argv[1] if len(sys.argv) == 2 else ""
    if not valid_lan_address(value):
        print("SOURCE_LAN_ADDRESS must be an explicit private IPv4 address", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
