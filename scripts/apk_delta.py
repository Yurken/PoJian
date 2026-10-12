"""Byte-exact APK patch: reuse unchanged compressed ZIP payloads, preserve signature bytes."""
import hashlib
import struct
import zipfile
from pathlib import Path

MAGIC = b"PJDIFF01"
MAX_APK = 512 * 1024 * 1024


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def payloads(path):
    with zipfile.ZipFile(path) as archive, open(path, "rb") as stream:
        result = {}
        for entry in archive.infolist():
            stream.seek(entry.header_offset)
            header = stream.read(30)
            if header[:4] != b"PK\x03\x04":
                raise ValueError("Invalid APK ZIP header")
            name, extra = struct.unpack_from("<HH", header, 26)
            offset = entry.header_offset + 30 + name + extra
            if entry.compress_size:
                stream.seek(offset)
                digest = hashlib.sha256()
                remaining = entry.compress_size
                while remaining:
                    block = stream.read(min(remaining, 65536))
                    if not block:
                        raise ValueError("Truncated APK")
                    digest.update(block)
                    remaining -= len(block)
                result[(entry.filename, entry.compress_type, entry.compress_size, digest.digest())] = (offset, entry.compress_size)
        return result


def create_patch(old, new, destination):
    old, new, destination = Path(old), Path(new), Path(destination)
    if not 0 < new.stat().st_size <= MAX_APK:
        raise ValueError("Invalid APK size")
    previous = payloads(old)
    matches = sorted((position, previous[key]) for key, position in payloads(new).items() if key in previous)
    with new.open("rb") as source, destination.open("wb") as output:
        output.write(MAGIC + struct.pack(">Q", new.stat().st_size))
        cursor = 0

        def literal(end):
            nonlocal cursor
            if end > cursor:
                output.write(b"\x02" + struct.pack(">Q", end - cursor))
                source.seek(cursor)
                remaining = end - cursor
                while remaining:
                    block = source.read(min(remaining, 65536))
                    if not block:
                        raise ValueError("Truncated APK")
                    output.write(block)
                    remaining -= len(block)
                cursor = end

        for (start, size), (old_start, old_size) in matches:
            if start < cursor or size != old_size:
                raise ValueError("Overlapping APK payload")
            literal(start)
            output.write(b"\x01" + struct.pack(">QQ", old_start, size))
            cursor = start + size
        literal(new.stat().st_size)
        output.write(b"\x00")


def apply_patch(old, patch, output):
    """Reference decoder, also used to verify a patch before publishing it."""
    with open(old, "rb") as base, open(patch, "rb") as delta, open(output, "wb") as result:
        def number():
            return struct.unpack(">Q", delta.read(8))[0]
        if delta.read(8) != MAGIC:
            raise ValueError("Invalid patch format")
        expected = number()
        if not 0 < expected <= MAX_APK:
            raise ValueError("Invalid output size")
        written = 0
        while True:
            opcode = delta.read(1)
            if opcode == b"\x00":
                if written != expected or delta.read(1):
                    raise ValueError("Incomplete or trailing patch")
                break
            if opcode == b"\x01":
                offset, length = number(), number()
                if offset + length > Path(old).stat().st_size:
                    raise ValueError("Copy exceeds base")
                base.seek(offset)
                source = base
            elif opcode == b"\x02":
                length, source = number(), delta
            else:
                raise ValueError("Unknown patch instruction")
            if length <= 0 or length > expected - written:
                raise ValueError("Invalid instruction length")
            remaining = length
            while remaining:
                block = source.read(min(remaining, 65536))
                if not block:
                    raise ValueError("Truncated patch")
                result.write(block)
                remaining -= len(block)
            written += length
