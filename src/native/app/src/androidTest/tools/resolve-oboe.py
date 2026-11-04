import argparse
import bisect
import hashlib
import json
import struct
from pathlib import Path


class Elf:
    def __init__(self, path):
        self.path = Path(path)
        self.data = self.path.read_bytes()
        if self.data[:6] != b"\x7fELF\x02\x01" or self.u16(18) != 183:
            raise ValueError("Expected little-endian AArch64 ELF64")
        self.segments = []
        self.eh = None
        for index in range(self.u16(56)):
            values = struct.unpack_from("<IIQQQQQQ", self.data, self.u64(32) + index * self.u16(54))
            kind, flags, offset, address, _, size, memory, _ = values
            if kind == 1:
                self.segments.append((flags, offset, address, size, memory))
            if kind == 0x6474E550:
                self.eh = (offset, address, size)
        if not self.eh:
            raise ValueError("ELF unwind index unavailable")
        offset, address, size = self.eh
        if self.data[offset:offset + 4] != bytes([1, 0x1B, 3, 0x3B]):
            raise ValueError("Unsupported ELF unwind index encoding")
        count = self.u32(offset + 8)
        if count <= 0 or 12 + count * 8 > size:
            raise ValueError("Invalid ELF unwind index")
        self.starts = [address + struct.unpack_from("<i", self.data, offset + 12 + index * 8)[0] for index in range(count)]
        if self.starts != sorted(set(self.starts)):
            raise ValueError("Ambiguous ELF unwind boundaries")

    def u16(self, offset):
        return struct.unpack_from("<H", self.data, offset)[0]

    def u32(self, offset):
        return struct.unpack_from("<I", self.data, offset)[0]

    def u64(self, offset):
        return struct.unpack_from("<Q", self.data, offset)[0]

    def file_offset(self, address, length=1):
        for _, offset, start, size, _ in self.segments:
            if start <= address and address + length <= start + size:
                return offset + address - start
        raise ValueError("Unmapped ELF address")

    def word(self, address):
        return self.u32(self.file_offset(address, 4))

    def bytes(self, address, length):
        offset = self.file_offset(address, length)
        return self.data[offset:offset + length]

    def strings(self, value):
        needle = value.encode() + b"\0"
        result = []
        cursor = 0
        while True:
            found = self.data.find(needle, cursor)
            if found < 0:
                return result
            for _, offset, address, size, _ in self.segments:
                if offset <= found and found + len(needle) <= offset + size:
                    result.append(address + found - offset)
                    break
            cursor = found + 1

    def references(self, targets):
        targets = set(targets)
        result = []
        pages = {value & ~4095 for value in targets}
        for flags, offset, address, size, _ in self.segments:
            if not flags & 1:
                continue
            for position in range(offset, offset + size - 24, 4):
                instruction = self.u32(position)
                if instruction & 0x9F000000 != 0x90000000:
                    continue
                immediate = ((instruction >> 5) & 0x7FFFF) << 2 | ((instruction >> 29) & 3)
                if immediate & (1 << 20):
                    immediate -= 1 << 21
                pc = address + position - offset
                page = (pc & ~4095) + (immediate << 12)
                if page not in pages:
                    continue
                register = instruction & 31
                for distance in range(1, 6):
                    following = self.u32(position + distance * 4)
                    if following & 0xFF000000 != 0x91000000 or (following >> 5) & 31 != register:
                        continue
                    value = page + ((following >> 10) & 4095) * (4096 if following & (1 << 22) else 1)
                    if value in targets:
                        result.append((pc, pc + distance * 4, value))
        return result

    def function(self, address):
        index = bisect.bisect_right(self.starts, address) - 1
        if index < 0 or index + 1 >= len(self.starts):
            raise ValueError("Unwind boundary unavailable")
        return self.starts[index], self.starts[index + 1]


def unique(values, description):
    values = list(set(values))
    if len(values) != 1:
        raise ValueError(f"Expected one {description}, found {len(values)}")
    return values[0]


