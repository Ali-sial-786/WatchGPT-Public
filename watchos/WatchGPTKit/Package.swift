// swift-tools-version:5.9

import PackageDescription

// What the Apple Watch app shares with nothing platform-specific: ChatGPT sign-in and answers, the safety gate,
// memory, reminders, sleep, the watch's tools and WatchGPT's animation. Plain Foundation, so it also builds and
// tests on Linux; the app adds SwiftUI, speech, notifications and HealthKit on top.
let package = Package(
    name: "WatchGPTKit",
    platforms: [.watchOS(.v10), .iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "WatchGPTKit", targets: ["WatchGPTKit"]),
    ],
    targets: [
        .target(name: "WatchGPTKit"),
        .testTarget(name: "WatchGPTKitTests", dependencies: ["WatchGPTKit"]),
    ]
)
