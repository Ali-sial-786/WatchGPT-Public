import Foundation

/// Takes secrets and ways out of text before it goes to ChatGPT: one-time codes, card numbers, IBANs, passwords, keys
/// and tokens, links, phone numbers and email addresses. Each is replaced by what it was ("[code hidden]"), so the model
/// can still tell the user there was one. A link keeps its site; its path (tokens, reset links) doesn't survive.
/// The Android app's Redactor, rule for rule.
public enum Redactor {
    public enum Kind: Sendable { case privateKey, key, link, email, iban, card, password, code, phone }

    public struct Cleaned: Sendable {
        public let text: String
        public let hidden: [Kind]
    }

    public static func clean(_ text: String) -> Cleaned {
        var hidden: [Kind] = []
        var s = text
        s = replace(privateKey, in: s) { _ in hidden.append(.privateKey); return "[private key hidden]" }
        s = replace(jwt, in: s) { _ in hidden.append(.key); return "[key hidden]" }
        s = replace(knownKeys, in: s) { _ in hidden.append(.key); return "[key hidden]" }
        s = replace(link, in: s) { raw in
            var url = Substring(raw)
            while let last = url.last, ".,;:!?".contains(last) { url = url.dropLast() }
            hidden.append(.link)
            return "[link to \(host(String(url)))]" + raw.dropFirst(url.count)
        }
        s = replace(email, in: s) { _ in hidden.append(.email); return "[email address hidden]" }
        s = replace(iban, in: s) { raw in
            guard ibanValid(raw) else { return raw }
            hidden.append(.iban)
            return "[IBAN hidden]"
        }
        s = replace(card, in: s) { raw in
            guard luhn(raw.filter(\.isNumber)) else { return raw }
            hidden.append(.card)
            return "[card number hidden]"
        }
        // Phone numbers before codes, so a number isn't cut into "codes" with half of it left.
        s = replace(phone, in: s) { raw in
            let digits = raw.filter(\.isNumber).count
            guard (8 ... 15).contains(digits), !whole(date, raw.trimmingCharacters(in: .whitespaces)) else { return raw }
            hidden.append(.phone)
            return "[phone number hidden]"
        }
        s = codes(s, &hidden)
        s = replace(longToken, in: s) { raw in
            guard looksRandom(raw) else { return raw }
            hidden.append(.key)
            return "[key hidden]"
        }
        s = replace(secret, in: s) { _ in hidden.append(.password); return "[hidden]" }
        return Cleaned(text: s, hidden: hidden)
    }

    /// One-time codes, in whatever language or digits the text is written: nothing here looks for a word like "code".
    /// Shapes that are codes anyway (G-482913, X7K9PQ) and bare six-digit numbers always go; other bare numbers of 4 to
    /// 8 digits only in a short message, as a code's is. A year, a date, a time or a price stays.
    private static func codes(_ text: String, _ hidden: inout [Kind]) -> String {
        var s = text
        for pattern in shapedCodes {
            s = replace(pattern, in: s) { _ in hidden.append(.code); return "[code hidden]" }
        }
        let before = s
        var found: [Kind] = []
        s = replaceMatches(bareNumber, in: before) { raw, range in
            guard isCode(before, raw, range) else { return raw }
            found.append(.code)
            return "[code hidden]"
        }
        hidden += found
        return s
    }

    private static func isCode(_ text: String, _ raw: String, _ range: Range<String.Index>) -> Bool {
        let digits = raw.compactMap(\.wholeNumberValue)
        if priced(text, range) { return false }
        if digits.count == 6 { return true }
        let value = digits.reduce(0) { $0 * 10 + $1 }
        if digits.count == 4, (1900 ... 2099).contains(value) { return false }
        return messageLength(text, range) <= shortMessage
    }

    /// A currency sign right before or after it, in any currency: "12,50 €", "₹4999", "$ 1299".
    private static func priced(_ text: String, _ range: Range<String.Index>) -> Bool {
        func currency(_ c: Character?) -> Bool {
            c?.unicodeScalars.first.map { $0.properties.generalCategory == .currencySymbol } ?? false
        }
        func before() -> Character? {
            guard range.lowerBound > text.startIndex else { return nil }
            var i = text.index(before: range.lowerBound)
            if text[i] == " ", i > text.startIndex { i = text.index(before: i) }
            return text[i]
        }
        func after() -> Character? {
            var i = range.upperBound
            guard i < text.endIndex else { return nil }
            if text[i] == " ", text.index(after: i) < text.endIndex { i = text.index(after: i) }
            return text[i]
        }
        return currency(before()) || currency(after())
    }

    /// How long the one message around [range] is: lists put " | " or a new line between items.
    private static func messageLength(_ text: String, _ range: Range<String.Index>) -> Int {
        let start = separators.compactMap { text.range(of: $0, options: .backwards, range: text.startIndex ..< range.lowerBound)?.upperBound }.max() ?? text.startIndex
        let end = separators.compactMap { text.range(of: $0, range: range.upperBound ..< text.endIndex)?.lowerBound }.min() ?? text.endIndex
        return text.distance(from: start, to: end)
    }

    private static func host(_ url: String) -> String {
        var s = url.replacingOccurrences(of: "^https?://", with: "", options: [.regularExpression, .caseInsensitive])
        for cut in ["/", "?", "#"] {
            if let r = s.range(of: cut) { s = String(s[..<r.lowerBound]) }
        }
        if let at = s.range(of: "@") { s = String(s[at.upperBound...]) }
        if let colon = s.range(of: ":") { s = String(s[..<colon.lowerBound]) }
        s = s.lowercased()
        return s.isEmpty ? "a website" : s
    }

