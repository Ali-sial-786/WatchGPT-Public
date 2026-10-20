import Foundation

/// A point in body units: 1 is the radius of the ball at rest, y points down.
public struct Pt: Equatable, Sendable {
    public let x: Float
    public let y: Float
}

/// A body outline: its radius at [samples] fixed angles (θ = 0 points right and grows clockwise on screen, y down),
/// plus a pose. Every outline shares the same angles, so any two morph by blending their radii point for point.
///
/// Ported from bloub by Jérémy Perret (MIT, github.com/jeremy-prt/bloub, src/bot/shape.ts) through the Android app's
/// core/buddy; see THIRD_PARTY_NOTICES.md.
public struct Silhouette: Equatable, Sendable {
    public var radii: [Float]
    /// Rotation of the profile, radians.
    public var rot: Float = 0
    public var cx: Float = 0
    public var cy: Float = 0
    /// Squash and stretch, on screen after the rotation.
    public var sx: Float = 1
    public var sy: Float = 1

    public init(_ radii: [Float], rot: Float = 0, cx: Float = 0, cy: Float = 0, sx: Float = 1, sy: Float = 1) {
        self.radii = radii
        self.rot = rot
        self.cx = cx
        self.cy = cy
        self.sx = sx
        self.sy = sy
    }

    /// The outline as points (x, y interleaved), pose applied.
    public func points() -> [Float] {
        var out = [Float](repeating: 0, count: Self.samples * 2)
        let cr = cos(rot)
        let sr = sin(rot)
        for i in 0 ..< Self.samples {
            let x = radii[i] * Self.cosines[i]
            let y = radii[i] * Self.sines[i]
            out[2 * i] = (x * cr - y * sr) * sx + cx
            out[2 * i + 1] = (x * sr + y * cr) * sy + cy
        }
        return out
    }

    public static let samples = 64
    static let tau = 2 * Float.pi
    static let cosines = (0 ..< samples).map { cos(angle($0)) }
    static let sines = (0 ..< samples).map { sin(angle($0)) }

    public static func angle(_ i: Int) -> Float {
        Float(i) * tau / Float(samples)
    }

    public static func circle(_ radius: Float, cx: Float = 0, cy: Float = 0, rot: Float = 0) -> Silhouette {
        Silhouette([Float](repeating: radius, count: samples), rot: rot, cx: cx, cy: cy)
    }

    /// From [a] to [b]; the rotation takes the short way round.
    public static func blend(_ a: Silhouette, _ b: Silhouette, _ t: Float) -> Silhouette {
        var dRot = b.rot - a.rot
        while dRot > .pi { dRot -= tau }
        while dRot < -.pi { dRot += tau }
        return Silhouette(
            (0 ..< samples).map { a.radii[$0] + (b.radii[$0] - a.radii[$0]) * t },
            rot: a.rot + dRot * t,
            cx: lerp(a.cx, b.cx, t),
            cy: lerp(a.cy, b.cy, t),
            sx: lerp(a.sx, b.sx, t),
            sy: lerp(a.sy, b.sy, t)
        )
    }

    /// Radius of [radii] towards [angle], between the two nearest samples.
    public static func radiusAt(_ radii: [Float], _ angle: Float) -> Float {
        let turns = (angle / tau).truncatingRemainder(dividingBy: 1)
        let t = (turns + 1).truncatingRemainder(dividingBy: 1) * Float(samples)
        let i = Int(t)
        return lerp(radii[i % samples], radii[(i + 1) % samples], t - Float(i))
    }

    /// |x/sx|^n + |y/sy|^n = 1: n = 2 is an ellipse, n near 4 a squircle.
    public static func superellipse(_ n: Float, sx: Float = 1, sy: Float = 1) -> [Float] {
        (0 ..< samples).map { i in pow(pow(abs(cosines[i] / sx), n) + pow(abs(sines[i] / sy), n), -1 / n) }
    }

    /// Radius of a union of discs (the origin inside it): the farthest ray exit.
    public static func unionOfCircles(_ circles: [(Float, Float, Float)]) -> [Float] {
        (0 ..< samples).map { i in
            circles.map { x, y, r -> Float in
                let b = cosines[i] * x + sines[i] * y
                let disc = b * b - (x * x + y * y - r * r)
                return disc < 0 ? 0 : b + sqrt(disc)
            }.max()!
        }
    }

    /// Any polygon to a radial profile, by casting rays from (cx, cy). Built once, never per frame.
    public static func fromPolygon(_ poly: [Pt], cx: Float = 0, cy: Float = 0) -> [Float] {
        (0 ..< samples).map { k in
            var best: Float = 0
            for i in poly.indices {
                let a = poly[i]
                let b = poly[(i + 1) % poly.count]
                let ex = b.x - a.x
                let ey = b.y - a.y
                let den = cosines[k] * ey - sines[k] * ex
                if abs(den) < 1e-9 { continue }
                let px = a.x - cx
                let py = a.y - cy
                let t = (px * ey - py * ex) / den
                let u = (px * sines[k] - py * cosines[k]) / den
                if t > best, u >= 0, u <= 1 { best = t }
            }
            return best
        }
    }

