import Foundation

/// A pose declared by a state, in body units: the outline, an offset for body and face, the face and how visible it
/// is, and the decor. The engine only blends poses and adds the life at rest; states never read a clock.
struct Pose {
    var sil: Silhouette
    var face: Expression
    var offX: Float = 0
    var offY: Float = 0
    var faceAlpha: Float = 1
    var dots: [Dot] = []
    var dotsBehind = false
    var arcs: [ArcSpec] = []
    /// The badge, placed on the outline at [Decor.badgeAngle].
    var badge: BadgeSpec?

    /// From [a] to [b]: shapes and faces morph, decor cross-fades, the badge switches half-way.
    static func blend(_ a: Pose, _ b: Pose, _ t: Float) -> Pose {
        let out = 1 - t
        func fade(_ d: Dot, _ k: Float) -> Dot {
            Dot(x: d.x, y: d.y, r: d.r, opacity: d.opacity * k, depth: d.depth, shape: d.shape, rot: d.rot)
        }
        return Pose(
            sil: Silhouette.blend(a.sil, b.sil, t),
            face: Expression.blend(a.face, b.face, t),
            offX: lerp(a.offX, b.offX, t),
            offY: lerp(a.offY, b.offY, t),
            faceAlpha: lerp(a.faceAlpha, b.faceAlpha, t),
            dots: a.dots.map { fade($0, out) } + b.dots.map { fade($0, t) },
            dotsBehind: t < 0.5 ? a.dotsBehind : b.dotsBehind,
            arcs: a.arcs.map { ArcSpec(seed: $0.seed, t: $0.t, opacity: $0.opacity * out) } + b.arcs.map { ArcSpec(seed: $0.seed, t: $0.t, opacity: $0.opacity * t) },
            badge: t < 0.5 ? a.badge : b.badge
        )
    }
}

struct BadgeSpec {
    let r: Float
    let heart: Bool
}

/// What a state knows besides its own time: this WatchGPT's resting body and face, and the voice level (0...1).
struct Cue {
    let body: [Float]
    let rest: Expression
    let level: Float
}

/// Where the conversation is: what WatchGPT does when no reaction plays.
public enum Act: Sendable { case rest, awake, connect, listen, think, speak, error }

/// How WatchGPT reacts to what a tool did; the user sets none of this.
public enum Mood: String, CaseIterable, Sendable {
    case neutral, happy, greet, excited, laugh, love, curious, thinking, surprised, sad, sleepy, confused, proud, oops
    /// Music or a video starts: the play triangle.
    case play
}

public struct Reaction: Equatable, Sendable {
    public let mood: Mood
    /// 0...1: a small smile at 0.3, jumping around at 1.
    public let intensity: Float

    public init(_ mood: Mood, intensity: Float = 0.6) {
        self.mood = mood
        self.intensity = intensity
    }
}

/// Everything WatchGPT can do. The first seven follow the conversation ([Act]); the rest are reactions ([Mood]) that
/// play for [hold] seconds and hand back to the conversation. The animations are bloub's (MIT, src/bot/states.ts);
/// sizes, faces and timings are the Android app's.
enum State: CaseIterable {
    case idle, awake, connect, listen, think, speak, error
    case greet, happy, proud, excited, laugh, love, curious, confused, surprised, sad, sleepy, play

    /// How long it plays as a reaction; conversation states last as long as the act.
    var hold: Double {
        switch self {
        case .idle, .awake, .connect, .listen, .think, .speak, .error: 0
        case .greet: 1.8
        case .happy: 2.4
        case .proud: 3.4
        case .excited, .laugh: 2.8
        case .love: 2.6
        case .curious: 2.2
        case .confused, .surprised, .play: 2.0
        case .sad: 3.0
        case .sleepy: 2.4
        }
    }

