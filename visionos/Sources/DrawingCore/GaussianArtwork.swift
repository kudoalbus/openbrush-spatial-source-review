import Foundation
import simd

/// GPU records contain XYZ, scale XYZ, rotation WXYZ, opacity and RGB SH coefficients.
struct GaussianArtwork {
    let bytes: Data
    let count: Int
    let degree: UInt8
    let stride: Int
    let usesLinearScaleAndOpacity: Bool
    let min: SIMD3<Float>
    let max: SIMD3<Float>

    static func read(_ url: URL) throws -> Self {
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        return try url.pathExtension.lowercased() == "ply" ? readPLY(data) : readPacked(data)
    }

    static func readPacked(_ data: Data) throws -> Self {
        guard !data.isEmpty, data.count.isMultiple(of: 32) else {
            throw WorkspaceError.invalid("Expected 32-byte Gaussian SPLAT records")
        }
        let sh: Float = 0.28209479177387814
        return try data.withUnsafeBytes { input in
            try make(count: input.count / 32, degree: 0, linear: true) { row, output in
                let start = row * 32
                for i in 0..<6 { output[i] = float(input, start + i * 4, littleEndian: true) }
                guard (3...5).allSatisfy({ output[$0] > 0 }) else {
                    throw WorkspaceError.invalid("Gaussian SPLAT scales must be positive")
                }
                for i in 0..<4 { output[i + 6] = (Float(input[start + 28 + i]) - 128) / 128 }
                output[10] = Float(input[start + 27]) / 255
                for i in 0..<3 { output[11 + i] = (Float(input[start + 24 + i]) / 255 - 0.5) / sh }
            }
        }
    }

    private struct PLYHeader {
        var littleEndian: Bool?
        var count = 0
        var vertexStride = 0
        var properties: [String: Int] = [:]
        var dataStart = 0

        init(_ data: Data) throws {
            var offset = data.startIndex
            var inVertex = false
            var seenVertex = false
            var sawPriorData = false
            guard let first = data.firstIndex(of: 10),
                  String(decoding: data[..<first], as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines) == "ply"
            else { throw WorkspaceError.invalid("Invalid PLY header") }
            offset = first + 1
            while let end = data[offset...].firstIndex(of: 10) {
                let fields = String(decoding: data[offset..<end], as: UTF8.self).split(whereSeparator: \.isWhitespace)
                offset = end + 1
                switch fields.first {
                case "format" where fields.count == 3:
                    switch fields[1] {
                    case "binary_little_endian": littleEndian = true
                    case "binary_big_endian": littleEndian = false
                    default: throw WorkspaceError.invalid("Gaussian PLY requires binary encoding")
                    }
                case "element" where fields.count == 3:
                    guard let rows = Int(fields[2]), rows >= 0 else { throw WorkspaceError.invalid("Invalid PLY element count") }
                    inVertex = fields[1] == "vertex"
                    if inVertex {
                        guard !seenVertex, !sawPriorData else {
                            throw WorkspaceError.invalid("Gaussian PLY requires a leading vertex element")
                        }
                        count = rows
                        seenVertex = true
                    } else if !seenVertex, rows > 0 { sawPriorData = true }
                case "property" where inVertex && fields.count == 3:
                    let width: Int
                    switch fields[1] {
                    case "char", "int8", "uchar", "uint8": width = 1
                    case "short", "int16", "ushort", "uint16": width = 2
                    case "int", "int32", "uint", "uint32": width = 4
                    case "float", "float32":
                        width = 4
                        properties[String(fields[2])] = vertexStride
                    case "double", "float64": width = 8
                    default: throw WorkspaceError.invalid("Unsupported PLY vertex property")
                    }
                    vertexStride += width
                case "property" where inVertex:
                    throw WorkspaceError.invalid("Unsupported PLY vertex list")
                case "end_header":
                    dataStart = offset - data.startIndex
                    guard littleEndian != nil, count > 0, vertexStride > 0,
                          count <= (data.count - dataStart) / vertexStride else {
                        throw WorkspaceError.invalid("Truncated or empty Gaussian PLY vertices")
                    }
                    return
                default: break
                }
            }
            throw WorkspaceError.invalid("Missing PLY header terminator")
        }