    private static func ibanValid(_ candidate: String) -> Bool {
        let iban = candidate.replacingOccurrences(of: " ", with: "")
        guard (15 ... 34).contains(iban.count) else { return false }
        let rearranged = iban.dropFirst(4) + iban.prefix(4)
        var remainder = 0
        for c in rearranged {
            let value = c.wholeNumberValue.map(String.init) ?? String(Int(c.asciiValue ?? 65) - 65 + 10)
            for d in value { remainder = (remainder * 10 + (d.wholeNumberValue ?? 0)) % 97 }
        }
        return remainder == 1
    }

    private static func luhn(_ digits: String) -> Bool {
        guard (13 ... 19).contains(digits.count) else { return false }
        var sum = 0
        for (i, c) in digits.reversed().enumerated() {
            var d = c.wholeNumberValue ?? 0
            if i % 2 == 1 {
                d *= 2
                if d > 9 { d -= 9 }
            }
            sum += d
        }
        return sum % 10 == 0
    }

    /// Keys and tokens: long, with letters and digits mixed, and close to random.
    private static func looksRandom(_ token: String) -> Bool {
        guard token.contains(where: \.isNumber), token.contains(where: \.isLetter) else { return false }
        var counts: [Character: Int] = [:]
        for c in token { counts[c, default: 0] += 1 }
        let n = Double(token.count)
        let entropy = -counts.values.reduce(0.0) { sum, k in let p = Double(k) / n; return sum + p * log2(p) }
        return entropy >= 3.5
    }

    // MARK: - Patterns (ICU, as the Android app's java.util.regex ones)

    private static func regex(_ pattern: String) -> NSRegularExpression {
        try! NSRegularExpression(pattern: pattern)
    }

    private static let privateKey = regex(#"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z0-9 ]*PRIVATE KEY-----|$)"#)
    private static let jwt = regex(#"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}"#)
    private static let knownKeys = regex(
        #"\b(?:sk-(?:proj-|ant-)?[A-Za-z0-9_-]{16,}|[sr]k_(?:live|test)_[A-Za-z0-9]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|"# +
            #"github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_-]{20,}|xox[abprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|"# +
            #"AIza[0-9A-Za-z_-]{35}|hf_[A-Za-z0-9]{30,})"#
    )
    private static let link = try! NSRegularExpression(pattern: #"\b(?:https?://|www\.)[^\s<>"'()\[\]]+"#, options: .caseInsensitive)
    private static let email = regex(#"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}\b"#)
    private static let iban = regex(#"\b[A-Z]{2}\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?\b"#)
    private static let card = regex(#"(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)"#)

    /// A word of letters and digits, like most passwords ("Sonne123", "hunter2!"), found by its shape rather than by a
    /// word like "password" before it, so it works whatever the language. Latin letters only.
    private static let secret = regex(
        #"(?:(?<=[("'])|(?<![\x21-\x7E]))(?=[\x21-\x7E]*[A-Za-z])(?=[\x21-\x7E]*[0-9])[\x21-\x2C\x30-\x39\x3B-\x7E]{6,}"# +
            #"(?=[.,:;!?]*(?![\x21-\x7E]))"#
    )
    private static let shapedCodes = [
        // G-123456, Google's style.
        regex(#"\b[A-Z]-\d{4,8}\b"#),
        // Letters and digits mixed, all caps, like X7K9PQ.
        regex(#"\b(?=[A-Z0-9]*\d[A-Z0-9]*\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{6,8}\b"#),
    ]

    /// 4 to 8 digits of any script on their own (or 123 456), not part of a date, a time, a price or a longer number.
    private static let bareNumber = regex(#"(?<!\p{Nd}|\p{Nd}[.:/,])(?:\p{Nd}{3}[ -]\p{Nd}{3}|\p{Nd}{4,8})(?!\p{Nd}|[.:/,]\p{Nd})"#)
    private static let shortMessage = 200
    private static let separators = [" | ", "\n"]
    private static let phone = regex(
        #"(?<![\w+])(?:\+|00)\d[\d \-/().]{6,20}\d|(?<!\w)0\d{2,5}[ \-/.]?\d[\d \-/.]{4,14}\d|"# +
            #"\(\d{3}\)\s?\d{3}[ -]\d{4}\b|\b\d{3}[ -]\d{3}[ -]\d{4}\b"#
    )
    private static let date = regex(#"\d{1,4}[./-]\d{1,2}[./-]\d{1,4}"#)
    private static let longToken = regex(#"\b[A-Za-z0-9+/=_-]{32,}\b"#)

    private static func whole(_ pattern: NSRegularExpression, _ text: String) -> Bool {
        guard let m = pattern.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) else { return false }
        return m.range == NSRange(text.startIndex..., in: text)
    }

    private static func replace(_ pattern: NSRegularExpression, in text: String, _ with: (String) -> String) -> String {
        replaceMatches(pattern, in: text) { raw, _ in with(raw) }
    }

    /// Every match replaced by what [with] says, left to right; [with] also gets where the match is in [text].
    private static func replaceMatches(_ pattern: NSRegularExpression, in text: String, _ with: (String, Range<String.Index>) -> String) -> String {
        var out = ""
        var cursor = text.startIndex
        for match in pattern.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let range = Range(match.range, in: text) else { continue }
            out += text[cursor ..< range.lowerBound]
            out += with(String(text[range]), range)
            cursor = range.upperBound
        }
        out += text[cursor...]
        return out
    }
}
