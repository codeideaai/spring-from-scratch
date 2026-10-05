"""Verify durable state across two real application processes on ephemeral ports."""
from pathlib import Path
import os
import queue
import subprocess
import tempfile
import threading
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CLASSPATH = os.pathsep.join(str(ROOT / p) for p in (
    "build/library-classes", "build/lib/h2-2.2.224.jar"))


def start(database):
    process = subprocess.Popen(
        ["java", "-cp", CLASSPATH, "io.github.codeideaai.library.LibraryApp", "0", database],
        cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    lines = queue.Queue()

    def read_output():
        for line in process.stdout:
            lines.put(line.rstrip())
        lines.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    output = []
    try:
        while True:
            line = lines.get(timeout=20)
            if line is None:
                raise RuntimeError("Application exited: " + "\n".join(output))
            output.append(line)
            if line.startswith("Library API: "):
                return process, line.removeprefix("Library API: ").split("/books", 1)[0]
    except BaseException:
        stop(process)
        raise


def stop(process):
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
    finally:
        process.stdout.close()


def request(base, method, path):
    req = urllib.request.Request(base + path, method=method,
                                 data=b"" if method == "POST" else None)
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            return response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as response:
        with response:
            return response.code, response.read().decode("utf-8")


def main():
    with tempfile.TemporaryDirectory(prefix="library-restart-") as directory:
        database = "jdbc:h2:" + str(Path(directory) / "catalog")
        process, base = start(database)
        try:
            assert request(base, "GET", "/books?bookId=101") == (200, "bookId=101;available=3")
            assert request(base, "POST", "/reservations?id=persisted&bookId=101&member=Lin")[0] == 201
        finally:
            stop(process)
        process, base = start(database)
        try:
            assert request(base, "GET", "/books?bookId=101") == (200, "bookId=101;available=2")
            assert request(base, "POST", "/reservations?id=other&bookId=101&member=Lin")[0] == 409
            assert request(base, "GET", "/books?bookId=101") == (200, "bookId=101;available=2")
        finally:
            stop(process)
    print("PASS process restart: stock and reservations persist; duplicate rollback preserves stock")


if __name__ == "__main__":
    main()
