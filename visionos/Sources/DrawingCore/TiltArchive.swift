import Compression
import Foundation

struct LittleEndianReader {
    let data: Data
    var offset: Int = 0
    var remaining: Int { data.count - offset }

    mutating func u16() throws -> UInt16 { UInt16(littleEndian: try integer()) }
    mutating func u32() throws -> UInt32 { UInt32(littleEndian: try integer()) }

    private mutating func integer<T: FixedWidthInteger>() throws -> T {
        guard offset >= 0, remaining >= MemoryLayout<T>.size else {
            throw WorkspaceError.invalid("Truncated binary file")
        }
        let value = data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: T.self) }
        offset += MemoryLayout<T>.size
        return value
    }

    mutating func float() throws -> Float {
        let value = Float(bitPattern: try u32())
        guard value.isFinite else { throw WorkspaceError.invalid("Nonfinite TILT point or brush value") }
        return value
    }

    mutating func skip(_ count: Int) throws {
        guard count >= 0, offset >= 0, count <= remaining else {
            throw WorkspaceError.invalid("Invalid binary file length")
        }
        offset += count
    }

    mutating func bytes(_ count: Int) throws -> Data {
        let start = offset
        try skip(count)
        return data.subdata(in: start..<offset)
    }
}

enum TiltArchive {
    static func read(_ data: Data) throws -> (metadata: Data, sketch: Data) {
        var header = LittleEndianReader(data: data)
        guard try header.u32() == 0x546c6974 else { throw WorkspaceError.invalid("Invalid TILT file header") }
        let headerSize = Int(try header.u16())
        guard try header.u16() == 1, (16...4096).contains(headerSize), headerSize <= data.count else {
            throw WorkspaceError.invalid("Unsupported TILT container version")
        }
        guard data.count >= headerSize + 22 else { throw WorkspaceError.invalid("Missing TILT ZIP directory") }
        let lowest = max(headerSize, data.count - 65_557)
        var directory: (end: Int, reader: LittleEndianReader)?
        for offset in stride(from: data.count - 22, through: lowest, by: -1) {
            var reader = LittleEndianReader(data: data, offset: offset)
            guard try reader.u32() == 0x06054b50 else { continue }
            var comment = LittleEndianReader(data: data, offset: offset + 20)
            if Int(try comment.u16()) == comment.remaining {
                directory = (offset, reader)
                break
            }
        }
        guard let directory else { throw WorkspaceError.invalid("Missing TILT ZIP directory") }
        var end = directory.reader
        let disk = try end.u16(), directoryDisk = try end.u16()
        let diskCount = try end.u16(), count = Int(try end.u16())
        let size = Int(try end.u32()), storedOffset = Int(try end.u32())
        let start = directory.end - size
        let bias = start - storedOffset
        guard disk == 0, directoryDisk == 0, Int(diskCount) == count, count > 0, count <= size / 46,
              start >= headerSize, bias == 0 || bias == headerSize else {
            throw WorkspaceError.invalid("Unsupported TILT ZIP layout")
        }

        var central = LittleEndianReader(data: data, offset: start)
        var found: [String: Data] = [:]
        for _ in 0..<count {
            guard try central.u32() == 0x02014b50 else { throw WorkspaceError.invalid("Bad TILT ZIP entry") }
            try central.skip(4)
            let flags = try central.u16(), method = try central.u16()
            try central.skip(4)
            let checksum = try central.u32(), packed = Int(try central.u32()), size = Int(try central.u32())
            let nameLength = Int(try central.u16()), extra = Int(try central.u16()), comment = Int(try central.u16())
            let entryDisk = try central.u16()
            try central.skip(6)
            let local = Int(try central.u32()) + bias
            let nameBytes = try central.bytes(nameLength)
            try central.skip(extra + comment)
            guard central.offset <= directory.end else { throw WorkspaceError.invalid("Bad TILT ZIP directory length") }
            guard let name = String(data: nameBytes, encoding: .utf8), name == "metadata.json" || name == "data.sketch"
            else { continue }
            guard found[name] == nil, entryDisk == 0, (flags & 1) == 0, (method == 0 || method == 8),
                  size > 0, packed > 0, (method != 0 || packed == size), (headerSize..<start).contains(local) else {
                throw WorkspaceError.invalid("Unsupported or duplicate TILT entry: \(name)")
            }
            var entry = LittleEndianReader(data: data, offset: local)
            guard try entry.u32() == 0x04034b50 else { throw WorkspaceError.invalid("Missing TILT local ZIP entry") }
            try entry.skip(2)
            guard try entry.u16() == flags, try entry.u16() == method else {
                throw WorkspaceError.invalid("Conflicting TILT ZIP entry metadata")
            }
            try entry.skip(16)
            let localName = Int(try entry.u16()), localExtra = Int(try entry.u16())
            guard try entry.bytes(localName) == nameBytes else {
                throw WorkspaceError.invalid("Conflicting TILT ZIP entry name")
            }
            try entry.skip(localExtra)
            guard packed <= start - entry.offset else { throw WorkspaceError.invalid("Truncated TILT ZIP entry") }
            let contents = try entry.bytes(packed)
            let decoded = method == 0 ? contents : try inflate(contents, size: size)
            guard decoded.count == size, crc32(decoded) == checksum else {
                throw WorkspaceError.invalid("Damaged TILT ZIP entry: \(name)")
            }
            found[name] = decoded
        }
        guard central.offset == directory.end, let metadata = found["metadata.json"],
              let sketch = found["data.sketch"] else { throw WorkspaceError.invalid("TILT is missing metadata or strokes") }
        return (metadata, sketch)
    }

    private static func inflate(_ source: Data, size: Int) throws -> Data {
        // Grow from decoded chunks instead of allocating the size claimed by the ZIP directory.
        var stream = compression_stream(dst_ptr: .init(bitPattern: 1)!, dst_size: 0,
                                        src_ptr: .init(bitPattern: 1)!, src_size: 0, state: nil)
        guard compression_stream_init(&stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK
        else { throw WorkspaceError.invalid("Could not start TILT decompression") }
        defer { compression_stream_destroy(&stream) }
        var output = Data()
        var chunk = [UInt8](repeating: 0, count: 256 * 1024)
        try source.withUnsafeBytes { input in
            stream.src_ptr = input.bindMemory(to: UInt8.self).baseAddress!
            stream.src_size = input.count
            while true {
                let (status, written) = chunk.withUnsafeMutableBufferPointer { buffer in
                    stream.dst_ptr = buffer.baseAddress!
                    stream.dst_size = buffer.count
                    let status = compression_stream_process(&stream, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                    return (status, buffer.count - stream.dst_size)
                }
                guard written <= size - output.count else { throw WorkspaceError.invalid("Invalid TILT DEFLATE length") }
                output.append(contentsOf: chunk.prefix(written))
                if status == COMPRESSION_STATUS_END, stream.src_size == 0, output.count == size { return }
                guard status == COMPRESSION_STATUS_OK, written > 0 else {
                    throw WorkspaceError.invalid("Invalid TILT DEFLATE entry")
                }
            }
        }
        return output
    }

    private static let crcTable: [UInt32] = (0..<256).map { index in
        var value = UInt32(index)
        for _ in 0..<8 { value = (value >> 1) ^ ((value & 1) == 0 ? 0 : 0xedb88320) }
        return value
    }

    private static func crc32(_ data: Data) -> UInt32 {
        data.reduce(UInt32.max) { (crc, byte) in
            (crc >> 8) ^ crcTable[Int((crc ^ UInt32(byte)) & 255)]
        } ^ UInt32.max
    }
}
