import SwiftUI
import WatchGPTKit

/// WatchGPT, animated: the same engine as on Android (WatchGPTKit's Director), drawn with a Canvas. A frame is a pure
/// function of time, so the canvas only asks for the picture at each tick: 12 a second, 20 while it talks (the mouth),
/// none in always-on, where it shows a still outline.
struct FaceView: View {
    let genes: Genes
    let act: Act
    let reaction: Reaction?
    let reactionId: Int
    let pulse: Pulse

    @Environment(\.isLuminanceReduced) private var dimmed
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var clock = FaceClock()

    var body: some View {
        TimelineView(.animation(minimumInterval: act == .speak ? 1.0 / 20 : 1.0 / 12, paused: dimmed)) { timeline in
            Canvas { context, size in
                let date = timeline.date
                clock.update(genes: genes, still: reduceMotion, act: act, reaction: reaction, reactionId: reactionId, at: date)
                let level = act == .speak ? pulse.level(at: date) : 0
                let frame = clock.frame(at: date, level: level, blend: !dimmed)
                if dimmed {
                    FaceDrawing.ambient(frame, in: context, size: size)
                } else {
                    FaceDrawing.draw(frame, genes: genes, in: context, size: size)
                }
            }
        }
    }
}

/// Keeps WatchGPT's clock and the scene: when the act changed and which reaction plays, stamped as they arrive (the
/// Android renderer does the same), so the picture doesn't depend on the frame rate.
final class FaceClock {
    private let start = Date()
    private var director: Director?
    private var genes: Genes?
    private var still = false
    private var scene = FaceScene(act: .rest)
    private var reactionId = 0

    func update(genes: Genes, still: Bool, act: Act, reaction: Reaction?, reactionId: Int, at date: Date) {
        if genes != self.genes || still != self.still || director == nil {
            self.genes = genes
            self.still = still
            director = Director(genes: genes, still: still)
        }
        let t = date.timeIntervalSince(start)
        if act != scene.act {
            scene.act = act
            scene.actAt = t
        }
        if reactionId != self.reactionId {
            self.reactionId = reactionId
            scene.reaction = reaction
            scene.reactionAt = t
        }
    }

    func frame(at date: Date, level: Float, blend: Bool) -> Frame {
        director!.frame(scene, level: level, t: date.timeIntervalSince(start), blend: blend)
    }
}

/// Draws a frame the way the Android app's drawWatchGPT does (after bloub's BloubBot.vue): a flat body in WatchGPT's
/// colour with the eyes and mouth cut out (painted in the background colour), the back of every orbit drawn first so
/// the body hides it.
enum FaceDrawing {
    /// The ball's radius as a share of the smaller side: leaves room for the rings (up to 1.3 body units).
    static let ball: CGFloat = 0.34

    static func draw(_ frame: Frame, genes: Genes, in context: GraphicsContext, size: CGSize) {
        let s = Space(size)
        let ink = Color(rgb: genes.ink)
        let paper = Color.black
        frame.arcs.forEach { arc($0, s, context, front: false) }
        if frame.dotsBehind { frame.dots.forEach { dot($0, s, context, ink: genes.ink) } }
        context.fill(body(frame.body, s), with: .color(ink))
        frame.features.forEach { feature($0, s, context, paper) }
        if let badge = frame.badge { context.fill(Path(ellipseIn: s.circle(badge.x, badge.y, badge.r + badge.gap)), with: .color(paper)) }
        if !frame.dotsBehind { frame.dots.forEach { dot($0, s, context, ink: genes.ink) } }
        if let badge = frame.badge { self.badge(badge, s, context, Color(rgb: genes.accent)) }
        frame.arcs.forEach { arc($0, s, context, front: true) }
    }

