import Foundation

/// What WatchGPT is doing: the conversation's [act] since [actAt], and a [reaction] since [reactionAt] (seconds).
public struct FaceScene: Equatable, Sendable {
    public var act: Act
    public var actAt: Double = 0
    public var reaction: Reaction?
    public var reactionAt: Double = 0

    public init(act: Act, actAt: Double = 0, reaction: Reaction? = nil, reactionAt: Double = 0) {
        self.act = act
        self.actAt = actAt
        self.reaction = reaction
        self.reactionAt = reactionAt
    }
}

/// An eye or the mouth, cut out of the body, and how far it shows. It is drawn in its own frame (a pill [w] by [h]
/// centred on the origin, or the [band] of a mouth or a squinting eye), then mapped to body units by the matrix
/// x' = a x + c y + e, y' = b x + d y + f, so a renderer draws one rounded rect or curve under a transform.
public struct Feature: Sendable {
    public let w: Float
    public let h: Float
    public let band: Mouth?
    public let a: Float
    public let b: Float
    public let c: Float
    public let d: Float
    public let e: Float
    public let f: Float
    public let alpha: Float
}

/// One picture of WatchGPT in body units: 1 is the radius of the ball at rest, (0, 0) its centre, y points down.
/// Renderers draw, in order: [arcs] behind, [dots] if [dotsBehind], the [body] with its [features] and the [badge] gap
/// cut out, [dots] in front, the [badge], [arcs] in front.
public struct Frame: Sendable {
    /// [Silhouette.samples] outline points, x and y interleaved; draw as a smooth closed curve.
    public let body: [Float]
    public let features: [Feature]
    public let dots: [Dot]
    public let dotsBehind: Bool
    public let arcs: [Arc]
    public let badge: Badge?
}

/// WatchGPT's animation, a port of bloub's engine by Jérémy Perret (MIT, src/bot/engine.ts) through the Android app:
/// no clock inside, [sample] is a pure function of the time it is given and of the state changes set before. A change
/// blends from what was on screen, frozen if it lands in the middle of another blend. With [still] (Reduce Motion)
/// there is no drift, breathing or blinking, each state shows its most readable moment and changes are instant.
final class Engine {
    private let genes: Genes
    private let still: Bool
    private var cur = State.idle
    private var prev: State?
    private var frozen: Pose?
    private var tCur = 0.0
    private var tPrev = 0.0
    private var blinkAt = -10.0

    init(genes: Genes, still: Bool = false) {
        self.genes = genes
        self.still = still
    }

    var state: State { cur }

    /// Starts over on [state] with nothing to blend from.
    func reset(_ state: State, now: Double) {
        cur = state
        prev = nil
        frozen = nil
        tCur = now
        tPrev = now
        blinkAt = -10
    }

    /// Switches to [state] at [now]; a change mid-blend starts from the blended picture, so nothing jumps.
    func set(_ state: State, now: Double) {
        if state == cur { return }
        let midBlend = prev != nil && now - tCur < Double(cur.morph)
        frozen = midBlend ? composed(now, 0) : nil
        prev = cur
        tPrev = tCur
        cur = state
        tCur = now
        if state.blinkIn { blinkAt = now }
    }

    private func posed(_ state: State, _ since: Double, _ level: Float) -> Pose {
        state.pose(still ? state.readable : Float(max(0, since)), Cue(body: genes.body, rest: genes.face, level: level))
    }

    /// The blended pose at [now], before the life at rest.
    private func composed(_ now: Double, _ level: Float) -> Pose {
        let since = now - tCur
        let pose = posed(cur, since, level)
        if still || since >= Double(cur.morph) { return pose }
        guard let from = frozen ?? prev.map({ posed($0, now - tPrev, level) }) else { return pose }
        return Pose.blend(from, pose, easeOutQuint(clamp01(Float(since) / cur.morph)))
    }

