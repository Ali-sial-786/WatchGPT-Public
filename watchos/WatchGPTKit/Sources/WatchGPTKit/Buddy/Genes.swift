import Foundation

/// What makes one user's WatchGPT theirs: body shape, colour and resting face, all from one seed. The seed comes from a
/// hash of the user's ChatGPT account id, so the same account gets the same WatchGPT on Android and on the Apple Watch;
/// the id itself never leaves the watch. The draws are in the Android app's fixed order: changing them changes every
/// existing WatchGPT.
public struct Genes: Equatable, Sendable {
    public let seed: Int64
    public let shape: BodyShape
    public let color: Tint
    public let rest: RestFace
    /// The resting face: [rest] nudged a little by the seed.
    public let face: Expression

    /// The resting outline, radii at [Silhouette.samples] angles.
    public var body: [Float] { shape.radii }

    /// The body's colour, 0xRRGGBB.
    public var ink: UInt32 { color.body }

    /// A stronger shade of its hue, for the badge: 0xRRGGBB.
    public var accent: UInt32 { Decor.hsl(color.hue, 0.8, 0.6) }

    /// A WatchGPT for [userId] (the ChatGPT account id): the same id always gives the same WatchGPT.
    public static func forUser(_ userId: String) -> Genes {
        of(seedFor(userId))
    }

    /// SHA-256 of a fixed prefix and the id, first 8 bytes: stable, and the id can't be read back.
    public static func seedFor(_ userId: String) -> Int64 {
        let digest = SHA256.hash(Array("watch-ai buddy v1|\(userId)".utf8))
        return Int64(bitPattern: digest.prefix(8).reduce(UInt64(0)) { $0 << 8 | UInt64($1) })
    }

    public static func of(_ seed: Int64) -> Genes {
        var rng = Rng(seed)
        let shape = BodyShape.allCases[rng.nextInt(BodyShape.allCases.count)]
        let color = Tint.allCases[rng.nextInt(Tint.allCases.count)]
        let rest = RestFace.allCases[rng.nextInt(RestFace.allCases.count)]
        let base = rest.face
        func around(_ span: Float) -> Float { (rng.nextFloat() * 2 - 1) * span }
        let gaze = Gaze(yaw: base.gaze.yaw + around(5), pitch: base.gaze.pitch + around(4), roll: base.gaze.roll + around(4))
        let eyeScale = 1 + around(0.08)
        var face = base
        face.gaze = gaze
        face.split = base.split + around(1)
        face.eyes.0.w *= eyeScale
        face.eyes.0.h *= eyeScale
        face.eyes.1.w *= eyeScale
        face.eyes.1.h *= eyeScale
        face.mouth.w = base.mouth.w * (1 + around(0.1))
        return Genes(seed: seed, shape: shape, color: color, rest: rest, face: face)
    }
}

/// Resting bodies, built from simple maths, each scaled so they weigh about the same to the eye.
public enum BodyShape: CaseIterable, Sendable {
    case round, pebble, squircle, egg, gumdrop, bun, cloud, tall

    var radii: [Float] {
        switch self {
        case .round: Shapes.round
        case .pebble: Shapes.pebble
        case .squircle: Shapes.squircle
        case .egg: Shapes.egg
        case .gumdrop: Shapes.gumdrop
        case .bun: Shapes.bun
        case .cloud: Shapes.cloud
        case .tall: Shapes.tall
        }
    }
}

enum Shapes {
    private static func each(_ f: (Float) -> Float) -> [Float] {
        (0 ..< Silhouette.samples).map { f(Silhouette.angle($0)) }
    }

    static let round = [Float](repeating: 1, count: Silhouette.samples)

    /// A circle with two low, soft bumps: uneven but smooth.
    static let pebble = Silhouette.normalized(each { a in 1 + 0.07 * cos(2 * a + 0.9) + 0.03 * cos(3 * a + 1.4) }, 1.02)

    static let squircle = Silhouette.normalized(Silhouette.superellipse(3.6), 1.1)

    /// Wider at the bottom (y points down).
    static let egg = Silhouette.normalized(
        Silhouette.fromPolygon((0 ..< 96).map { i in
            let a = Float(i) * 2 * .pi / 96
            return Pt(x: 0.84 * cos(a) * (1 + 0.13 * sin(a)), y: sin(a))
        }),
        1.04
    )

    /// A dome over a flat, soft bottom.
    static let gumdrop: [Float] = {
        let flat = Silhouette.superellipse(3.2)
        return Silhouette.normalized(each { a in sin(a) > 0 ? lerp(1, Silhouette.radiusAt(flat, a), sin(a)) : 1 }, 1.06)
    }()