    /// How long the morph into it takes.
    var morph: Float {
        switch self {
        case .idle, .error: 0.45
        case .awake, .speak, .greet: 0.3
        case .connect, .listen, .sad, .sleepy, .love, .play: 0.5
        case .think, .excited, .curious, .confused: 0.4
        case .happy: 0.35
        case .proud: 0.6
        case .laugh, .surprised: 0.45
        }
    }

    /// Masks the change with a blink, as bloub does for every change of face.
    var blinkIn: Bool {
        switch self {
        case .idle, .speak, .error, .proud, .excited, .laugh, .surprised, .sleepy: false
        default: true
        }
    }

    /// When in its own time the state reads best: the still picture shown with animations off.
    var readable: Float {
        switch self {
        case .idle, .speak: 1
        case .awake: 0.5
        case .excited, .sleepy: 0.45
        case .connect, .love, .play: 0.9
        case .listen, .greet, .curious, .confused, .surprised: 0.8
        case .think: 1.1
        case .error: 0.75
        case .happy, .proud, .sad: 1.2
        case .laugh: 1.15
        }
    }

    static func of(_ act: Act) -> State {
        switch act {
        case .rest: .idle
        case .awake: .awake
        case .connect: .connect
        case .listen: .listen
        case .think: .think
        case .speak: .speak
        case .error: .error
        }
    }

    static func of(_ mood: Mood) -> State? {
        switch mood {
        case .neutral: nil
        case .happy: .happy
        case .greet: .greet
        case .excited: .excited
        case .laugh: .laugh
        case .love: .love
        case .curious: .curious
        case .thinking: .think
        case .surprised: .surprised
        case .sad: .sad
        case .sleepy: .sleepy
        case .confused: .confused
        case .proud: .proud
        case .oops: .error
        case .play: .play
        }
    }