    /// Always-on: only an outline and the face, in grey, so almost every pixel stays off.
    static func ambient(_ frame: Frame, in context: GraphicsContext, size: CGSize) {
        let s = Space(size)
        let grey = Color(white: 0.55)
        context.stroke(body(frame.body, s), with: .color(grey), lineWidth: s.k * 0.035)
        frame.features.forEach { feature($0, s, context, grey) }
    }

    private struct Space {
        let cx: CGFloat
        let cy: CGFloat
        let k: CGFloat

        init(_ size: CGSize) {
            cx = size.width / 2
            cy = size.height / 2
            k = min(size.width, size.height) * FaceDrawing.ball
        }

        func p(_ x: Float, _ y: Float) -> CGPoint {
            CGPoint(x: cx + CGFloat(x) * k, y: cy + CGFloat(y) * k)
        }

        func circle(_ x: Float, _ y: Float, _ r: Float) -> CGRect {
            let c = p(x, y)
            let radius = CGFloat(r) * k
            return CGRect(x: c.x - radius, y: c.y - radius, width: radius * 2, height: radius * 2)
        }
    }

    /// A closed Catmull-Rom curve through the outline points, as cubic Béziers (tension 1/6, like bloub).
    private static func body(_ pts: [Float], _ s: Space) -> Path {
        let n = pts.count / 2
        func at(_ i: Int) -> CGPoint {
            let j = ((i % n) + n) % n
            return s.p(pts[2 * j], pts[2 * j + 1])
        }
        var path = Path()
        path.move(to: at(0))
        for i in 0 ..< n {
            let p0 = at(i - 1), p1 = at(i), p2 = at(i + 1), p3 = at(i + 2)
            path.addCurve(
                to: p2,
                control1: CGPoint(x: p1.x + (p2.x - p0.x) / 6, y: p1.y + (p2.y - p0.y) / 6),
                control2: CGPoint(x: p2.x - (p3.x - p1.x) / 6, y: p2.y - (p3.y - p1.y) / 6)
            )
        }
        path.closeSubpath()
        return path
    }

    /// An eye or the mouth in its own frame, mapped onto the body by the feature's matrix.
    private static func feature(_ f: Feature, _ s: Space, _ context: GraphicsContext, _ color: Color) {
        let alpha = Double(min(max(f.alpha, 0), 1))
        guard alpha >= 0.01 else { return }
        var ctx = context
        ctx.opacity = alpha
        ctx.concatenate(CGAffineTransform(
            a: CGFloat(f.a) * s.k, b: CGFloat(f.b) * s.k, c: CGFloat(f.c) * s.k, d: CGFloat(f.d) * s.k,
            tx: s.cx + CGFloat(f.e) * s.k, ty: s.cy + CGFloat(f.f) * s.k
        ))
        guard let band = f.band else {
            let w = CGFloat(f.w), h = CGFloat(f.h)
            ctx.fill(Path(roundedRect: CGRect(x: -w / 2, y: -h / 2, width: w, height: h), cornerRadius: min(w, h) / 2), with: .color(color))
            return
        }
        // The line: a parabola, which one quadratic curve draws exactly, with round ends.
        let hw = CGFloat(band.w) / 2
        let bend = 2 * CGFloat(band.curve) * hw
        var line = Path()
        line.move(to: CGPoint(x: -hw, y: 0))
        line.addQuadCurve(to: CGPoint(x: hw, y: 0), control: CGPoint(x: 0, y: bend))
        ctx.stroke(line, with: .color(color), style: StrokeStyle(lineWidth: CGFloat(band.thick), lineCap: .round))
        guard band.open > 0.004 else { return }
        // Opened: the space down to a round "U" (and up to another for an "o", by the mouth's up).
        let low = (CGFloat(band.curve) * hw + CGFloat(band.open * (1 - band.up))) * 4 / 3
        let high = (CGFloat(band.curve) * hw - CGFloat(band.open * band.up)) * 4 / 3
        var lens = Path()
        lens.move(to: CGPoint(x: -hw, y: 0))
        if band.up > 0 {
            lens.addCurve(to: CGPoint(x: hw, y: 0), control1: CGPoint(x: -hw, y: high), control2: CGPoint(x: hw, y: high))
        } else {
            lens.addQuadCurve(to: CGPoint(x: hw, y: 0), control: CGPoint(x: 0, y: bend))
        }
        lens.addCurve(to: CGPoint(x: -hw, y: 0), control1: CGPoint(x: hw, y: low), control2: CGPoint(x: -hw, y: low))
        lens.closeSubpath()
        ctx.fill(lens, with: .color(color))
    }

