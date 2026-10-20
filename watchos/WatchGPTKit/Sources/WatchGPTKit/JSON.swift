import Foundation

/// A JSON value: tool arguments and schemas, request bodies and the events ChatGPT streams back. Output items are
/// replayed to the stateless endpoint as they came, so they're kept as values rather than decoded into types.
public enum JSON: Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSON])
    case object([String: JSON])

    /// Null for anything that isn't JSON.
    public static func parse(_ data: Data) -> JSON? {
        try? JSONDecoder().decode(JSON.self, from: data)
    }

    public static func parse(_ text: String) -> JSON? {
        parse(Data(text.utf8))
    }

    /// Keys sorted, so the same value always encodes the same way.
    public var data: Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return (try? encoder.encode(self)) ?? Data("null".utf8)
    }

    public var text: String {
        String(decoding: data, as: UTF8.self)
    }

    public subscript(key: String) -> JSON? {
        if case let .object(fields) = self { return fields[key] }
        return nil
    }

    public var string: String? {
        if case let .string(value) = self { return value }
        return nil
    }

    public var double: Double? {
        if case let .number(value) = self { return value }
        return nil
    }

    /// Only for whole numbers that fit.
    public var int: Int? {
        guard case let .number(value) = self, value.rounded() == value, abs(value) < 9.0e15 else { return nil }
        return Int(value)
    }

    public var bool: Bool? {
        if case let .bool(value) = self { return value }
        return nil
    }

    public var object: [String: JSON]? {
        if case let .object(value) = self { return value }
        return nil
    }

    public var array: [JSON]? {
        if case let .array(value) = self { return value }
        return nil
    }
}

extension JSON: Codable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSON].self) {
            self = .array(value)
        } else {
            self = try .object(container.decode([String: JSON].self))
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case let .bool(value): try container.encode(value)
        // Whole numbers as integers: "5", never "5.0".
        case let .number(value): if let whole = JSON.number(value).int { try container.encode(whole) } else { try container.encode(value) }
        case let .string(value): try container.encode(value)
        case let .array(value): try container.encode(value)
        case let .object(value): try container.encode(value)
        }
    }
}

extension JSON: ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByBooleanLiteral,
    ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral, ExpressibleByNilLiteral
{
    public init(stringLiteral value: String) { self = .string(value) }
    public init(integerLiteral value: Int) { self = .number(Double(value)) }
    public init(booleanLiteral value: Bool) { self = .bool(value) }
    public init(arrayLiteral elements: JSON...) { self = .array(elements) }
    public init(dictionaryLiteral elements: (String, JSON)...) { self = .object(Dictionary(elements, uniquingKeysWith: { _, last in last })) }
    public init(nilLiteral _: ()) { self = .null }
}
