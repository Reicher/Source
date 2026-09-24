import unittest

from validate_lan_address import valid_lan_address


class ValidateLANAddressTests(unittest.TestCase):
    def test_accepts_rfc1918_addresses(self):
        for value in ("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.20"):
            with self.subTest(value=value):
                self.assertTrue(valid_lan_address(value))

    def test_rejects_wildcard_loopback_public_and_ipv6_addresses(self):
        for value in ("", "0.0.0.0", "127.0.0.1", "8.8.8.8", "::", "fd00::1", "localhost"):
            with self.subTest(value=value):
                self.assertFalse(valid_lan_address(value))


if __name__ == "__main__":
    unittest.main()
