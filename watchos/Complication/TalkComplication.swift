import SwiftUI
import WidgetKit

/// A complication and Smart Stack widget: one tap from the watch face opens WatchGPT listening, like the Wear OS app's
/// Talk tile. It shows nothing personal, so it never needs refreshing.
@main
struct WatchGPTWidgets: WidgetBundle {
    var body: some Widget {
        TalkComplication()
    }
}

struct TalkComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "io.github.alisial786.watchgpt.talk", provider: TalkProvider()) { _ in
            TalkView()
        }
        .configurationDisplayName("Talk to WatchGPT")
        .description("Opens WatchGPT, listening.")
        .supportedFamilies([.accessoryCircular, .accessoryCorner, .accessoryRectangular, .accessoryInline])
    }
}

struct TalkEntry: TimelineEntry {
    let date: Date
}

struct TalkProvider: TimelineProvider {
    func placeholder(in _: Context) -> TalkEntry {
        TalkEntry(date: Date())
    }

    func getSnapshot(in _: Context, completion: @escaping (TalkEntry) -> Void) {
        completion(TalkEntry(date: Date()))
    }

    func getTimeline(in _: Context, completion: @escaping (Timeline<TalkEntry>) -> Void) {
        completion(Timeline(entries: [TalkEntry(date: Date())], policy: .never))
    }
}

struct TalkView: View {
    @Environment(\.widgetFamily) private var family

    var body: some View {
        content
            .widgetURL(URL(string: "watchgpt://talk"))
            .containerBackground(for: .widget) { Color.clear }
    }

    @ViewBuilder
    private var content: some View {
        switch family {
        case .accessoryRectangular:
            HStack(spacing: 8) {
                Mascot().frame(width: 34, height: 34)
                VStack(alignment: .leading) {
                    Text("WatchGPT").font(.headline)
                    Text("Tap to talk").font(.footnote)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        case .accessoryInline:
            Label("Talk to WatchGPT", systemImage: "bubble.left.fill")
        case .accessoryCorner:
            Mascot()
                .padding(4)
                .widgetLabel("WatchGPT")
        default:
            ZStack {
                AccessoryWidgetBackground()
                Mascot().padding(7)
            }
        }
    }
}

/// WatchGPT at rest, from plain shapes: a ball with two eyes and a smile cut out.
struct Mascot: View {
    var body: some View {
        GeometryReader { geo in
            let d = min(geo.size.width, geo.size.height)
            ZStack {
                Circle().fill(.white)
                HStack(spacing: d * 0.16) {
                    Capsule().frame(width: d * 0.1, height: d * 0.2)
                    Capsule().frame(width: d * 0.1, height: d * 0.2)
                }
                .offset(y: -d * 0.08)
                Smile()
                    .stroke(style: StrokeStyle(lineWidth: d * 0.05, lineCap: .round))
                    .frame(width: d * 0.24, height: d * 0.08)
                    .offset(y: d * 0.14)
            }
            .foregroundStyle(.black)
            .compositingGroup()
            .frame(width: d, height: d)
        }
        .aspectRatio(1, contentMode: .fit)
        .widgetAccentable()
    }
}

private struct Smile: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.minY))
        path.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.minY), control: CGPoint(x: rect.midX, y: rect.maxY * 2))
        return path
    }
}
