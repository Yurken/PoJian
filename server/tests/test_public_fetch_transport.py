"""Offline transport checks through urllib and http.client, replacing only network/TLS IO."""
import io
import os
from pathlib import Path
import socket
import ssl
import sys
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import monitor_server as monitor


class _HeaderStream(io.BytesIO):
    def __init__(self, body, transport, trickle=False):
        super().__init__(body)
        self.transport = transport
        self.trickle = trickle
        self.headers_done = False
        self.reference_released = False

    def readline(self, size=-1):
        if not self.headers_done and self.trickle and self.transport.released.wait(.03):
            raise TimeoutError("fixture header transport closed")
        if self.headers_done and self.transport.chunk_line_block:
            self.transport.released.wait(1.5)
            raise TimeoutError("fixture chunk-size transport closed")
        line = super().readline(size)
        if line in (b"\r\n", b"\n"):
            self.headers_done = True
        return line

    def read1(self, size=-1):
        if self.transport.body_block:
            self.transport.released.wait(1.5)
            raise TimeoutError("fixture body transport closed")
        return super().read1(size)

    def close(self):
        if not self.reference_released:
            self.reference_released = True
            self.transport.readers -= 1
            if self.transport.closed and not self.transport.readers:
                self.transport.released.set()
        super().close()


class _Socket:
    def __init__(self, response, *, tls_failure=False, tls_block=False, header_trickle=False, tcp_failure=False,
                 body_block=False, chunk_line_block=False):
        self.response = response
        self.tls_failure = tls_failure
        self.tls_block = tls_block
        self.header_trickle = header_trickle
        self.tcp_failure = tcp_failure
        self.body_block = body_block
        self.chunk_line_block = chunk_line_block
        self.released = threading.Event()
        self.address = None
        self.timeout = None
        self.sent = bytearray()
        self.closed = False
        self.readers = 0

    def connect(self, address):
        self.address = address
        if self.tcp_failure:
            raise OSError("fixture first address unavailable")

    def settimeout(self, value):
        self.timeout = value

    def gettimeout(self):
        return self.timeout

    def setsockopt(self, *args):
        pass

    def bind(self, address):
        pass

    def do_handshake(self):
        if self.tls_failure:
            raise ssl.SSLCertVerificationError("fixture rejected certificate")
        if self.tls_block:
            self.released.wait(1.5)
            raise TimeoutError("fixture handshake transport closed")

    def sendall(self, data):
        self.sent.extend(data)

    def makefile(self, *args):
        self.readers += 1
        return _HeaderStream(self.response, self, self.header_trickle)

    def shutdown(self, how):
        self.released.set()

    def close(self):
        self.closed = True
        # socket.makefile owns an IO reference: socket.close does not end an in-flight body reader.
        if not self.readers:
            self.released.set()


def _response(status=200, *, location=None, extra_headers=0):
    body = b"verified public document" if status == 200 else b""
    headers = [f"HTTP/1.1 {status} Fixture", "Content-Type: text/plain",
               f"Content-Length: {len(body)}", "Connection: close"]
    if location:
        headers.append("Location: " + location)
    headers.extend(f"X-Fixture-{index}: yes" for index in range(extra_headers))
    return ("\r\n".join(headers) + "\r\n\r\n").encode() + body