    // swiftlint:disable:next cyclomatic_complexity function_body_length
    func pose(_ t: Float, _ cue: Cue) -> Pose {
        switch self {
        case .idle:
            return Pose(sil: Silhouette(cue.body), face: cue.rest)
        case .awake:
            // Half of the orbit's rings, quickly in and out: WatchGPT is up.
            let arcs = Decor.rings.prefix(3).enumerated().map { i, s in
                ArcSpec(seed: s, t: t, opacity: clamp01((t - Float(i) * 0.06) / 0.14) * clamp01((1.22 - t) / 0.34))
            }
            return Pose(sil: Silhouette(cue.body), face: cue.rest, arcs: arcs)
        case .connect:
            // The badge pops: 15 % over size at about 0.3 s, then settles.
            let p = clamp01(t / 0.45)
            let pop = 1 + (Decor.badgePop - 1) * sin(p * .pi) * (1 - p * 0.35)
            return Pose(sil: Silhouette(cue.body), face: Poses.connect, badge: BadgeSpec(r: Decor.badgeR * (p < 1 ? pop : 1), heart: false))
        case .listen:
            // Leans in with the user's voice.
            let l = cue.level
            var face = Poses.listen
            face.eyes.0.h *= 1 + 0.08 * l
            face.eyes.1.h *= 1 + 0.08 * l
            return Pose(sil: Silhouette(cue.body, sx: 1 + 0.02 * l, sy: 1 + 0.035 * l), face: face, offY: -0.02 * l)
        case .think:
            // The body becomes the middle dot, the other two come out of its sides.
            let mid = Poses.dotPulse(t, 1)
            let emerge = 0.3 + 0.7 * easeOutCubic(clamp01(t / 0.3))
            let dots = [0, 2].map { i -> Dot in
                let k = Poses.dotPulse(t, i)
                return Dot(x: Decor.dotX[i] * emerge, y: 0, r: Decor.dotR * (1 + (Decor.dotPeak - 1) * k), opacity: 0.55 + 0.45 * k)
            }
            return Pose(sil: .circle(Decor.dotR * (1 + (Decor.dotPeak - 1) * mid), cx: Decor.dotX[1]), face: cue.rest, faceAlpha: 0, dots: dots)
        case .speak:
            let l = cue.level
            return Pose(sil: Silhouette(cue.body, sy: 1 + 0.02 * l), face: Poses.speaking(cue.rest, l), offY: -0.012 * l)
        case .error:
            // The leaning "!" slides across and comes back, with a small buzz.
            let p = clamp01(t / 1.5)
            let travel = easeInOutCubic(p) * 0.8 - 0.09
            let back: Float = t > 1.6 ? clamp01((t - 1.6) / 0.4) : 0
            let x = travel * (1 - back) + 0.1 * back
            let buzz = sin(t * 2.5 * 2 * .pi) * 0.005
            let tilt = Poses.italic * .pi / 180
            return Pose(
                sil: Silhouette(Poses.barItalic, rot: tilt, cx: x, cy: -0.32 - buzz),
                face: cue.rest,
                faceAlpha: 0,
                dots: [Dot(x: x - sin(tilt) * 0.58, y: -0.32 + cos(tilt) * 0.58 + buzz * 2.8, r: 0.115, opacity: 1, shape: Poses.tear, rot: Poses.italic)]
            )
        case .greet:
            return Pose(sil: Silhouette(cue.body), face: Poses.greet)
        case .happy:
            // Two small hops.
            let hop: Float = t < 0.9 ? abs(sin(.pi * t / 0.45)) : 0
            return Pose(sil: Silhouette(cue.body, sx: 1 + 0.02 * hop, sy: 1 - 0.02 * hop), face: Poses.happy, offY: -0.05 * hop)
        case .proud:
            // The triangle tumbles round, rings come in one by one, then it relaxes into WatchGPT's own body.
            let ramp = easeInOutCubic(clamp01(t / 0.35))
            let rot = -2 * .pi * 1.1 * t * ramp
            let back = easeInOutCubic(clamp01((t - 1.6) / 0.9))
            let tri = Poses.spinningTriangle(rot)
            let radii = (0 ..< Silhouette.samples).map { lerp(tri.radii[$0], Silhouette.radiusAt(cue.body, Silhouette.angle($0) + rot), back) }
            let fade = clamp01(t / 0.8) * clamp01((3.6 - t) / 0.9)
            var face = Poses.proud
            face.gaze = Gaze(yaw: Poses.proud.gaze.yaw + sin(t * 6.5) * 60 * (1 - back), pitch: -4 + back * (Poses.proud.gaze.pitch + 4), roll: Poses.proud.gaze.roll)
            let arcs = Decor.rings.enumerated().map { i, s in ArcSpec(seed: s, t: t, opacity: fade * clamp01((t - Float(i) * 0.13) / 0.3)) }
            return Pose(sil: Silhouette(radii, rot: rot, cx: tri.cx * (1 - back), cy: tri.cy * (1 - back)), face: face, arcs: arcs)
        case .excited:
            // Collapses into a small core that swallows five sparks, then grows back.
            let collapse = 1 - 0.83 * easeOutQuint(clamp01(t / 0.7))
            let regrow = easeOutQuint(clamp01((t - 1.7) / 0.7))
            let k = collapse + (1 - collapse) * regrow
            return Pose(
                sil: Silhouette(cue.body.map { lerp(1, $0, regrow) * k }),
                face: Poses.excited,
                faceAlpha: clamp01((t - 1.85) / 0.4),
                dots: Decor.particles(t),
                dotsBehind: true
            )
        case .laugh:
            // Shrinks to a dot that wobbles while ribbons orbit it, then grows back laughing.
            let collapse = 1 - (1 - Poses.cometDot) * easeOutQuint(clamp01(t / 0.55))
            let regrow = easeOutQuint(clamp01((t - 1.85) / 0.6))
            let k = collapse + (1 - collapse) * regrow
            let fade = clamp01((t - 0.15) / 0.25) * clamp01((1.95 - t) / 0.3)
            return Pose(
                sil: Silhouette(cue.body.map { lerp(1, $0, regrow) * k }, cy: sin(clamp01(t / 1.7) * .pi) * 0.035),
                face: Poses.laugh,
                faceAlpha: clamp01((t - 2) / 0.35),
                arcs: Decor.comet.map { ArcSpec(seed: $0, t: t, opacity: fade) }
            )
        case .love:
            let p = clamp01(t / 0.45)
            let pop = 1 + (Decor.badgePop - 1) * sin(p * .pi) * (1 - p * 0.35)
            return Pose(sil: Silhouette(cue.body), face: Poses.love, badge: BadgeSpec(r: Decor.badgeR * 1.1 * (p < 1 ? pop : 1), heart: true))
        case .curious:
            return Pose(sil: Silhouette(Shapes.egg), face: Poses.curious)
        case .confused:
            return Pose(sil: Silhouette(Poses.hexagon), face: Poses.confused)
        case .surprised:
            return Pose(sil: Silhouette(Poses.barUpright, cy: Poses.barCY), face: cue.rest, faceAlpha: 0, dots: [Dot(x: 0, y: 0.52, r: 0.11, opacity: 1)])
        case .sad:
            return Pose(sil: Silhouette(cue.body, sy: 0.97), face: Poses.sad, offY: 0.03)
        case .sleepy:
            // WatchGPT shrinks to a dot bouncing gently.
            return Pose(sil: .circle(0.16, cy: 0.11 + sin(t * (2 * .pi / 0.6)) * 0.19), face: cue.rest, faceAlpha: 0)
        case .play:
            // A play triangle, and a swoosh sweeping over it from right to left.
            let fade = clamp01(t / 0.35) * clamp01((2.2 - t) / 0.5)
            let arcs = Decor.swoosh.map { seed -> ArcSpec in
                var moved = seed
                moved.cx = 0.45 - t * 0.42
                return ArcSpec(seed: moved, t: t, opacity: fade)
            }
            return Pose(sil: Poses.spinningTriangle(0), face: Poses.play, arcs: arcs)
        }
    }
}

