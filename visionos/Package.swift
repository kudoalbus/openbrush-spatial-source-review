// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "OpenBrushSpatialCore",
    platforms: [.macOS(.v14), .visionOS(.v2)],
    products: [.library(name: "DrawingCore", targets: ["DrawingCore"])],
    targets: [
        .target(name: "DrawingCore"),
        .testTarget(name: "DrawingCoreTests", dependencies: ["DrawingCore"])
    ]
)