def resolve(path):
    elf = Elf(path)
    if len(elf.strings("OboeVersion1.9.3")) != 1:
        raise ValueError("Only independently checked Oboe 1.9.3 layout is supported")
    disabled = elf.references(elf.strings("AudioStream::%s() called with data callback disabled!"))
    names = elf.references(elf.strings("fireDataCallback"))
    candidates = {elf.function(reference[0]) for reference in disabled} & {elf.function(reference[0]) for reference in names}
    start, end = unique(candidates, "common Oboe fireDataCallback")
    if end - start > 1024 or end - start < 64:
        raise ValueError("Unexpected Oboe callback function size")
    words = [elf.word(address) for address in range(start, end, 4)]
    if words[-1] != 0xD65F03C0 or not any(value & 0xFFFFFC00 == 0x08DFFC00 for value in words) or not any(value & 0xFFFFFC00 == 0x089FFC00 for value in words):
        raise ValueError("Oboe callback missing return/atomic enable guard")
    callback_loads = [value for value in words if value & 0xFFC00000 == 0xF9400000 and ((value >> 10) & 4095) * 8 == 8]
    if not callback_loads:
        raise ValueError("Oboe callback object load unavailable")
    opening = unique(elf.references(elf.strings("AudioStreamOpenSLES::open() chans=%d, rate=%d")), "OpenSL stream format diagnostic")
    open_start, open_end = elf.function(opening[0])
    pairs = []
    for address in range(max(open_start, opening[0] - 64), opening[0], 4):
        value = elf.word(address)
        if value & 0xFFC00000 == 0x29400000 and value & 31 == 3 and (value >> 10) & 31 == 4 and (value >> 5) & 31 == 0:
            immediate = (value >> 15) & 127
            if immediate & 64:
                immediate -= 128
            pairs.append(immediate * 4)
    channel_offset = unique(pairs, "channel/rate diagnostic field pair")
    rate_offset = channel_offset + 4
    formats = []
    for address in range(opening[1] + 4, min(opening[1] + 32, open_end), 4):
        value = elf.word(address)
        if value & 0xFFC00000 == 0xB9400000 and (value >> 5) & 31 == 19:
            formats.append(((value >> 10) & 4095) * 4)
    format_offset = unique(formats, "OpenSL format validation field")
    if (channel_offset, rate_offset, format_offset) != (0x3C, 0x40, 0x58):
        raise ValueError("Oboe 1.9.3 field layout does not match independent source layout")
    anchors = [{"rva": start, "bytes": elf.bytes(start, min(32, end - start)).hex()}]
    anchors.extend({"rva": item[0], "bytes": elf.bytes(item[0], item[1] - item[0] + 4).hex()} for item in disabled + names)
    return {
        "path": str(elf.path.resolve()),
        "sha256": hashlib.sha256(elf.data).hexdigest(),
        "oboeVersion": "1.9.3",
        "callbackRva": start,
        "callbackSize": end - start,
        "callbackSha256": hashlib.sha256(elf.bytes(start, end - start)).hexdigest(),
        "abi": "int fireDataCallback(void* stream, void* audioData, int32_t numFrames)",
        "fields": {"channelCount": channel_offset, "sampleRate": rate_offset, "format": format_offset, "direction": format_offset + 4},
        "fieldEvidence": {"openDiagnosticRva": opening[0], "openFunctionRva": open_start, "direction": "Oboe 1.9.3 AudioStreamBase source layout; validate runtime value 0 for output"},
        "anchors": anchors,
        "limitations": ["Static resolution does not prove runtime callback execution", "Only Oboe 1.9.3 and independently checked field layout accepted", "Native caller must verify exact ELF digest/anchors and live stream fields before reading or modifying PCM"]
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("library")
    args = parser.parse_args()
    try:
        print(json.dumps(resolve(args.library), indent=4))
    except (OSError, ValueError, struct.error) as error:
        raise SystemExit(str(error))