class PublicFetchTransportTests(unittest.TestCase):
    def fetch(self, addresses=("93.184.216.34",), response=None, *, timeout=3, **socket_options):
        self.sockets, self.resolutions, self.tls = [], [], []

        def resolve(host, port, *args, **kwargs):
            self.resolutions.append((host, port))
            if host == "public.example.org":
                resolved = addresses
            elif host in addresses:
                resolved = [host]
            else:
                raise AssertionError("unexpected DNS target: " + host)
            return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (address, port)) for address in resolved]

        def create_socket(*args, **kwargs):
            options = dict(socket_options)
            if options.pop("fail_first_tcp", False):
                options["tcp_failure"] = not self.sockets
            transport = _Socket(response or _response(), **options)
            self.sockets.append(transport)
            return transport

        def wrap(context, transport, **kwargs):
            self.tls.append((kwargs.get("server_hostname"), context.verify_mode, context.check_hostname))
            # Model wrap_socket's implicit handshake when the connection has not split it out.
            if kwargs.get("do_handshake_on_connect", True):
                transport.do_handshake()
            return transport

        with patch.object(monitor.socket, "getaddrinfo", side_effect=resolve), \
                patch.object(monitor.socket, "socket", side_effect=create_socket), \
                patch.object(ssl.SSLContext, "wrap_socket", new=wrap), \
                patch.dict(os.environ, {"HTTPS_PROXY": "http://127.0.0.1:9999", "ALL_PROXY": "http://127.0.0.1:9999"}):
            started = time.monotonic()
            result = monitor.fetch_page_text("https://public.example.org/page", started + timeout)
            self.elapsed = time.monotonic() - started
            return result

    def test_public_connection_keeps_pinned_address_hostname_and_tls_policy(self):
        self.assertEqual("verified public document", self.fetch())
        self.assertEqual([("93.184.216.34", 443)], [item.address for item in self.sockets])
        self.assertEqual(1, self.resolutions.count(("public.example.org", 443)))
        self.assertTrue(all(host in {"public.example.org", "93.184.216.34"} for host, _ in self.resolutions))
        self.assertEqual([("public.example.org", ssl.CERT_REQUIRED, True)], self.tls)
        self.assertIn(b"Host: public.example.org\r\n", self.sockets[0].sent)
        self.assertNotIn(b"Authorization:", self.sockets[0].sent)
        self.assertTrue(self.sockets[0].closed)

    def test_private_or_mixed_dns_answers_never_create_a_tcp_socket(self):
        for addresses in [("127.0.0.1",), ("10.0.0.1",), ("93.184.216.34", "192.168.1.1")]:
            with self.subTest(addresses=addresses):
                self.assertEqual("", self.fetch(addresses))
                self.assertEqual([], self.sockets)
                self.assertEqual([], self.tls)

    def test_http_redirect_cannot_open_a_private_second_destination(self):
        self.assertEqual("", self.fetch(response=_response(302, location="https://127.0.0.1/private")))
        self.assertEqual(1, len(self.sockets))
        self.assertEqual(("93.184.216.34", 443), self.sockets[0].address)
        self.assertNotIn(("127.0.0.1", 443), self.resolutions)
        self.assertTrue(self.sockets[0].closed)

    def test_failed_public_address_can_only_fall_back_to_another_validated_address(self):
        self.assertEqual("verified public document", self.fetch(("93.184.216.34", "8.8.8.8"), fail_first_tcp=True))
        self.assertEqual([("93.184.216.34", 443), ("8.8.8.8", 443)], [item.address for item in self.sockets])
        self.assertTrue(all(item.closed for item in self.sockets))

    def test_certificate_failure_drops_the_document_and_closes_transport(self):
        self.assertEqual("", self.fetch(tls_failure=True))
        self.assertEqual(1, len(self.sockets))
        self.assertTrue(self.sockets[0].closed)

    def test_deadline_interrupts_a_blocked_tls_handshake(self):
        self.assertEqual("", self.fetch(timeout=.1, tls_block=True))
        self.assertLess(self.elapsed, 1.0)
        self.assertEqual(1, len(self.sockets))
        self.assertTrue(self.sockets[0].closed)

    def test_response_header_trickle_cannot_restart_the_total_deadline(self):
        self.assertEqual("", self.fetch(timeout=.1, response=_response(extra_headers=24), header_trickle=True))
        self.assertLess(self.elapsed, .6)
        self.assertEqual(1, len(self.sockets))
        self.assertTrue(self.sockets[0].closed)

    def test_body_deadline_survives_httpclient_handing_the_socket_to_its_reader(self):
        self.assertEqual("", self.fetch(timeout=.1, body_block=True))
        self.assertLess(self.elapsed, 1.0)
        self.assertEqual(1, len(self.sockets))
        self.assertTrue(self.sockets[0].closed)

    def test_chunk_size_line_deadline_survives_connection_close_response(self):
        response = b"HTTP/1.1 200 Fixture\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
        self.assertEqual("", self.fetch(timeout=.1, response=response, chunk_line_block=True))
        self.assertLess(self.elapsed, 1.0)
        self.assertEqual(1, len(self.sockets))
        self.assertTrue(self.sockets[0].closed)


if __name__ == "__main__":
    unittest.main()