    /// WatchGPT at [now], with the voice [level] (0...1) for the mouth and the listening lean.
    func sample(_ now: Double, level: Float = 0) -> Frame {
        let pose = composed(now, clamp01(level))
        let alive = pose.faceAlpha > 0.01
        let life = Face.life(now, wander: alive ? 1 : 0, blink: alive, still: still)
        let gaze = Gaze(yaw: pose.face.gaze.yaw + life.dYaw, pitch: pose.face.gaze.pitch + life.dPitch, roll: pose.face.gaze.roll + life.dRoll)
        // A blink forced by the state change, on top of the schedule.
        let forced = clamp01(Float((now - blinkAt) / 0.2))
        let lid = still ? 1 : min(life.lid, forced < 1 ? abs(forced * 2 - 1) : 1)
        let offX = pose.offX + life.driftX
        let offY = pose.offY + life.driftY
        var sil = pose.sil
        sil.cx += offX
        sil.cy += offY
        sil.sy *= life.breath

        // Features live on a unit sphere; on other outlines they move in or out with the radius in their direction.
        func fit(_ x: Float, _ y: Float) -> Float {
            Silhouette.radiusAt(pose.sil.radii, atan2(y, x) - pose.sil.rot)
        }

        var features: [Feature] = []
        if alive {
            let (inner, outer, mouth) = Face.spots(gaze, split: pose.face.split, drop: pose.face.drop)
            for (spot, eye) in [(inner, pose.face.eyes.0), (outer, pose.face.eyes.1)] where spot.depth > 0.02 {
                let k = fit(spot.x, spot.y)
                let squash = Face.blinkScale(min(lid, eye.open))
                features.append(Face.feature(spot, tilt: eye.tilt, squashY: squash, x: spot.x * k + offX, y: spot.y * k + offY,
                                             w: eye.w, h: eye.h, band: Face.band(eye), alpha: pose.faceAlpha * Face.facing(spot.depth)))
            }
            if mouth.depth > 0.02 {
                let k = fit(mouth.x, mouth.y)
                let m = pose.face.mouth
                features.append(Face.feature(mouth, tilt: m.tilt, squashY: 1, x: mouth.x * k + offX, y: mouth.y * k + offY,
                                             w: m.w, h: m.thick, band: m, alpha: pose.faceAlpha * Face.facing(mouth.depth)))
            }
        }

        let badge = pose.badge.map { b -> Badge in
            let a = Decor.badgeAngle * .pi / 180
            let k = fit(cos(a), sin(a))
            return Badge(x: cos(a) * k + offX, y: sin(a) * k + offY, r: b.r, gap: Decor.badgeGap, heart: b.heart)
        }

        return Frame(
            body: sil.points(),
            features: features,
            dots: pose.dots.filter { $0.opacity > 0.01 && $0.r > 0.0005 }.map {
                Dot(x: $0.x + offX, y: $0.y + offY, r: $0.r, opacity: $0.opacity, depth: $0.depth, shape: $0.shape, rot: $0.rot)
            },
            dotsBehind: pose.dotsBehind,
            arcs: pose.arcs.filter { $0.opacity > 0.01 }.map { Decor.arc($0.seed, t: $0.t, opacity: $0.opacity, accentHue: genes.color.hue) },
            badge: badge
        )
    }
}

/// Turns scenes into engine states: the reaction while it plays, else the conversation. Each change is set at the
/// moment it really happened, so the picture doesn't depend on the frame rate.
public final class Director {
    private let engine: Engine
    private var started = false

    public init(genes: Genes, still: Bool = false) {
        engine = Engine(genes: genes, still: still)
    }

    /// The frame at [t]; with [blend] off (always-on display, where time doesn't move) the state as if it had always been on.
    public func frame(_ scene: FaceScene, level: Float, t: Double, blend: Bool = true) -> Frame {
        let (state, since) = Self.target(scene, t)
        if !blend || !started {
            engine.reset(state, now: since)
            started = true
        } else {
            engine.set(state, now: since)
        }
        return engine.sample(t, level: level)
    }

    /// What plays at [t] and since when.
    static func target(_ scene: FaceScene, _ t: Double) -> (State, Double) {
        let act = State.of(scene.act)
        guard let reaction = scene.reaction, let mood = State.of(reaction.mood) else { return (act, scene.actAt) }
        let end = scene.reactionAt + mood.hold
        if t < scene.reactionAt { return (act, scene.actAt) }
        if t < end { return (mood, scene.reactionAt) }
        return (act, max(scene.actAt, end))
    }
}
