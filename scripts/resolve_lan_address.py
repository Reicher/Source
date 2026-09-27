#!/usr/bin/env python3
"""Resolve and preflight the private IPv4 address used by Source deployment."""

import json
import socket
import subprocess
import sys

from validate_lan_address import valid_lan_address


class LANAddressError(ValueError):
    pass


def discover_lan_address(run=subprocess.run) -> str:
    """Return the preferred source address on the best IPv4 default route."""
    try:
        result = run(
            ["ip", "-j", "-4", "route", "show", "default"],
            check=True,
            capture_output=True,
            text=True,
        )
        routes = json.loads(result.stdout)
    except (
        FileNotFoundError,
        OSError,
        subprocess.CalledProcessError,
        json.JSONDecodeError,
    ) as error:
        raise LANAddressError(
            "SOURCE_LAN_ADDRESS is unset and the IPv4 default route could not be inspected; "
            "install iproute2 or set it to an assigned private IPv4 address"
        ) from error

    default_routes = (
        [route for route in routes if isinstance(route, dict) and route.get("dst") == "default"]
        if isinstance(routes, list)
        else []
    )
    if not default_routes:
        raise LANAddressError(
            "SOURCE_LAN_ADDRESS is unset and no IPv4 default route was found; "
            "set it to an assigned private IPv4 address"
        )

    try:
        best_metric = min(int(route.get("metric", 0)) for route in default_routes)
    except (TypeError, ValueError) as error:
        raise LANAddressError("The IPv4 default route returned an invalid metric") from error

    preferred_sources = set()
    for route in default_routes:
        if int(route.get("metric", 0)) != best_metric:
            continue
        source = route.get("prefsrc") or route.get("src")
        if isinstance(source, str) and source.strip():
            preferred_sources.add(source.strip())

    if len(preferred_sources) != 1:
        detail = (
            "no preferred source address"
            if not preferred_sources
            else "multiple preferred source addresses"
        )
        raise LANAddressError(
            f"The best IPv4 default route has {detail}; "
            "set SOURCE_LAN_ADDRESS to an assigned private IPv4 address"
        )
    return preferred_sources.pop()


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
