import Foundation

/// Head orientation in degrees: [yaw] > 0 looks right, [pitch] > 0 looks up, [roll] tilts the head. The eyes and the
/// mouth are painted on a sphere turned this way, so looking aside squeezes and tilts them by itself.
public struct Gaze: Equatable, Sendable {
    public var yaw: Float
    public var pitch: Float
    public var roll: Float
}

/// One eye in body units: a pill [w] across and [h] tall, [open] 1...0 lid, own [tilt] in degrees. With [arc] above
/// one half it is a bent line instead ("^", [h] thick): squinting with joy.
public struct Eye: Equatable, Sendable {
    public var w: Float
    public var h: Float
    public var open: Float = 1
    public var tilt: Float = 0
    public var arc: Float = 0
}

/// The mouth, a band on the sphere below the eyes, in body units: [w] corner to corner, [curve] > 0 smiles and < 0
/// frowns, [open] how far it opens (a grin downwards, an "o" both ways, by [up]), [thick] the line when closed.
public struct Mouth: Equatable, Sendable {
    public var w: Float
    public var curve: Float
    public var open: Float = 0
    public var thick: Float = 0.05
    public var up: Float = 0
    public var tilt: Float = 0
}

/// A whole face: where the head looks, how far apart the eyes sit (half, in degrees), both eyes, the mouth and how far
/// below the eyes it sits (degrees).
public struct Expression: Equatable, Sendable {
    public var gaze: Gaze
    public var split: Float
    public var eyes: (Eye, Eye)
    public var mouth: Mouth
    public var drop: Float = 24

    public static func == (a: Expression, b: Expression) -> Bool {
        a.gaze == b.gaze && a.split == b.split && a.eyes.0 == b.eyes.0 && a.eyes.1 == b.eyes.1 && a.mouth == b.mouth && a.drop == b.drop
    }

    static func blend(_ a: Expression, _ b: Expression, _ t: Float) -> Expression {
        Expression(
            gaze: Gaze(yaw: lerp(a.gaze.yaw, b.gaze.yaw, t), pitch: lerp(a.gaze.pitch, b.gaze.pitch, t), roll: lerp(a.gaze.roll, b.gaze.roll, t)),
            split: lerp(a.split, b.split, t),
            eyes: (blend(a.eyes.0, b.eyes.0, t), blend(a.eyes.1, b.eyes.1, t)),
            mouth: blend(a.mouth, b.mouth, t),
            drop: lerp(a.drop, b.drop, t)
        )
    }

    private static func blend(_ a: Eye, _ b: Eye, _ t: Float) -> Eye {
        Eye(w: lerp(a.w, b.w, t), h: lerp(a.h, b.h, t), open: lerp(a.open, b.open, t), tilt: lerp(a.tilt, b.tilt, t), arc: lerp(a.arc, b.arc, t))
    }

    private static func blend(_ a: Mouth, _ b: Mouth, _ t: Float) -> Mouth {
        Mouth(w: lerp(a.w, b.w, t), curve: lerp(a.curve, b.curve, t), open: lerp(a.open, b.open, t), thick: lerp(a.thick, b.thick, t), up: lerp(a.up, b.up, t), tilt: lerp(a.tilt, b.tilt, t))
    }
}

/// A spot on the sphere seen from the front: its centre on the unit sphere, the tangent frame projected to the screen
/// (columns (a, b) and (c, d)), and [depth] > 0 while it faces the viewer.
struct Spot {
    let x: Float
    let y: Float
    let a: Float
    let b: Float
    let c: Float
    let d: Float
    let depth: Float
}

/// The life at rest, as offsets to add to the pose: head drift, the lid (1 open, 0 shut), body float and breath.
struct Life {
    let dYaw: Float
    let dPitch: Float
    let dRoll: Float
    let lid: Float
    let driftX: Float
    let driftY: Float
    let breath: Float
}

/// Sphere geometry, liveliness and blinking, ported from bloub (MIT, src/bot/face.ts). The mouth is the Android app's
/// own. All pure functions of time, so pausing or jumping to any time gives the same picture.
enum Face {
    private static let deg = Float.pi / 180
    private static let tau = 2 * Float.pi

    /// Head frame, then the eyes (inner, outer) at ±[split] degrees and the mouth [drop] degrees below them.
    static func spots(_ gaze: Gaze, split: Float, drop: Float) -> (Spot, Spot, Spot) {
        var f: [Float] = [0, 0, 1]
        var right: [Float] = [1, 0, 0]
        var down: [Float] = [0, 1, 0]
        (f, right) = spin(f, right, gaze.yaw * deg)
        (down, f) = spin(down, f, gaze.pitch * deg)
        (right, down) = spin(right, down, gaze.roll * deg)
        func eye(_ side: Float) -> Spot {
            let (ef, er) = spin(f, right, split * side * deg)
            return Spot(x: ef[0], y: ef[1], a: er[0], b: er[1], c: down[0], d: down[1], depth: ef[2])
        }
        // Tipping the forward vector towards "down" slides the mouth under the eyes; its across stays "right".
        let (mf, md) = spin(f, down, drop * deg)
        return (eye(-1), eye(1), Spot(x: mf[0], y: mf[1], a: right[0], b: right[1], c: md[0], d: md[1], depth: mf[2]))
    }

