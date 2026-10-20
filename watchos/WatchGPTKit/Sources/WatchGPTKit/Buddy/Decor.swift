import Foundation

/// A circle in 3D seen edge-on: half axis [a], flattening [k], screen [tilt] (rad), turns per second, how much of the
/// turn is drawn, the hue offset from WatchGPT's accent (degrees) and how far the colour walks along the arc.
struct ArcSeed {
    let a: Float
    let k: Float
    let tilt: Float
    let speed: Float
    let phase: Float
    let sweep: Float
    let hue: Float
    let hueSpan: Float
    let width: Float
    var cx: Float = 0
    var cy: Float = 0
}

/// What a state asks for: an arc, its own clock and how visible it is.
struct ArcSpec {
    let seed: ArcSeed
    let t: Float
    let opacity: Float
}

/// An arc ready to draw, in body units: the runs in front of the body and the runs behind it (drawn first, so the body
/// hides them), with a colour gradient from ([x1], [y1]) to ([x2], [y2]).
public struct Arc: Sendable {
    public let front: [[Float]]
    public let back: [[Float]]
    public let width: Float
    public let opacity: Float
    public let x1: Float
    public let y1: Float
    public let x2: Float
    public let y2: Float
    /// 0xRRGGBB.
    public let colors: [UInt32]
}

/// A dot in body units. [depth] < 1 fades it towards the background; [shape], when set, replaces the disc with that
/// outline, turned by [rot] degrees (the tear under the leaning "!").
public struct Dot: Sendable {
    public let x: Float
    public let y: Float
    public let r: Float
    public let opacity: Float
    public var depth: Float = 1
    public var shape: [Float]?
    public var rot: Float = 0
}

/// The badge on the outline, and the gap cut around it.
public struct Badge: Sendable {
    public let x: Float
    public let y: Float
    public let r: Float
    public let gap: Float
    public let heart: Bool
}

/// Everything drawn around the body: orbit arcs, dots, particles and the badge. Ported from bloub (MIT,
/// src/bot/decor.ts) through the Android app, with its sizes, speeds and colours: the arcs stay within the user's hue.
enum Decor {
    private static let tau = 2 * Float.pi

    /// Projects [seed] at [t]: the circle lives in the plane of u (on screen) and v (into the depth); z splits it into
    /// front and back runs, which is what makes it read as an orbit.
    static func arc(_ seed: ArcSeed, t: Float, opacity: Float, accentHue: Float) -> Arc {
        let spin = seed.phase + t * seed.speed * tau
        let cu = cos(seed.tilt)
        let su = sin(seed.tilt)
        let kz = sqrt(max(0, 1 - seed.k * seed.k))
        let n = 32
        let span = seed.sweep * tau
        var front: [[Float]] = []
        var back: [[Float]] = []
        var run: [Float] = []
        var behind: Bool?
        func flush() {
            if run.count >= 4 { if behind == true { back.append(run) } else { front.append(run) } }
            run = []
        }
        for i in 0 ... n {
            let th = spin + Float(i) / Float(n) * span
            let ct = cos(th)
            let st = sin(th)
            let x = seed.a * (ct * cu - st * su * seed.k) + seed.cx
            let y = seed.a * (ct * su + st * cu * seed.k) + seed.cy
            let isBehind = seed.a * st * kz < 0
            if let behind, isBehind != behind { flush() }
            behind = isBehind
            run.append(x)
            run.append(y)
        }
        flush()
        let gx = cu * seed.a
        let gy = su * seed.a
        let h = accentHue + seed.hue
        return Arc(
            front: front, back: back, width: seed.width, opacity: opacity,
            x1: seed.cx - gx, y1: seed.cy - gy, x2: seed.cx + gx, y2: seed.cy + gy,
            colors: [hsl(h - seed.hueSpan / 2, ringS, ringL), hsl(h, ringS, ringL), hsl(h + seed.hueSpan / 2, ringS, ringL)]
        )
    }

    private static let ringS: Float = 0.62
    private static let ringL: Float = 0.64