    private static func arc(_ arc: Arc, _ s: Space, _ context: GraphicsContext, front: Bool) {
        let runs = front ? arc.front : arc.back
        guard !runs.isEmpty else { return }
        var ctx = context
        ctx.opacity = Double(min(max(arc.opacity, 0), 1))
        let shading = GraphicsContext.Shading.linearGradient(
            Gradient(colors: arc.colors.map { Color(rgb: $0) }),
            startPoint: s.p(arc.x1, arc.y1),
            endPoint: s.p(arc.x2, arc.y2)
        )
        let style = StrokeStyle(lineWidth: CGFloat(arc.width) * s.k, lineCap: .round, lineJoin: .round)
        for run in runs {
            var path = Path()
            path.move(to: s.p(run[0], run[1]))
            for i in stride(from: 2, to: run.count - 1, by: 2) {
                path.addLine(to: s.p(run[i], run[i + 1]))
            }
            ctx.stroke(path, with: shading, style: style)
        }
    }

    private static func dot(_ dot: Dot, _ s: Space, _ context: GraphicsContext, ink: UInt32) {
        // Particles far away fade into the (black) background.
        let color = Color(rgb: ink).opacity(Double(min(max(dot.depth, 0), 1)))
        var ctx = context
        ctx.opacity = Double(min(max(dot.opacity, 0), 1))
        guard let shape = dot.shape else {
            ctx.fill(Path(ellipseIn: s.circle(dot.x, dot.y, dot.r)), with: .color(color))
            return
        }
        let rad = dot.rot * .pi / 180
        let c = cos(rad), n = sin(rad)
        var path = Path()
        for i in stride(from: 0, to: shape.count - 1, by: 2) {
            let point = s.p(dot.x + shape[i] * c - shape[i + 1] * n, dot.y + shape[i] * n + shape[i + 1] * c)
            if i == 0 { path.move(to: point) } else { path.addLine(to: point) }
        }
        path.closeSubpath()
        ctx.fill(path, with: .color(color))
    }

    private static func badge(_ badge: Badge, _ s: Space, _ context: GraphicsContext, _ accent: Color) {
        guard badge.heart else {
            context.fill(Path(ellipseIn: s.circle(badge.x, badge.y, badge.r)), with: .color(accent))
            return
        }
        // A heart the size of the badge: two lobes and a point.
        let r = CGFloat(badge.r) * s.k
        let c = s.p(badge.x, badge.y)
        var path = Path()
        path.move(to: CGPoint(x: c.x, y: c.y + r * 0.95))
        path.addCurve(to: CGPoint(x: c.x, y: c.y - r * 0.45), control1: CGPoint(x: c.x - r * 1.35, y: c.y + r * 0.05), control2: CGPoint(x: c.x - r * 0.85, y: c.y - r * 1.05))
        path.addCurve(to: CGPoint(x: c.x, y: c.y + r * 0.95), control1: CGPoint(x: c.x + r * 0.85, y: c.y - r * 1.05), control2: CGPoint(x: c.x + r * 1.35, y: c.y + r * 0.05))
        path.closeSubpath()
        context.fill(path, with: .color(accent))
    }
}

extension Color {
    /// From 0xRRGGBB.
    init(rgb: UInt32) {
        self.init(red: Double(rgb >> 16 & 0xFF) / 255, green: Double(rgb >> 8 & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }
}
