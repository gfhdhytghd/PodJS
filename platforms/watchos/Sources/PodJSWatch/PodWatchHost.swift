#if os(watchOS)
import Foundation
import SpriteKit
import WatchKit
import CPodJS
import Combine

public final class PodWatchHost: ObservableObject {
    @Published public private(set) var semanticNodes: [PodSemanticNode] = []
    private var semanticHash: UInt64 = 0
    public let scene: PodScene
    private var runtime: OpaquePointer?
    private var guestIoGate: OpaquePointer?
    private let logicalWidth: CGFloat
    private let logicalHeight: CGFloat
    private var crownRemainder: Double = 0
    private var touches: [PodTouch] = []
    private static let renderScale: UInt32 = 2

    public init(bundle: Bundle = .main) throws {
        let device = WKInterfaceDevice.current()
        let screenBounds = device.screenBounds
        let screenScale = device.screenScale
        let physicalWidth = UInt32((screenBounds.width * screenScale).rounded())
        let physicalHeight = UInt32((screenBounds.height * screenScale).rounded())
        logicalWidth = CGFloat((physicalWidth + Self.renderScale - 1) / Self.renderScale)
        logicalHeight = CGFloat((physicalHeight + Self.renderScale - 1) / Self.renderScale)
        scene = PodScene(size: CGSize(width: logicalWidth, height: logicalHeight))
        guard pod_runtime_abi_version() == PODJS_RUNTIME_ABI_VERSION else { throw HostError.abi }
        let data = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].path
        try FileManager.default.createDirectory(atPath: data, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        guard let gate = data.withCString({ pod_guest_io_open($0) }) else { throw HostError.runtime("Guest file IO gate unavailable") }
        guestIoGate = gate
        guard pod_guest_io_try_enter(gate) == 1 else {
            pod_guest_io_close(gate); guestIoGate = nil; throw HostError.runtime("Guest file IO busy")
        }
        var initialized = false
        defer {
            pod_guest_io_leave(gate)
            if !initialized {
                if let runtime { pod_runtime_destroy(runtime); self.runtime = nil }
                pod_guest_io_close(gate); guestIoGate = nil
            }
        }
        runtime = "watchos-watch".withCString { target in data.withCString { path in
          let caps = "[\"input.touch\",\"input.rotary\",\"data.kv\",\"device.haptics\",\"host.lifecycle\",\"host.theme\",\"display.round\",\"net.http\",\"data.fs\"]"
          return caps.withCString { capabilities in
            var c = PodRuntimeConfig(struct_size: UInt32(MemoryLayout<PodRuntimeConfig>.size), target_id: target,
              host_abi: UInt32(PODJS_RUNTIME_ABI_VERSION), raster_density: Self.renderScale, physical_width: physicalWidth, physical_height: physicalHeight,
              display_density: Float(screenScale), display_shape: UInt32(POD_DISPLAY_RECT.rawValue), safe_top: 0, safe_right: 0, safe_bottom: 0, safe_left: 0, data_dir: path, capabilities_json: capabilities)
            return pod_runtime_create(&c)
          }
        }}
        guard let runtime else { throw HostError.runtime(String(cString: pod_runtime_last_error())) }
        guard let pak = bundle.url(forResource: "main", withExtension: "pak"), let js = bundle.url(forResource: "main", withExtension: "js"), let manifest = bundle.url(forResource: "pod.manifest", withExtension: "json") else { throw HostError.assets }
        let pb = try Data(contentsOf: pak), jb = try Data(contentsOf: js), mb = try String(contentsOf: manifest, encoding: .utf8)
        try checked(pb.withUnsafeBytes { pod_runtime_load_pak(runtime, $0.bindMemory(to: UInt8.self).baseAddress, $0.count) })
        try checked(mb.withCString { pod_runtime_validate_package(runtime, $0) })
        try checked(jb.withUnsafeBytes { ptr in "app:///main.js".withCString { pod_runtime_eval_bundle(runtime, ptr.bindMemory(to: UInt8.self).baseAddress, ptr.count, $0) } })
        scene.scaleMode = .aspectFit
        scene.backgroundColor = .black
        try checked(pod_runtime_set_accessibility_enabled(runtime, 1))
        initialized = true
    }
    deinit { if let runtime { pod_runtime_destroy(runtime) }; if let guestIoGate { pod_guest_io_close(guestIoGate) } }
    public func addCrownDegrees(_ degrees: Double) { crownRemainder += degrees * 1000 }
    public func updatePrimaryTouch(location: CGPoint, in hostSize: CGSize) {
        guard let point = Self.logicalTouchPoint(location, in: hostSize, logicalSize: scene.size) else {
            touches.removeAll(keepingCapacity: true)
            return
        }
        touches = [PodTouch(id: 0, x: Float(point.x), y: Float(point.y))]
    }
    public func clearTouches() { touches.removeAll(keepingCapacity: true) }
    public static func logicalTouchPoint(
        _ point: CGPoint,
        in hostSize: CGSize,
        logicalSize: CGSize = CGSize(width: 240, height: 240)
    ) -> CGPoint? {
        guard hostSize.width > 0, hostSize.height > 0 else { return nil }
        let logicalWidth = logicalSize.width
        let logicalHeight = logicalSize.height
        let scale = min(hostSize.width / logicalWidth, hostSize.height / logicalHeight)
        let left = (hostSize.width - logicalWidth * scale) / 2
        let top = (hostSize.height - logicalHeight * scale) / 2
        let logical = CGPoint(x: (point.x - left) / scale, y: (point.y - top) / scale)
        guard logical.x >= 0, logical.x < logicalWidth,
              logical.y >= 0, logical.y < logicalHeight else { return nil }
        return logical
    }
    public func setLifecycle(_ state: UInt32) throws { try checked(pod_runtime_set_lifecycle(runtime, state)) }
    public func performAccessibilityAction(_ id: Int32, action: UInt8) {
        guard semanticNodes.contains(where: { $0.id == id && $0.permits(action) }) else { return }
        _ = pod_runtime_accessibility_action(runtime, id, semanticHash, Int32(action))
    }
    private func updateSemantics() throws {
        var snapshot = PodAccessibilitySnapshot()
        try checked(pod_runtime_accessibility_snapshot(runtime, &snapshot))
        guard snapshot.changed != 0, let bytes = snapshot.json else { return }
        do {
            let decoded = try JSONDecoder().decode(PodSemanticSnapshot.self, from: Data(bytes: bytes, count: snapshot.byte_length))
            guard decoded.schema == 1 else { throw HostError.runtime("Unsupported semantic schema") }
            semanticHash = snapshot.content_hash
            semanticNodes = decoded.nodes
        } catch {
            // Never leave outdated operable elements after a failed projection.
            semanticNodes = []
            throw error
        }
    }
    /// Advance one deterministic guest turn. A SpriteKit texture is submitted
    /// only when the canonical DrawList/resource hash changes.
    public func frame() throws {
        guard let guestIoGate else { throw HostError.runtime("Guest file IO gate closed") }
        let acquired = pod_guest_io_try_enter(guestIoGate)
        if acquired == 0 { return } // Keep input/crown state for the next turn.
        guard acquired == 1 else { throw HostError.runtime("Guest file IO gate failed") }
        defer { pod_guest_io_leave(guestIoGate) }
        var input = PodInputFrame(
            struct_size: UInt32(MemoryLayout<PodInputFrame>.size),
            buttons: 0,
            analog: 0x80808080,
            touches: nil,
            touch_count: 0,
            rotary_primary_millidegrees: Int32(crownRemainder.rounded(.towardZero)),
            rotary_secondary_millidegrees: 0
        )
        crownRemainder -= Double(input.rotary_primary_millidegrees)
        let frameResult = touches.withUnsafeBufferPointer { buffer in
            input.touches = buffer.baseAddress
            input.touch_count = UInt32(buffer.count)
            return pod_runtime_frame(runtime, &input)
        }
        if frameResult == 1 { return }
        try checked(frameResult)

        var snapshot = PodDrawList()
        try checked(pod_runtime_snapshot(runtime, &snapshot))
        // A label/action change can leave the paint hash unchanged.
        try updateSemantics()
        guard snapshot.changed != 0 else { return }

        let scale = Self.renderScale
        let pixelWidth = Int(pod_runtime_logical_width(runtime)) * Int(scale)
        let pixelHeight = Int(pod_runtime_logical_height(runtime)) * Int(scale)
        var pixels = [UInt8](repeating: 0, count: pixelWidth * pixelHeight * 4)
        try pixels.withUnsafeMutableBytes { bytes in
            try checked(pod_runtime_render_rgba(runtime, scale, bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count))
        }
        try scene.commit(
            rgba: pixels,
            pixelWidth: pixelWidth,
            pixelHeight: pixelHeight,
            generation: snapshot.content_hash
        )
    }
    private func checked(_ code: Int32) throws { if code < 0 { throw HostError.runtime(String(cString: pod_runtime_last_error())) } }
    public enum HostError: Error { case abi, assets, runtime(String) }
}
#endif
