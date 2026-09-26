import LlmSampleKit
import SwiftUI

/// Runs the on-device benchmark once per launch: CPU, then Metal. Every line also goes to
/// stdout with a [bench] prefix, for `xcrun devicectl device process launch --console`.
@main
struct LlmSampleApp: App {
    var body: some Scene { WindowGroup { ContentView() } }
}

struct ContentView: View {
    @State private var lines: [String] = ["running…"]

    var body: some View {
        ScrollView {
            Text(lines.joined(separator: "\n\n"))
                .font(.system(.footnote, design: .monospaced))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .task { await run() }
    }

    private func run() async {
        let result = await Task.detached(priority: .userInitiated) { () -> [String] in
            var out: [String] = []
            func note(_ line: String) {
                let full = "\(line) footprintMB=\(footprintMB())"
                print("[bench] \(full)")
                out.append(full)
            }
            guard let path = Bundle.main.path(forResource: "MiniCPM5-1B-Q4_K_M", ofType: "gguf") else {
                note("error=model not in bundle")
                return out
            }
            note("device=\(UIDevice.current.model) ios=\(UIDevice.current.systemVersion) cores=\(ProcessInfo.processInfo.activeProcessorCount) ramMB=\(ProcessInfo.processInfo.physicalMemory / 1_048_576)")
            let bench = Bench()
            for gpuLayers: Int32 in [0, -1] {
                do {
                    note(try bench.load(path: path, gpuLayers: gpuLayers))
                    note(try bench.narrate())
                    note(try bench.intent())
                } catch {
                    note("error=\(error.localizedDescription)")
                }
                bench.close()
                note("closed gpuLayers=\(gpuLayers)")
            }
            print("[bench] done")
            return out
        }.value
        lines = result
    }
}

/// The app's memory footprint as iOS counts it against its limit (phys_footprint).
func footprintMB() -> Int {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
    let kr = withUnsafeMutablePointer(to: &info) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
        }
    }
    return kr == KERN_SUCCESS ? Int(info.phys_footprint / 1_048_576) : -1
}