enum Poses {
    /// The upright "!": a tapered bar (hull of two circles) over a dot.
    static let barCY: Float = -0.19
    static let barUpright = Silhouette.fromPolygon(Silhouette.hullOfCircles(0, -0.5, 0.13, 0, 0.13, 0.075), cx: 0, cy: barCY)

    /// The leaning "!": a plain capsule, with a tear-shaped dot.
    static let barItalic = Silhouette.fromPolygon(Silhouette.hullOfCircles(0, -0.25, 0.13, 0, 0.25, 0.13))
    static let tear: [Float] = Silhouette.hullOfCircles(0, 0, 0.115, 0, 0.17, 0.012, steps: 40).flatMap { [$0.x, $0.y] }
    static let italic: Float = 16

    static let triangle = Silhouette.regularPolygon(3, 1.1, 0.3, rotationDeg: -90)
    static let hexagon = Silhouette.regularPolygon(6, 1.02, 0.22, rotationDeg: 30)

    /// The triangle doesn't spin on the spot: its centre circles the middle, so it tumbles.
    private static let triOrbit: Float = 0.2

    static func spinningTriangle(_ rot: Float) -> Silhouette {
        Silhouette(triangle, rot: rot, cx: -triOrbit * sin(rot), cy: triOrbit * cos(rot))
    }

    /// Squinting "^ ^" eyes: [w] wide, [thick] lines, bent by [arc].
    private static func smiling(_ w: Float, _ thick: Float, _ arc: Float) -> (Eye, Eye) {
        (Eye(w: w, h: thick, arc: arc), Eye(w: w, h: thick, arc: arc))
    }