    /// Six rings around the body: larger than it, seen nearly edge-on, one after another.
    static let rings: [ArcSeed] = {
        var r = Mulberry(0x0B1_7A1)
        return (0 ..< 6).map { i in
            let a = 1.16 + Float(r.next()) * 0.1
            let k = 0.06 + Float(r.next()) * 0.36
            let tilt = Float(i) / 6 * .pi + Float(r.next()) * 0.5
            let speed = 1.7 + Float(r.next()) * 0.5
            let phase = Float(r.next()) * tau
            let sweep = 0.55 + Float(r.next()) * 0.25
            let hue = (Float(i) - 2.5) * 8 + Float(r.next()) * 6
            let hueSpan = 16 + Float(r.next()) * 14
            let width = 0.05 + Float(r.next()) * 0.012
            return ArcSeed(a: a, k: k, tilt: tilt, speed: speed, phase: phase, sweep: sweep, hue: hue, hueSpan: hueSpan, width: width, cy: 0.08)
        }
    }()

    /// Nested arcs that sweep across the body, seen edge-on, so they bend like a hairpin.
    static let swoosh: [ArcSeed] = (0 ..< 4).map { i in
        let f = Float(i)
        return ArcSeed(a: 0.74 + f * 0.17, k: 0.05 + f * 0.02, tilt: -0.58 + f * 0.05, speed: 0.3, phase: 0.06 * f, sweep: 0.4, hue: -18 + f * 12, hueSpan: 30, width: 0.05, cy: -0.1)
    }

    /// The comet: the dot stays put and four tight ribbons orbit it.
    static let comet: [ArcSeed] = {
        var r = Mulberry(0x0C0_3E7)
        return (0 ..< 4).map { i in
            let d = Float(i) - 1.5
            let phase = -Float(i) * 0.045 + Float(r.next()) * 0.012
            let hue = d * 12 + Float(r.next()) * 6
            return ArcSeed(a: 0.8 * (1 + d * 0.03), k: 0.18 * (1 + d * 0.16), tilt: 30 * .pi / 180 + d * 0.035, speed: 0.55, phase: phase, sweep: 0.34, hue: hue, hueSpan: 24, width: 0.09)
        }
    }()

    /// The thinking dots: where they sit, their size and how big the pulse gets.
    static let dotX: [Float] = [-0.56, 0, 0.56]
    static let dotR: Float = 0.16
    static let dotPeak: Float = 1.25

    /// Where the badge sits on the outline (degrees, 0 = right, negative = up), its size and pop.
    static let badgeAngle: Float = -40
    static let badgeR: Float = 0.14
    static let badgePop: Float = 1.15
    static let badgeGap: Float = 0.05

    private struct Particle {
        let birth: Float
        let angle: Float
        let rho: Float
    }

    private static let particlesSeeds: [Particle] = {
        var r = Mulberry(0x0B3E_EF1)
        return (0 ..< 5).map { i in
            let angle = Float(r.next()) * tau
            let rho = 0.58 + Float(r.next()) * 0.18
            return Particle(birth: Float(i) * 0.2, angle: angle, rho: rho)
        }
    }()

    /// Five sparks spiralling in towards the shrunken body and passing behind it: a new one every 0.2 s, each lives 0.62 s.
    static func particles(_ t: Float) -> [Dot] {
        particlesSeeds.compactMap { p in
            let u = t - p.birth
            guard u >= 0, u <= 0.62 else { return nil }
            let rho = p.rho * pow(0.75, u * 10)
            let a = p.angle + u * 100 * .pi / 180
            return Dot(
                x: cos(a) * rho,
                y: sin(a) * rho,
                r: 0.05 + 0.036 * clamp01(u / 0.55),
                opacity: clamp01(u / 0.06) * clamp01((0.62 - u) / 0.08),
                depth: clamp01(1 - rho / 0.8)
            )
        }
    }

    /// HSL (degrees, 0...1, 0...1) to 0xRRGGBB.
    static func hsl(_ hue: Float, _ s: Float, _ l: Float) -> UInt32 {
        let h = (hue.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
        let c = (1 - abs(2 * l - 1)) * s
        let x = c * (1 - abs((h / 60).truncatingRemainder(dividingBy: 2) - 1))
        let m = l - c / 2
        let (r, g, b): (Float, Float, Float) = switch h {
        case ..<60: (c, x, 0)
        case ..<120: (x, c, 0)
        case ..<180: (0, c, x)
        case ..<240: (0, x, c)
        case ..<300: (x, 0, c)
        default: (c, 0, x)
        }
        func byte(_ v: Float) -> UInt32 { UInt32(min(max(((v + m) * 255).rounded(), 0), 255)) }
        return byte(r) << 16 | byte(g) << 8 | byte(b)
    }
}
