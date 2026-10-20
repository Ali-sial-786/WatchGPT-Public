import Foundation

/// Cuts a streamed answer into speakable pieces so speech can start after the first sentence instead of after the
/// whole answer. Very short pieces are held back to avoid choppy speech. The first piece may also end at a comma once
/// it is [firstPauseChars] long, so the voice starts a little sooner on long opening sentences.
public struct SentenceSplitter {
    private let minChars: Int
    private let maxChars: Int
    private let firstPauseChars: Int
    private var buffer: [Character] = []
    private var pieces = 0

    public init(minChars: Int = 12, maxChars: Int = 160, firstPauseChars: Int = 28) {
        self.minChars = minChars
        self.maxChars = maxChars
        self.firstPauseChars = firstPauseChars
    }

    public mutating func add(_ delta: String) -> [String] {
        buffer.append(contentsOf: delta)
        var out: [String] = []
        while let cut = nextCut() {
            let piece = String(buffer[..<cut]).trimmingCharacters(in: .whitespacesAndNewlines)
            buffer.removeFirst(cut)
            if !piece.isEmpty {
                out.append(piece)
                pieces += 1
            }
        }
        return out
    }

    /// Whatever is left when the answer ends.
    public mutating func flush() -> String? {
        let rest = String(buffer).trimmingCharacters(in: .whitespacesAndNewlines)
        buffer.removeAll()
        return rest.isEmpty ? nil : rest
    }

    private func nextCut() -> Int? {
        for (i, c) in buffer.enumerated() {
            let followedBySpace = i + 1 < buffer.count && buffer[i + 1].isWhitespace
            let sentenceEnd = (Self.enders.contains(c) && followedBySpace) || c == "\n"
            if sentenceEnd, i + 1 >= minChars { return i + 1 }
            if pieces == 0, Self.pauses.contains(c), followedBySpace, i + 1 >= firstPauseChars { return i + 1 }
        }
        if buffer.count >= maxChars {
            let comma = buffer[..<maxChars].lastIndex { [",", ";", ":"].contains($0) }
            if let comma, comma >= minChars { return comma + 1 }
            return maxChars
        }
        return nil
    }

    private static let enders: Set<Character> = [".", "!", "?", "…", "。", "！", "？"]
    private static let pauses: Set<Character> = [",", ";", ":", "，", "、"]
}