    /// The convex hull of two circles: a tapered bar, a capsule or a drop.
    public static func hullOfCircles(_ x1: Float, _ y1: Float, _ r1: Float, _ x2: Float, _ y2: Float, _ r2: Float, steps: Int = 96) -> [Pt] {
        let dist = max(hypot(x2 - x1, y2 - y1), 1e-6)
        let base = atan2(y2 - y1, x2 - x1)
        let spread = acos(min(max((r1 - r2) / dist, -1), 1))
        let half = steps / 2
        let big = (0 ... half).map { i -> Pt in
            let a = base + spread + (tau - 2 * spread) * Float(i) / Float(half)
            return Pt(x: x1 + cos(a) * r1, y: y1 + sin(a) * r1)
        }
        let small = (0 ... half).map { i -> Pt in
            let a = base - spread + 2 * spread * Float(i) / Float(half)
            return Pt(x: x2 + cos(a) * r2, y: y2 + sin(a) * r2)
        }
        return big + small
    }

    /// A regular polygon with rounded corners (radius [rc]), inscribed in [radius].
    public static func regularPolygon(_ sides: Int, _ radius: Float, _ rc: Float, rotationDeg: Float = 0) -> [Float] {
        let rot = rotationDeg * .pi / 180
        let verts = (0 ..< sides).map { i -> Pt in
            let a = rot + Float(i) * tau / Float(sides)
            return Pt(x: cos(a) * (radius - rc), y: sin(a) * (radius - rc))
        }
        return fromPolygon(rounded(verts, rc))
    }

    /// Scales [radii] so its widest point is [max]: every body then weighs the same to the eye.
    public static func normalized(_ radii: [Float], _ max: Float = 1) -> [Float] {
        let peak = radii.max() ?? 0
        return peak <= 0 ? radii : radii.map { $0 * max / peak }
    }

    /// Minkowski sum with a disc: each corner becomes an arc of radius [rc]. Clockwise on screen.
    private static func rounded(_ verts: [Pt], _ rc: Float, arcSteps: Int = 10) -> [Pt] {
        func normal(_ a: Pt, _ b: Pt) -> Float {
            let len = max(hypot(b.x - a.x, b.y - a.y), 1e-6)
            return atan2(-(b.x - a.x) / len, (b.y - a.y) / len)
        }
        var out: [Pt] = []
        for i in verts.indices {
            let prev = verts[(i - 1 + verts.count) % verts.count]
            let cur = verts[i]
            let next = verts[(i + 1) % verts.count]
            let a0 = normal(prev, cur)
            var d = normal(cur, next) - a0
            while d > .pi { d -= tau }
            while d < -.pi { d += tau }
            for k in 0 ... arcSteps {
                let a = a0 + d * Float(k) / Float(arcSteps)
                out.append(Pt(x: cur.x + cos(a) * rc, y: cur.y + sin(a) * rc))
            }
        }
        return out
    }
}

func lerp(_ a: Float, _ b: Float, _ t: Float) -> Float {
    a + (b - a) * t
}

func clamp01(_ v: Float) -> Float {
    min(max(v, 0), 1)
}

func easeOutCubic(_ t: Float) -> Float {
    1 - pow(1 - t, 3)
}

func easeInOutCubic(_ t: Float) -> Float {
    t < 0.5 ? 4 * t * t * t : 1 - pow(-2 * t + 2, 3) / 2
}

func easeOutQuint(_ t: Float) -> Float {
    1 - pow(1 - t, 5)
}

/// mulberry32: a tiny fixed generator, the same sequence everywhere, for tables built once.
struct Mulberry {
    private var a: UInt32

    init(_ seed: UInt32) {
        a = seed
    }

    mutating func next() -> Double {
        a = a &+ 0x6D2B_79F5
        var t = (a ^ (a >> 15)) &* (1 | a)
        t = (t &+ ((t ^ (t >> 7)) &* (61 | t))) ^ t
        return Double(t ^ (t >> 14)) / 4_294_967_296.0
    }
}

/// SplitMix64: tiny, fast, and fixed by its definition (Steele, Lea and Flood, OOPSLA 2014), so a seed gives the same
/// WatchGPT on every device, on Android and on the Apple Watch alike.
struct Rng {
    private var state: UInt64

    init(_ seed: Int64) {
        state = UInt64(bitPattern: seed)
    }

    mutating func nextLong() -> UInt64 {
        state = state &+ 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    /// 0 until [bound].
    mutating func nextInt(_ bound: Int) -> Int {
        Int((nextLong() >> 1) % UInt64(bound))
    }

    /// 0...1 with 24 bits of precision.
    mutating func nextFloat() -> Float {
        Float(nextLong() >> 40) / Float(1 << 24)
    }
}