    static let bun = Silhouette.normalized(Silhouette.superellipse(2.6, sx: 1.12, sy: 0.92), 1.1)

    /// Bumps: two lobes on top, wide at the bottom.
    static let cloud = Silhouette.normalized(
        Silhouette.unionOfCircles([(-0.42, 0.18, 0.56), (0.44, 0.2, 0.52), (0, 0.28, 0.62), (-0.22, -0.28, 0.5), (0.28, -0.22, 0.46)]),
        1.04
    )

    static let tall = Silhouette.normalized(Silhouette.superellipse(2.5, sx: 0.9, sy: 1.06), 1.06)
}

/// Body colours, picked to read on a black watch screen with black eyes cut out of them, and the hue their rings and
/// sparks take: one colour per WatchGPT, never a rainbow.
public enum Tint: CaseIterable, Sendable {
    case milk, coral, tangerine, sun, lime, mint, aqua, sky, peri, lilac, pink, sand

    /// 0xRRGGBB.
    public var body: UInt32 {
        switch self {
        case .milk: 0xF3F1EC
        case .coral: 0xFF8A73
        case .tangerine: 0xFFA64D
        case .sun: 0xFFD45C
        case .lime: 0xC2E66B
        case .mint: 0x6FE3B4
        case .aqua: 0x5ED6E0
        case .sky: 0x6CB8FF
        case .peri: 0x8F9BFF
        case .lilac: 0xC0A2FF
        case .pink: 0xFF8CC6
        case .sand: 0xE8C9A0
        }
    }

    /// Degrees.
    public var hue: Float {
        switch self {
        case .milk: 205
        case .coral: 9
        case .tangerine: 30
        case .sun: 44
        case .lime: 78
        case .mint: 155
        case .aqua: 185
        case .sky: 210
        case .peri: 233
        case .lilac: 262
        case .pink: 330
        case .sand: 35
        }
    }
}

/// The resting faces a WatchGPT is born with. Eyes are pills, the mouth a band below them.
public enum RestFace: CaseIterable, Sendable {
    case calm, bright, sly, soft, button, dreamy, perky, cheeky

    public var face: Expression {
        switch self {
        case .calm: Expression(gaze: Gaze(yaw: 12, pitch: 16, roll: -8), split: 16.5, eyes: pair(0.2, 0.4), mouth: Mouth(w: 0.22, curve: 0.32))
        case .bright: Expression(gaze: Gaze(yaw: 8, pitch: 12, roll: -4), split: 17.5, eyes: pair(0.22, 0.44), mouth: Mouth(w: 0.26, curve: 0.42, open: 0.03))
        case .sly: Expression(gaze: Gaze(yaw: 15, pitch: 14, roll: -10), split: 16, eyes: (Eye(w: 0.2, h: 0.4), Eye(w: 0.21, h: 0.3)), mouth: Mouth(w: 0.2, curve: 0.26, tilt: -10))
        case .soft: Expression(gaze: Gaze(yaw: 10, pitch: 18, roll: -6), split: 15.5, eyes: pair(0.18, 0.34), mouth: Mouth(w: 0.16, curve: 0.36, thick: 0.045))
        case .button: Expression(gaze: Gaze(yaw: 6, pitch: 14, roll: -5), split: 17, eyes: pair(0.25, 0.3), mouth: Mouth(w: 0.18, curve: 0.3))
        case .dreamy: Expression(gaze: Gaze(yaw: 8, pitch: 10, roll: -3), split: 16.5, eyes: pair(0.2, 0.4, open: 0.62), mouth: Mouth(w: 0.18, curve: 0.22))
        case .perky: Expression(gaze: Gaze(yaw: -6, pitch: 18, roll: 7), split: 16.5, eyes: pair(0.21, 0.42, tilt: 5), mouth: Mouth(w: 0.24, curve: 0.45))
        case .cheeky: Expression(gaze: Gaze(yaw: 14, pitch: 12, roll: -9), split: 17, eyes: pair(0.21, 0.38, tilt: -6), mouth: Mouth(w: 0.26, curve: 0.38, open: 0.06))
        }
    }
}

func pair(_ w: Float, _ h: Float, tilt: Float = 0, open: Float = 1) -> (Eye, Eye) {
    (Eye(w: w, h: h, open: open, tilt: tilt), Eye(w: w, h: h, open: open, tilt: -tilt))
}
