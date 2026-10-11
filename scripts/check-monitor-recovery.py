#!/usr/bin/env python3
"""Verify real process recovery using installed APKs on an explicitly selected isolated emulator."""
import argparse
import http.server
import json
import subprocess
import threading


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--isolated-emulator", required=True, help="Serial of a disposable emulator with app/test APKs installed")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--resume-existing", action="store_true", help="Resume and restore a fixture left by an interrupted prepare")
    parser.add_argument("--port", type=int, default=0, help="Reuse the original fake service port when resuming an interrupted fixture")
    options = parser.parse_args()
    serial = options.isolated_emulator
    if not serial.startswith("emulator-"):
        parser.error("A disposable emulator serial is required")

    def adb(*args):
        return subprocess.run([options.adb, "-s", serial, *args], check=True, capture_output=True, text=True, timeout=60).stdout

    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        parser.error("Target is not an emulator")
    request_ids = []
    first_request = threading.Event()
    release = threading.Event()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            if self.path != "/__fixture_status":
                self.send_error(404)
                return
            response = b"accepted" if first_request.is_set() else b"waiting"
            self.send_response(200)
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)

        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            if self.path != "/monitor" or not 0 < length <= 65536:
                self.send_error(400)
                return
            body = json.loads(self.rfile.read(length))
            request_ids.append(body["id"])
            first_request.set()
            if len(request_ids) == 1 and not options.resume_existing:
                # Keep the first request in flight while the test host stops the app process.
                release.wait(55)
            claim = dict(quote="招聘岗位正在减少", topic="就业", subject="招聘岗位", dimension="数量",
                         stance="减少", evidence="岗位同比减少20%", conditions="今年", uncertain=False,
                         verdict="not_checked", explanation="本地恢复夹具，未查询外部证据", sources=[])
            response = json.dumps(dict(id=body["id"], claims=[claim]), ensure_ascii=False).encode()
            try:
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(response)))
                self.end_headers()
                self.wfile.write(response)
            except (BrokenPipeError, ConnectionResetError):
                pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", options.port), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    port = str(server.server_port)
    print("Fake service port: " + port, flush=True)
    test_class = "com.emerge.app.monitoring.MonitorRecoveryLifecycleTest"
    try:
        adb("reverse", "tcp:" + port, "tcp:" + port)
        def stage(name, method):
            result = adb("shell", "am", "instrument", "-r", "-w", "-e", "class", test_class + "#" + method,
                         "-e", "monitor_recovery_stage", name, "-e", "monitor_recovery_port", port,
                         "com.zhiheng.app.test/androidx.test.runner.AndroidJUnitRunner")
            if "OK (1 test)" not in result or "FAILURES" in result:
                raise RuntimeError(result)
            print(name + ": passed", flush=True)
        if not options.resume_existing:
            stage("prepare", "prepareRunningTask")
            if not first_request.wait(10):
                raise RuntimeError("The first HTTP request was not observed")
            adb("shell", "am", "force-stop", "com.zhiheng.app")
        stage("resume", "resumeAndVerifyRecoveredTask")
        if options.resume_existing:
            print("Existing recovery fixture resumed and settings restored")
        elif len(request_ids) != 2 or request_ids[0] != request_ids[1]:
            raise RuntimeError("Recovered request did not preserve its identity")
        else:
            print("Process recovery: same request identity, one event, valid semantics, no stale alert")
    finally:
        release.set()
        adb("reverse", "--remove", "tcp:" + port)
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
