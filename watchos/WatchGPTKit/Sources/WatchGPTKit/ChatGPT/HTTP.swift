import Foundation
#if canImport(FoundationNetworking)
    import FoundationNetworking
#endif

public struct HTTPRequest: Sendable {
    public var url: URL
    public var method: String
    public var headers: [String: String]
    public var body: Data?
    /// For a stream, how long it may go quiet.
    public var timeout: TimeInterval

    public init(url: URL, method: String = "POST", headers: [String: String] = [:], body: Data? = nil, timeout: TimeInterval = 30) {
        self.url = url
        self.method = method
        self.headers = headers
        self.body = body
        self.timeout = timeout
    }

    public static func json(_ url: URL, _ body: JSON, headers: [String: String] = [:], timeout: TimeInterval = 30) -> HTTPRequest {
        HTTPRequest(url: url, headers: headers.merging(["Content-Type": "application/json"]) { a, _ in a }, body: body.data, timeout: timeout)
    }

    /// `application/x-www-form-urlencoded`, as the token endpoint wants for a code exchange.
    public static func form(_ url: URL, _ fields: [(String, String)]) -> HTTPRequest {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let body = fields
            .map { "\($0.0.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")=\($0.1.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")" }
            .joined(separator: "&")
        return HTTPRequest(url: url, headers: ["Content-Type": "application/x-www-form-urlencoded"], body: Data(body.utf8))
    }
}

public struct HTTPResponse: Sendable {
    public let status: Int
    /// Lower-cased names.
    public let headers: [String: String]

    public init(status: Int, headers: [String: String] = [:]) {
        self.status = status
        self.headers = Dictionary(headers.map { ($0.key.lowercased(), $0.value) }, uniquingKeysWith: { a, _ in a })
    }

    public var ok: Bool { (200 ..< 300).contains(status) }
}

/// Thrown when nothing came back: no connection, a timeout, a refused host.
public struct HTTPOffline: Error {
    public let reason: String

    public init(_ reason: String) {
        self.reason = reason
    }
}

/// How the watch talks to OpenAI. It never follows redirects (an authorization code is single-use, so a redirected
/// exchange could burn it) and never retries on its own: retries are the caller's, at most one.
public protocol HTTPTransport: Sendable {
    /// One request, the whole body back. Throws [HTTPOffline] when nothing came back, or a cancellation.
    func send(_ request: HTTPRequest) async throws -> (HTTPResponse, Data)

    /// A streamed response, line by line, empty lines included (they end server-sent events).
    func lines(_ request: HTTPRequest) async throws -> (HTTPResponse, AsyncThrowingStream<String, Error>)
}

#if canImport(Darwin)
    /// URLSession, refusing redirects and every host but OpenAI's.
    public final class URLSessionTransport: NSObject, HTTPTransport, URLSessionTaskDelegate, @unchecked Sendable {
        private let allowedHosts: Set<String>
        private var session: URLSession!

        public init(allowedHosts: Set<String> = OpenAIAuth.hosts) {
            self.allowedHosts = allowedHosts
            super.init()
            let config = URLSessionConfiguration.ephemeral
            config.urlCache = nil
            config.httpShouldSetCookies = false
            config.waitsForConnectivity = false
            config.tlsMinimumSupportedProtocolVersion = .TLSv12
            session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        }

        public func send(_ request: HTTPRequest) async throws -> (HTTPResponse, Data) {
            do {
                let (data, response) = try await session.data(for: urlRequest(request))
                return (Self.response(response), data)
            } catch {
                throw Self.mapped(error)
            }
        }

        public func lines(_ request: HTTPRequest) async throws -> (HTTPResponse, AsyncThrowingStream<String, Error>) {
            let bytes: URLSession.AsyncBytes
            let response: URLResponse
            do {
                (bytes, response) = try await session.bytes(for: urlRequest(request))
            } catch {
                throw Self.mapped(error)
            }
            let stream = AsyncThrowingStream<String, Error> { continuation in
                let task = Task {
                    var line: [UInt8] = []
                    do {
                        for try await byte in bytes {
                            if byte == 0x0A {
                                if line.last == 0x0D { line.removeLast() }
                                continuation.yield(String(decoding: line, as: UTF8.self))
                                line.removeAll(keepingCapacity: true)
                            } else {
                                line.append(byte)
                            }
                        }
                        if !line.isEmpty { continuation.yield(String(decoding: line, as: UTF8.self)) }
                        continuation.finish()
                    } catch {
                        continuation.finish(throwing: Self.mapped(error))
                    }
                }
                continuation.onTermination = { _ in task.cancel() }
            }
            return (Self.response(response), stream)
        }

        public func urlSession(
            _: URLSession,
            task _: URLSessionTask,
            willPerformHTTPRedirection _: HTTPURLResponse,
            newRequest _: URLRequest,
            completionHandler: @escaping (URLRequest?) -> Void
        ) {
            completionHandler(nil)
        }

        private func urlRequest(_ request: HTTPRequest) throws -> URLRequest {
            guard request.url.scheme == "https", let host = request.url.host, allowedHosts.contains(host) else {
                throw HTTPOffline("blocked host")
            }
            var out = URLRequest(url: request.url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: request.timeout)
            out.httpMethod = request.method
            out.httpBody = request.body
            for (name, value) in request.headers {
                out.setValue(value, forHTTPHeaderField: name)
            }
            return out
        }

        private static func response(_ response: URLResponse) -> HTTPResponse {
            guard let http = response as? HTTPURLResponse else { return HTTPResponse(status: 0) }
            var headers: [String: String] = [:]
            for (name, value) in http.allHeaderFields {
                if let name = name as? String, let value = value as? String { headers[name] = value }
            }
            return HTTPResponse(status: http.statusCode, headers: headers)
        }

        private static func mapped(_ error: Error) -> Error {
            if error is CancellationError { return error }
            if let url = error as? URLError, url.code == .cancelled { return CancellationError() }
            if error is HTTPOffline { return error }
            return HTTPOffline((error as? URLError).map { "url_\($0.code.rawValue)" } ?? "io")
        }
    }
#endif
