import SwiftUI

/// The licence of the code WatchGPT's animation comes from, as the MIT licence asks.
struct LicencesView: View {
    private let text: String = {
        guard let url = Bundle.main.url(forResource: "bloub", withExtension: "txt"), let text = try? String(contentsOf: url, encoding: .utf8) else {
            return "bloub (github.com/jeremy-prt/bloub), MIT License, Copyright (c) 2026 Jérémy Perret."
        }
        return text
    }()

    var body: some View {
        ScrollView {
            Text(text)
                .font(.footnote)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle("Licences")
    }
}