    /// Turns two vectors of an orthonormal frame by [angle] within their plane.
    private static func spin(_ u: [Float], _ v: [Float], _ angle: Float) -> ([Float], [Float]) {
        let c = cos(angle)
        let s = sin(angle)
        return ((0 ..< 3).map { u[$0] * c + v[$0] * s }, (0 ..< 3).map { v[$0] * c - u[$0] * s })
    }

    /// A seamless periodic wobble: never repeats to the eye when periods are coprime.
    static func loopNoise(_ t: Double, _ period: Double, _ seed: Double) -> Float {
        let p = t / period * Double(tau)
        return Float(0.55 * sin(p + seed) + 0.3 * sin(2 * p + seed * 1.7 + 1.1) + 0.15 * sin(3 * p + seed * 2.3 + 2.4))
    }

    /// Head drift, blinks, float and breath at [t]; [wander] scales the drift, 0 when there are no eyes.
    static func life(_ t: Double, wander: Float, blink: Bool, still: Bool) -> Life {
        if still { return Life(dYaw: 0, dPitch: 0, dRoll: 0, lid: 1, driftX: 0, driftY: 0, breath: 1) }
        return Life(
            dYaw: (loopNoise(t, 11.3, 0.4) * 5.5 + loopNoise(t, 3.7, 2.1) * 1.6) * wander,
            dPitch: (loopNoise(t, 9.1, 1.3) * 4.2 + loopNoise(t, 4.3, 0.7) * 1.3) * wander,
            dRoll: loopNoise(t, 13.7, 3.2) * 2.2 * wander,
            lid: blink ? lid(t) : 1,
            driftX: loopNoise(t, 7.9, 1.9) * 0.006,
            driftY: loopNoise(t, 5.3, 0.3) * 0.007,
            breath: 1 + Float(sin(t / 3.4 * Double(tau))) * 0.005
        )
    }

    /// A blink squashes the eye on screen (height down to 6%), not along the pill's own axis.
    static func blinkScale(_ lid: Float) -> Float {
        0.06 + 0.94 * clamp01(lid)
    }

    private static let blinkSeconds = 0.18
    private static let blinkTableSeconds = 900.0

    /// Blink times drawn once: 1.9 to 4.6 s apart, sometimes a double blink. Fixed, so time alone decides.
    private static let blinks: [Double] = {
        var rng = Mulberry(0x0B11_4C5)
        var out: [Double] = []
        var t = 1.4
        while t < blinkTableSeconds {
            out.append(t)
            t += 1.9 + rng.next() * 2.7
            if rng.next() < 0.18 {
                out.append(t)
                t += 0.24
            }
        }
        return out
    }()

    private static func lid(_ time: Double) -> Float {
        // The table covers 15 minutes; after that it starts over, a jump nobody sees mid-blink.
        let t = time.truncatingRemainder(dividingBy: blinkTableSeconds)
        var lo = 0
        var hi = blinks.count - 1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if blinks[mid] <= t { lo = mid + 1 } else { hi = mid - 1 }
        }
        if hi < 0 { return 1 }
        let k = Float((t - blinks[hi]) / blinkSeconds)
        if k > 1 { return 1 }
        // Shuts fast, opens a little slower.
        return k < 0.45 ? 1 - k / 0.45 : (k - 0.45) / 0.55
    }

    /// An eye as a band when it squints with joy ([Eye.arc] over one half): a bent "^" line; else nil, a pill.
    static func band(_ e: Eye) -> Mouth? {
        e.arc >= 0.5 ? Mouth(w: e.w, curve: -e.arc, thick: e.h) : nil
    }

    /// A feature on the sphere: its own [tilt] (degrees) first, then the spot's tangent frame, then [squashY] on screen
    /// around its centre (a blink), then moved to ([x], [y]).
    static func feature(_ spot: Spot, tilt: Float, squashY: Float, x: Float, y: Float, w: Float, h: Float, band: Mouth?, alpha: Float) -> Feature {
        let phi = tilt * deg
        let cp = cos(phi)
        let sp = sin(phi)
        return Feature(
            w: w, h: h, band: band,
            a: spot.a * cp + spot.c * sp,
            b: (spot.b * cp + spot.d * sp) * squashY,
            c: -spot.a * sp + spot.c * cp,
            d: (-spot.b * sp + spot.d * cp) * squashY,
            e: x, f: y, alpha: alpha
        )
    }

    /// How much of a spot shows: fades out as it turns away over the edge of the sphere.
    static func facing(_ depth: Float) -> Float {
        clamp01(depth / 0.12)
    }
}
