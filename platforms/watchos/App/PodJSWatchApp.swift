import SwiftUI
import SpriteKit
import Combine
import PodJSWatch

@main
struct PodJSWatchGalleryApp: App {
    var body: some Scene {
        WindowGroup {
            PodJSWatchRootView()
        }
        .persistentSystemOverlays(.hidden)
    }
}

private struct PodJSWatchRootView: View {
    @State private var host: PodWatchHost?
    @State private var status = "Starting PodJS…"
    @State private var crownValue = 0.0
    @FocusState private var crownFocused: Bool
    private let frameTimer = Timer.publish(every: 1.0 / 60.0, on: .main, in: .common).autoconnect()

    var body: some View {
        Group {
            if let host {
                ZStack {
                    SpriteView(scene: host.scene, preferredFramesPerSecond: 60)
                        .accessibilityHidden(true)
                        .allowsHitTesting(false)
                        .focusable(false)
                    TouchScrollBridge { pointDelta in
                        host.addCrownDegrees(Double(pointDelta) * 0.4)
                        do {
                            try host.frame()
                        } catch {
                            let message = String(describing: error)
                            status = message
                            recordDiagnostic(message)
                        }
                    }
                    .accessibilityHidden(true)
                    PodAccessibilityOverlay(host: host)
                }
                .ignoresSafeArea()
                ._statusBarHidden(true)
                .persistentSystemOverlays(.hidden)
                .accessibilityIdentifier("podjs-crown-status")
                .focusable(true, interactions: .edit)
                .focused($crownFocused)
                .digitalCrownRotation(
                    Binding(
                        get: { crownValue },
                        set: { newValue in
                            // Keep the list's touch-scroll direction unchanged;
                            // the crown's native delta is opposite to the list axis.
                            host.addCrownDegrees((newValue - crownValue) * -0.12)
                            crownValue = newValue
                            do {
                                try host.frame()
                            } catch {
                                let message = String(describing: error)
                                status = message
                                recordDiagnostic(message)
                            }
                        }
                    ),
                    from: -100_000,
                    through: 100_000,
                    by: 1,
                    sensitivity: .high,
                    isContinuous: true,
                    isHapticFeedbackEnabled: false
                )
            } else {
                VStack(spacing: 8) {
                    Text("PodJS")
                        .font(.headline)
                    ScrollView {
                        Text(status)
                            .font(.system(size: 9))
                            .multilineTextAlignment(.center)
                    }
                }
            }
        }
        .task {
            guard host == nil else { return }
            do {
                let created = try PodWatchHost(bundle: .main)
                try created.frame()
                host = created
                status = "Runtime ready"
                crownFocused = true
                recordDiagnostic("runtime ready")
                print("PodJSWatch: runtime ready")
            } catch {
                let message = String(describing: error)
                status = message
                recordDiagnostic(message)
                print("PodJSWatch: runtime error: \(message)")
            }
        }
        .onReceive(frameTimer) { _ in
            guard let host else { return }
            do {
                try host.frame()
            } catch {
                let message = String(describing: error)
                status = message
                recordDiagnostic(message)
            }
        }
    }

    private func recordDiagnostic(_ message: String) {
        guard let directory = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first else { return }
        try? FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        try? message.write(
            to: directory.appendingPathComponent("podjs-last-error.txt"),
            atomically: true,
            encoding: .utf8
        )
    }
}

private struct TouchScrollBridge: View {
    let onDelta: (CGFloat) -> Void
    @State private var previousOffset: CGFloat?

    var body: some View {
        ScrollView(.vertical) {
            Color.black.opacity(0.001)
                .frame(height: 40_000)
                .background {
                    GeometryReader { geometry in
                        Color.clear.preference(
                            key: TouchScrollOffsetKey.self,
                            value: geometry.frame(in: .named("podjs-touch-scroll")).minY
                        )
                    }
                }
        }
        .coordinateSpace(name: "podjs-touch-scroll")
        .scrollIndicators(.hidden)
        .focusable(false)
        .onPreferenceChange(TouchScrollOffsetKey.self) { offset in
            defer { previousOffset = offset }
            guard let previousOffset else { return }
            let delta = offset - previousOffset
            guard abs(delta) >= 0.25 else { return }
            onDelta(delta)
        }
        .accessibilityIdentifier("podjs-touch-scroll")
    }
}

private struct TouchScrollOffsetKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}
