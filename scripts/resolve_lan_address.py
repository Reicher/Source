#!/usr/bin/env python3
"""Resolve and preflight the private IPv4 address used by Source deployment."""

import socket
import sys

from validate_lan_address import valid_lan_address


DISCOVERY_TARGET = ("192.0.2.1", 9)


class LANAddressError(ValueError):
    pass


def discover_lan_address() -> str:
    """Ask the routing table which source address reaches the default route."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as connection:
            connection.connect(DISCOVERY_TARGET)
            return connection.getsockname()[0]
    except OSError as error:
        raise LANAddressError(
            "SOURCE_LAN_ADDRESS is unset and no default-route IPv4 address could be detected; "
            "set it to an assigned private IPv4 address"
        ) from error


def address_is_assigned(value: str) -> bool:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind((value, 0))
        return True
    except OSError:
        return False


def resolve_lan_address(
    configured: str,
    discover=discover_lan_address,
    assigned=address_is_assigned,
) -> str:
    value = configured.strip()
    detected = not value
    if detected:
        value = discover()
    if not valid_lan_address(value):
        origin = "Detected address" if detected else "SOURCE_LAN_ADDRESS"
        raise LANAddressError(f"{origin} must be a private RFC1918 IPv4 address")
    if not assigned(value):
        raise LANAddressError(f"SOURCE_LAN_ADDRESS {value} is not assigned to this host")
    return value


def main() -> int:
    configured = sys.argv[1] if len(sys.argv) == 2 else ""
    try:
        print(resolve_lan_address(configured))
    except LANAddressError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
