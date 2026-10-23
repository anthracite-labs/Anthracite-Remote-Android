// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "AnthraciteKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "AnthraciteKit", targets: ["AnthraciteKit"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-nio.git", from: "2.65.0"),
        .package(url: "https://github.com/apple/swift-nio-ssh.git", from: "0.15.0"),
    ],
    targets: [
        .target(
            name: "AnthraciteKit",
            dependencies: [
                .product(name: "NIOCore", package: "swift-nio"),
                .product(name: "NIOPosix", package: "swift-nio"),
                .product(name: "NIOFoundationCompat", package: "swift-nio"),
                .product(name: "NIOSSH", package: "swift-nio-ssh"),
            ]
        ),
        .testTarget(name: "AnthraciteKitTests", dependencies: ["AnthraciteKit"]),
    ]
)
