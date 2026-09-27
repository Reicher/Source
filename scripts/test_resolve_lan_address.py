import json
import unittest
from types import SimpleNamespace

from resolve_lan_address import LANAddressError, discover_lan_address, resolve_lan_address


class ResolveLANAddressTests(unittest.TestCase):
    def test_discovers_source_from_lowest_metric_default_route(self):
        def run(command, **options):
            self.assertEqual(command, ["ip", "-j", "-4", "route", "show", "default"])
            self.assertEqual(
                options,
                {"check": True, "capture_output": True, "text": True},
            )
            return SimpleNamespace(
                stdout=json.dumps(
                    [
                        {
                            "dst": "default",
                            "dev": "vpn0",
                            "prefsrc": "10.8.0.2",
                            "metric": 600,
                        },
                        {
                            "dst": "default",
                            "dev": "eth0",
                            "prefsrc": "192.168.1.20",
                            "metric": 100,
                        },
                    ]
                )
            )

        self.assertEqual(discover_lan_address(run), "192.168.1.20")

    def test_rejects_ambiguous_equal_metric_default_routes(self):
        def run(*_args, **_options):
            return SimpleNamespace(
                stdout=json.dumps(
                    [
                        {
                            "dst": "default",
                            "dev": "eth0",
                            "prefsrc": "192.168.1.20",
                            "metric": 100,
                        },
                        {
                            "dst": "default",
                            "dev": "wlan0",
                            "prefsrc": "192.168.2.20",
                            "metric": 100,
                        },
                    ]
                )
            )

        with self.assertRaisesRegex(LANAddressError, "multiple preferred source addresses"):
            discover_lan_address(run)

    def test_uses_assigned_configured_private_address(self):
        self.assertEqual(
            resolve_lan_address(
                " 192.168.1.20 ",
                discover=lambda: self.fail("configured addresses must not be auto-detected"),
                assigned=lambda value: value == "192.168.1.20",
            ),
            "192.168.1.20",
        )

    def test_detects_assigned_private_default_route_address_when_unset(self):
        self.assertEqual(
            resolve_lan_address(
                "",
                discover=lambda: "10.20.30.40",
                assigned=lambda value: value == "10.20.30.40",
            ),
            "10.20.30.40",
        )

    def test_rejects_public_detected_address(self):
        with self.assertRaisesRegex(LANAddressError, "Detected address must be a private"):
            resolve_lan_address("", discover=lambda: "203.0.113.10", assigned=lambda _: True)

    def test_rejects_unassigned_configured_address(self):
        with self.assertRaisesRegex(LANAddressError, "is not assigned to this host"):
            resolve_lan_address(
                "172.16.1.4",
                discover=lambda: self.fail("configured addresses must not be auto-detected"),
                assigned=lambda _: False,
            )

    def test_preserves_discovery_failure(self):
        def fail():
            raise LANAddressError("no route")

        with self.assertRaisesRegex(LANAddressError, "no route"):
            resolve_lan_address("", discover=fail, assigned=lambda _: True)


if __name__ == "__main__":
    unittest.main()
