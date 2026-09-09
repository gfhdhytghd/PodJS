// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "PodJSCompanion",
    platforms: [.iOS(.v16), .watchOS(.v11), .macOS(.v13)],
    products: [.library(name: "PodJSCompanion", targets: ["PodJSCompanion"])],
    targets: [
        .systemLibrary(name: "CPodJSSync"),
        .target(name: "CPodJSJournal", publicHeadersPath: "include"),
        .target(name: "PodJSCompanion", dependencies: ["CPodJSSync", "CPodJSJournal"]),
        .target(name: "CPodJSRuntimeTestSupport", path: "Tests/CPodJSRuntimeTestSupport", publicHeadersPath: "include"),
        .testTarget(name: "PodJSCompanionTests", dependencies: ["PodJSCompanion", "CPodJSRuntimeTestSupport"])
    ]
)
