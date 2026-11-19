// SPDX-License-Identifier: Apache-2.0
// Test-only geometry and ZIP records generated for the source-review snapshot.
import Foundation

/// Five mathematical paths with pressure and time, containing no external artwork.
enum SyntheticTilt {
    static func data() throws -> Data {
        let metadata = try JSONSerialization.data(withJSONObject: [
            "BrushIndex": ["d90c6ad8-af0f-4b54-b422-e0f92abe1b3c"]
        ], options: [.sortedKeys])
        var sketch = Data()
        for value: UInt32 in [0xc576a5cd, 5, 0, 0, 5] { append(value, to: &sketch) }
        for stroke in 0..<5 {
            append(UInt32(0), to: &sketch)
            for value: Float in [0.2, 0.5, 0.8, 1, 0.2] { append(value.bitPattern, to: &sketch) }
            for value: UInt32 in [0, 3, 121] { append(value, to: &sketch) }
            for point in 0..<121 {
                let t = Float(point) / 120
                let coordinates: [Float] = [t * 2, sin(t * .pi * 2) * 0.2, Float(stroke) * 0.4]
                for value in coordinates + [0, 0, 0, 1, 0.5 + t * 0.5] {
                    append(value.bitPattern, to: &sketch)
                }
                append(UInt32(point * 10), to: &sketch)
            }
        }
        var local = Data()
        var directory = Data()
        for (name, contents) in [("metadata.json", metadata), ("data.sketch", sketch)] {
            let filename = Data(name.utf8)
            let checksum = crc32(contents)
            let size = UInt32(contents.count)
            let offset = UInt32(local.count)
            append(UInt32(0x04034b50), to: &local)
            for value: UInt16 in [20, 0, 0, 0, 0] { append(value, to: &local) }
            for value in [checksum, size, size] { append(value, to: &local) }
            append(UInt16(filename.count), to: &local)
            append(UInt16(0), to: &local)
            local.append(filename)
            local.append(contents)

            append(UInt32(0x02014b50), to: &directory)
            for value: UInt16 in [20, 20, 0, 0, 0, 0] { append(value, to: &directory) }
            for value in [checksum, size, size] { append(value, to: &directory) }
            for value: UInt16 in [UInt16(filename.count), 0, 0, 0, 0] { append(value, to: &directory) }
            append(UInt32(0), to: &directory)
            append(offset, to: &directory)
            directory.append(filename)
        }
        var header = Data()
        append(UInt32(0x546c6974), to: &header)
        append(UInt16(16), to: &header)
        append(UInt16(1), to: &header)
        append(UInt64(0), to: &header)
        var end = Data()
        append(UInt32(0x06054b50), to: &end)
        for value: UInt16 in [0, 0, 2, 2] { append(value, to: &end) }
        append(UInt32(directory.count), to: &end)
        append(UInt32(local.count), to: &end)
        append(UInt16(0), to: &end)
        return header + local + directory + end
    }

    private static func append<T: FixedWidthInteger>(_ value: T, to data: inout Data) {
        var littleEndian = value.littleEndian
        withUnsafeBytes(of: &littleEndian) { data.append(contentsOf: $0) }
    }

    private static func crc32(_ data: Data) -> UInt32 {
        var checksum = UInt32.max
        for byte in data {
            checksum ^= UInt32(byte)
            for _ in 0..<8 {
                checksum = (checksum >> 1) ^ (checksum & 1 == 0 ? 0 : 0xedb88320)
            }
        }
        return checksum ^ UInt32.max
    }
}
