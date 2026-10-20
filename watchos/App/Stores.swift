import Foundation
import Security
import WatchGPTKit

/// The ChatGPT sign-in, in the watch's keychain: only this app, only this watch, readable once it was unlocked after
/// a restart. Never synced or backed up.
final class KeychainStore: DataStore, @unchecked Sendable {
    private let service = "io.github.alisial786.watchgpt"

    func read(_ name: String) throws -> Data? {
        var query = base(name)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainError(status: status) }
        return item as? Data
    }

    func write(_ name: String, _ data: Data) throws {
        let update = SecItemUpdate(base(name) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return }
        guard update == errSecItemNotFound else { throw KeychainError(status: update) }
        var add = base(name)
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError(status: status) }
    }

    func delete(_ name: String) throws {
        let status = SecItemDelete(base(name) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainError(status: status) }
    }

    private func base(_ name: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: name]
    }

    struct KeychainError: Error {
        let status: OSStatus
    }
}

/// Reminders, notes and memory, as files in the app's own storage, encrypted by the watch (Data Protection) and kept
/// out of backups.
final class FileStore: DataStore, @unchecked Sendable {
    private let folder: URL

    init(folder name: String = "WatchGPT") {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        folder = base.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = folder
        try? url.setResourceValues(values)
    }

    func read(_ name: String) throws -> Data? {
        let url = file(name)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try Data(contentsOf: url)
    }

    func write(_ name: String, _ data: Data) throws {
        try data.write(to: file(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    func delete(_ name: String) throws {
        let url = file(name)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }

    private func file(_ name: String) -> URL {
        folder.appendingPathComponent("\(name).json")
    }
}