        func offsets(_ names: [String]) throws -> [Int] {
            try names.map { name in
                guard let offset = properties[name] else {
                    throw WorkspaceError.invalid("Missing Gaussian PLY property: \(name)")
                }
                return offset
            }
        }
    }

    static func readPLY(_ data: Data) throws -> Self {
        let header = try PLYHeader(data)
        let scalar = try header.offsets(["x", "y", "z", "scale_0", "scale_1", "scale_2",
                                         "rot_0", "rot_1", "rot_2", "rot_3", "opacity",
                                         "f_dc_0", "f_dc_1", "f_dc_2"])
        let restCount = header.properties.keys.count { $0.hasPrefix("f_rest_") }
        guard let degree = [0, 9, 24, 45].firstIndex(of: restCount),
              let littleEndian = header.littleEndian else {
            throw WorkspaceError.invalid("Unsupported Gaussian PLY spherical harmonics")
        }
        let rest = try header.offsets((0..<restCount).map { "f_rest_\($0)" })
        return try data.withUnsafeBytes { input in
            try make(count: header.count, degree: UInt8(degree), linear: false) { row, output in
                let start = header.dataStart + row * header.vertexStride
                for (i, offset) in scalar.enumerated() { output[i] = float(input, start + offset, littleEndian: littleEndian) }
                let bands = restCount / 3
                for i in 0..<bands {
                    for channel in 0..<3 {
                        output[14 + i * 3 + channel] = float(input, start + rest[channel * bands + i], littleEndian: littleEndian)
                    }
                }
            }
        }
    }

    private static func float(_ data: UnsafeRawBufferPointer, _ offset: Int, littleEndian: Bool) -> Float {
        let bits = data.loadUnaligned(fromByteOffset: offset, as: UInt32.self)
        return Float(bitPattern: littleEndian ? UInt32(littleEndian: bits) : UInt32(bigEndian: bits))
    }

    private static func make(count: Int, degree: UInt8, linear: Bool,
                             fill: (Int, UnsafeMutableBufferPointer<Float>) throws -> Void) throws -> Self {
        let coefficients = Int(degree + 1) * Int(degree + 1)
        let columns = 11 + 3 * coefficients
        let stride = columns * MemoryLayout<Float>.size
        let (size, overflow) = count.multipliedReportingOverflow(by: stride)
        guard !overflow else { throw WorkspaceError.invalid("Gaussian vertex storage overflows") }
        var data = Data(count: size)
        var low = SIMD3<Float>(repeating: .infinity)
        var high = SIMD3<Float>(repeating: -.infinity)
        try data.withUnsafeMutableBytes { raw in
            let values = raw.bindMemory(to: Float.self)
            for i in 0..<count {
                let row = UnsafeMutableBufferPointer(start: values.baseAddress! + i * columns, count: columns)
                try fill(i, row)
                guard row.allSatisfy(\.isFinite) else { throw WorkspaceError.invalid("Nonfinite Gaussian vertex data") }
                let rotation = SIMD4(row[6], row[7], row[8], row[9])
                let length = simd_length(rotation)
                guard length.isFinite, length > 1e-6 else { throw WorkspaceError.invalid("Invalid Gaussian rotation") }
                for j in 0..<4 { row[6 + j] = rotation[j] / length }
                let position = SIMD3(row[0], row[1], row[2])
                let scale = Swift.max(row[3], row[4], row[5])
                let radius = SIMD3(repeating: (linear ? scale : exp(scale)) * 2)
                low = simd_min(low, position - radius)
                high = simd_max(high, position + radius)
            }
        }
        guard (high - low).max().isFinite, (high - low).max() > 0 else {
            throw WorkspaceError.invalid("Invalid Gaussian artwork extent")
        }
        return Self(bytes: data, count: count, degree: degree, stride: stride, usesLinearScaleAndOpacity: linear,
                    min: low, max: high)
    }
}