    static let greet = Expression(gaze: Gaze(yaw: -6, pitch: 6, roll: 7), split: 16.5, eyes: (Eye(w: 0.23, h: 0.44), Eye(w: 0.4, h: 0.08)), mouth: Mouth(w: 0.28, curve: 0.45, open: 0.04))
    static let happy = Expression(gaze: Gaze(yaw: 6, pitch: 12, roll: -4), split: 17, eyes: smiling(0.27, 0.075, 0.55), mouth: Mouth(w: 0.3, curve: 0.42, open: 0.07))
    static let laugh = Expression(gaze: Gaze(yaw: 4, pitch: 16, roll: 0), split: 18, eyes: smiling(0.3, 0.085, 0.7), mouth: Mouth(w: 0.34, curve: 0.4, open: 0.15))
    static let excited = Expression(gaze: Gaze(yaw: 6, pitch: -8, roll: 0), split: 18.5, eyes: pair(0.33, 0.48, tilt: -8), mouth: Mouth(w: 0.3, curve: 0.4, open: 0.12))
    static let proud = Expression(gaze: Gaze(yaw: 8, pitch: 18, roll: -6), split: 17, eyes: smiling(0.26, 0.075, 0.55), mouth: Mouth(w: 0.26, curve: 0.5, open: 0.03))
    static let love = Expression(gaze: Gaze(yaw: 8, pitch: 10, roll: -6), split: 17, eyes: smiling(0.25, 0.075, 0.5), mouth: Mouth(w: 0.24, curve: 0.45, open: 0.03))
    static let curious = Expression(gaze: Gaze(yaw: 16, pitch: 20, roll: -16), split: 12, eyes: pair(0.17, 0.38), mouth: Mouth(w: 0.09, curve: 0, open: 0.06, thick: 0.04, up: 0.5))
    static let confused = Expression(gaze: Gaze(yaw: -12, pitch: 8, roll: 8), split: 14, eyes: (Eye(w: 0.19, h: 0.42, tilt: -16), Eye(w: 0.26, h: 0.16, tilt: 12)), mouth: Mouth(w: 0.18, curve: -0.08, thick: 0.045, tilt: 10))
    static let sad = Expression(gaze: Gaze(yaw: 3, pitch: -12, roll: 0), split: 16, eyes: pair(0.21, 0.38, tilt: -26), mouth: Mouth(w: 0.2, curve: -0.4))
    static let listen = Expression(gaze: Gaze(yaw: 4, pitch: -12, roll: 8), split: 18, eyes: pair(0.3, 0.6), mouth: Mouth(w: 0.12, curve: 0.15, thick: 0.045))
    static let connect = Expression(gaze: Gaze(yaw: -18, pitch: -6, roll: -10), split: 18, eyes: pair(0.42, 0.42), mouth: Mouth(w: 0.1, curve: 0, open: 0.05, thick: 0.045, up: 0.5))
    static let play = Expression(gaze: Gaze(yaw: 12, pitch: -8, roll: -6), split: 15, eyes: pair(0.18, 0.34), mouth: Mouth(w: 0.2, curve: 0.4, open: 0.02))

    static func speaking(_ rest: Expression, _ level: Float) -> Expression {
        var face = rest
        face.mouth = Mouth(w: rest.mouth.w * 1.05, curve: max(rest.mouth.curve, 0.2) * 0.7, open: 0.03 + 0.14 * level, thick: rest.mouth.thick, up: 0.2, tilt: rest.mouth.tilt)
        return face
    }

    /// A pulse running through the three dots from left to right.
    static func dotPulse(_ t: Float, _ index: Int) -> Float {
        let p = (((t - Float(index) * 0.5) / 1.5).truncatingRemainder(dividingBy: 1) + 1).truncatingRemainder(dividingBy: 1)
        let k: Float = p < 0.5 ? 0.5 - 0.5 * cos(p * 2 * .pi) : 0
        return clamp01(k * 2)
    }

    /// How small the comet's dot gets.
    static let cometDot: Float = 0.13
}
