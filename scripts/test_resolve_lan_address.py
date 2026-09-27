import unittest

from resolve_lan_address import LANAddressError, resolve_lan_address


class ResolveLANAddressTests(unittest.TestCase):
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
