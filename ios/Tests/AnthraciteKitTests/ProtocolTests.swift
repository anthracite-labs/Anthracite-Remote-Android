import XCTest
@testable import AnthraciteKit

final class HostKeyTests: XCTestCase {
    // A fixed Ed25519 key blob. The fingerprint is what `ssh-keygen -lf` prints.
    private let base64 = "AAAAC3NzaC1lZDI1NTE5AAAAICAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4/"
    private let fingerprint = "SHA256:LVC7PY6ur9Dk+WVIYQsgEU2uvTXAor8XoYLl+ZmNyRo"

    func testFingerprintMatchesSSHKeygen() {
        let key = HostKey(algorithm: "ssh-ed25519", base64: base64)
        XCTAssertEqual(key.fingerprint, fingerprint)
    }

    func testKeyBlobParsingRejectsRSAAndMalformedInput() {
        var writer = SFTPWriter()
        writer.write(string: "ssh-rsa")
        writer.write(bytes: Data(repeating: 1, count: 260))
        XCTAssertThrowsError(try HostKeyWire.parsePublicKeyBlob(writer.data)) { error in
            XCTAssertEqual(error as? HostKeyError, .unsupportedAlgorithm("ssh-rsa"))
        }
        XCTAssertThrowsError(try HostKeyWire.parsePublicKeyBlob(Data())) { error in
            XCTAssertEqual(error as? HostKeyError, .malformed)
        }
        var truncated = SFTPWriter()
        truncated.write(string: "ssh-ed25519")
        XCTAssertThrowsError(try HostKeyWire.parsePublicKeyBlob(truncated.data))
    }

    func testKeyExchangeReplyCarriesTheKey() throws {
        let blob = Data(base64Encoded: base64)!
        // The reply is `string K_S`, `string Q_S`, `string signature`.
        var wire = Data()
        wire.append(UInt8((blob.count >> 24) & 0xff))
        wire.append(UInt8((blob.count >> 16) & 0xff))
        wire.append(UInt8((blob.count >> 8) & 0xff))
        wire.append(UInt8(blob.count & 0xff))
        wire.append(blob)
        let parsed = try HostKeyWire.hostKeyFromKeyExchangeReply(wire)
        XCTAssertEqual(parsed.fingerprint, fingerprint)
        XCTAssertEqual(parsed.algorithm, "ssh-ed25519")
    }

    func testPinApprovalLifecycle() {
        var store = HostKeyStore()
        let key = HostKey(algorithm: "ssh-ed25519", base64: base64)
        guard case .unknownNeedsApproval(let presented) = store.decide(host: "example.com", port: 22, presented: key) else {
            return XCTFail("first use must ask for approval")
        }
        XCTAssertEqual(presented, key)
        store.approve(host: "example.com", port: 22, key: key)
        XCTAssertEqual(store.decide(host: "example.com", port: 22, presented: key), .approved)

        let other = HostKey(algorithm: "ssh-ed25519", base64: "AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        guard case .changed(let previous, let now) = store.decide(host: "example.com", port: 22, presented: other) else {
            return XCTFail("a different key must be refused")
        }
        XCTAssertEqual(previous.key, key)
        XCTAssertEqual(now, other)

        // A pin for one port never covers another port.
        if case .unknownNeedsApproval = store.decide(host: "example.com", port: 2222, presented: key) {} else {
            XCTFail("a different port is a different endpoint")
        }
        store.forget(host: "example.com", port: 22)
        if case .unknownNeedsApproval = store.decide(host: "example.com", port: 22, presented: key) {} else {
            XCTFail("forgetting a pin must ask again")
        }
    }

    func testOpenSSHEd25519KeyParsing() throws {
        let text = """
        -----BEGIN OPENSSH PRIVATE KEY-----
        b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
        QyNTUxOQAAACAgISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+PwAAAJgRIjNEESIz
        RAAAAAtzc2gtZWQyNTUxOQAAACAgISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+Pw
        AAAEAAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8w
        MTIzNDU2Nzg5Ojs8PT4/AAAAD3Rlc3RAYW50aHJhY2l0ZQECAwQFBg==
        -----END OPENSSH PRIVATE KEY-----
        """
        let seed = try OpenSSHKey.ed25519(from: text)
        XCTAssertEqual(seed.count, 32)
        XCTAssertEqual(seed.first, 0x00)
        XCTAssertEqual(seed.last, 0x1f)

        XCTAssertThrowsError(try OpenSSHKey.ed25519(from: "not a key"))
        let pem = "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"
        XCTAssertThrowsError(try OpenSSHKey.ed25519(from: pem))
    }
}

final class TmuxTests: XCTestCase {
    func testListingParsesEveryFieldAndSkipsGarbage() {
        let output = """
        work\t$0\t3\t1\t1700000000
        dev box\t$4\t1\t0\t1700000100
        bad\tx1\t1\t0\t0
        missing\t$9\tone\t0\t0

        """
        let sessions = Tmux.parseSessions(output)
        XCTAssertEqual(sessions.count, 2)
        XCTAssertEqual(sessions[0].name, "work")
        XCTAssertEqual(sessions[0].id, "$0")
        XCTAssertEqual(sessions[0].windows, 3)
        XCTAssertTrue(sessions[0].attached)
        XCTAssertEqual(sessions[1].name, "dev box")
    }

    func testIdentityRequiresBothNameAndID() {
        let sessions = [TmuxSession(name: "work", id: "$2", windows: 1, attached: false, created: 0)]
        XCTAssertTrue(Tmux.identityMatches(sessions, name: "work", id: "$2"))
        XCTAssertFalse(Tmux.identityMatches(sessions, name: "work", id: "$3"))
        XCTAssertFalse(Tmux.identityMatches(sessions, name: "other", id: "$2"))
    }

    func testCommandsQuoteAndValidate() {
        let session = TmuxSession(name: "a'b", id: "$7", windows: 1, attached: false, created: 0)
        XCTAssertEqual(Tmux.attachCommand(session), "tmux attach-session -t '$7'")
        XCTAssertEqual(Tmux.stopCommand(session), "tmux kill-session -t '$7'")
        XCTAssertEqual(Tmux.newSessionCommand(name: "a'b"), "tmux new-session -d -s 'a'\\''b'")
        XCTAssertNil(Tmux.newSessionCommand(name: "bad\nname"))
        XCTAssertNil(Tmux.attachCommand(TmuxSession(name: "x", id: "7", windows: 1, attached: false, created: 0)))
    }
}

final class GitTests: XCTestCase {
    func testStatusParsing() {
        let output = """
        # branch.oid 1234abcd
        # branch.head main
        # branch.upstream origin/main
        # branch.ab +2 -1
        1 .M N... 100644 100644 100644 abcdef abcdef src/main.swift
        ? docs/notes.md
        u UU N... 100644 100644 100644 100644 aaaa bbbb cccc conflicted.txt

        """
        let status = Git.parseStatus(output)
        XCTAssertEqual(status.branch, "main")
        XCTAssertEqual(status.upstream, "origin/main")
        XCTAssertEqual(status.ahead, 2)
        XCTAssertEqual(status.behind, 1)
        XCTAssertEqual(status.entries.count, 3)
        XCTAssertEqual(status.entries[0].path, "src/main.swift")
        XCTAssertEqual(status.entries[0].code, ".M")
        XCTAssertEqual(status.entries[1].code, "??")
        XCTAssertEqual(status.entries[2].kind, "conflict")
    }

    func testLogParsingRejectsNonHashes() {
        let output = """
        b1946ac92492d2347c6235b4d2611184b1946ac9\tA U Thor\t2026-10-23T10:00:00Z\tinitial
        not-a-hash\tX\t2026-10-23\tbad
        """
        let entries = Git.parseLog(output)
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(entries[0].subject, "initial")
    }

    func testQuotingAndValidation() {
        XCTAssertEqual(Git.quote("/tmp/a b"), "'/tmp/a b'")
        XCTAssertEqual(Git.quote("it's"), "'it'\\''s'")
        XCTAssertFalse(Git.isValidPath("relative/path"))
        XCTAssertFalse(Git.isValidPath("/tmp/../etc"))
        XCTAssertTrue(Git.isValidPath("/home/user/project"))
        XCTAssertNil(Git.pushCommand(at: "/home/user/p", remote: "origin", branch: "-f", forceWithLease: false))
        XCTAssertTrue(
            Git.pushCommand(at: "/home/user/p", remote: "origin", branch: "main", forceWithLease: true)?
                .contains("--force-with-lease") == true
        )
        XCTAssertNil(Git.stageCommand(at: "/home/user/p", file: "../secret"))
    }
}

final class DeployTests: XCTestCase {
    func testContainerRunCarriesTheOwnershipLabelAndQuotesEverything() throws {
        let spec = ContainerSpec(
            image: "nginx:1.27",
            name: "web-1",
            ports: [.init(host: 8080, container: 80)],
            volumes: [.init(hostPath: "/srv/www", containerPath: "/usr/share/nginx/html", readOnly: true)],
            environment: ["TZ": "Africa/Johannesburg"],
            command: ["nginx", "-g", "daemon off;"]
        )
        let command = try Deploy.containerRunCommand(spec, runtime: .podman)
        XCTAssertTrue(command.hasPrefix("podman run --detach --name 'web-1'"))
        XCTAssertTrue(command.contains("--label 'anthracite.managed=1'"))
        XCTAssertTrue(command.contains("--publish '8080:80'"))
        XCTAssertTrue(command.contains("--volume '/srv/www:/usr/share/nginx/html:ro'"))
        XCTAssertTrue(command.contains("--env 'TZ=Africa/Johannesburg'"))
        XCTAssertTrue(command.hasSuffix("'nginx:1.27' 'nginx' '-g' 'daemon off;'"))
    }

    func testInvalidInputsAreRefusedBeforeAnyCommandExists() {
        XCTAssertThrowsError(try Deploy.containerRunCommand(
            ContainerSpec(image: "nginx; rm -rf /", name: "web"),
            runtime: .docker
        ))
        XCTAssertThrowsError(try Deploy.containerRunCommand(
            ContainerSpec(image: "nginx", name: "Web"),
            runtime: .docker
        ))
        XCTAssertThrowsError(try Deploy.containerRunCommand(
            ContainerSpec(image: "nginx", name: "web", ports: [.init(host: 0, container: 80)]),
            runtime: .docker
        ))
        XCTAssertThrowsError(try Deploy.containerRunCommand(
            ContainerSpec(image: "nginx", name: "web", environment: ["lower": "x"]),
            runtime: .docker
        ))
        XCTAssertThrowsError(try Deploy.containerRunCommand(
            ContainerSpec(image: "nginx", name: "web",
                          volumes: [.init(hostPath: "/srv/../../etc", containerPath: "/data")]),
            runtime: .docker
        ))
    }

    func testRemovalRequiresTheLabelCheckAndAValidName() throws {
        let inspect = try Deploy.inspectLabelCommand(runtime: .docker, name: "web-1")
        XCTAssertTrue(inspect.contains("anthracite.managed"))
        XCTAssertThrowsError(try Deploy.containerRemoveCommand(runtime: .docker, name: "../x"))
        XCTAssertEqual(try Deploy.containerLogsCommand(runtime: .docker, name: "web-1", tail: 5000),
                       "docker logs --tail 2000 'web-1'")
    }

    func testKubernetesCommandsNameContextAndNamespace() throws {
        let apply = try Deploy.kubectlApplyCommand(context: "prod-cluster", namespace: "apps")
        XCTAssertEqual(apply, "kubectl --context 'prod-cluster' --namespace 'apps' apply -f -")
        let canI = try Deploy.kubectlPermissionCommand(context: "prod-cluster", namespace: "apps", verb: "patch")
        XCTAssertTrue(canI.contains("auth can-i patch 'deploy.apps'"))
        XCTAssertThrowsError(try Deploy.kubectlApplyCommand(context: "prod; rm", namespace: "apps"))
        XCTAssertThrowsError(try Deploy.kubectlApplyCommand(context: "prod", namespace: "Apps"))
        let manifest = try Deploy.manifest(name: "web", image: "nginx:1.27", replicas: 99,
                                           containerPort: 80, namespace: "apps")
        XCTAssertTrue(manifest.contains("replicas: 10"))
        XCTAssertTrue(manifest.contains("anthracite.managed: \"1\""))
        XCTAssertFalse(manifest.contains("\t"))
    }

    func testNativeUnitIsAUserUnit() throws {
        let command = try Deploy.systemdRunCommand(
            unit: "job-1",
            executable: "/usr/local/bin/agent",
            arguments: ["--port", "1234"]
        )
        XCTAssertTrue(command.hasPrefix("systemd-run --user --collect --unit 'job-1' '/usr/local/bin/agent'"))
        XCTAssertFalse(command.contains("sudo"))
        XCTAssertThrowsError(try Deploy.systemdRunCommand(unit: "job-1", executable: "agent", arguments: []))
    }

    func testRemotePathPolicy() {
        XCTAssertTrue(RemotePath.isValidAbsolute("/srv/app"))
        XCTAssertFalse(RemotePath.isValidAbsolute("/srv/../etc"))
        XCTAssertFalse(RemotePath.isValidAbsolute("srv/app"))
        XCTAssertFalse(RemotePath.isValidAbsolute("/srv/\u{0}x"))
        XCTAssertEqual(RemotePath.join("/srv", "app"), "/srv/app")
        XCTAssertNil(RemotePath.join("/srv", "../etc"))
        XCTAssertNil(RemotePath.join("/srv", "a/b"))
        XCTAssertEqual(RemotePath.parent("/srv/app/file.txt"), "/srv/app")
    }
}

final class SFTPWireTests: XCTestCase {
    func testWriterAndReaderRoundTrip() throws {
        var writer = SFTPWriter()
        writer.write(byte: 3)
        writer.write(uint32: 42)
        writer.write(uint64: 1 << 40)
        writer.write(string: "hello")
        var reader = SFTPReader(writer.data)
        XCTAssertEqual(try reader.readByte(), 3)
        XCTAssertEqual(try reader.readUInt32(), 42)
        XCTAssertEqual(try reader.readUInt64(), 1 << 40)
        XCTAssertEqual(try reader.readString(), "hello")
        XCTAssertTrue(reader.isAtEnd)
    }

    func testDecoderHandlesSplitPacketsAndRejectsAbsurdLengths() {
        var writer = SFTPWriter()
        writer.write(byte: SFTP.replyData)
        writer.write(uint32: 7)
        writer.write(bytes: Data(repeating: 0xAB, count: 10))
        let framed = writer.framed()
        var decoder = SFTPPacketDecoder()
        decoder.append(framed.prefix(6))
        XCTAssertNil(decoder.next())
        decoder.append(framed.dropFirst(6))
        let packet = decoder.next()
        XCTAssertNotNil(packet)
        var reader = SFTPReader(packet!)
        XCTAssertEqual(try? reader.readByte(), SFTP.replyData)
        XCTAssertEqual(try? reader.readUInt32(), 7)

        var bad = SFTPPacketDecoder()
        bad.append(Data([0x7F, 0xFF, 0xFF, 0xFF, 0, 0, 0, 0]))
        XCTAssertNil(bad.next())
    }
}

final class StorageTests: XCTestCase {
    func testProfilesAndSecretsRoundTrip() throws {
        let store = ProfileStore(store: MemorySecretStore())
        var profile = ServerProfile(name: "home", host: "example.com", username: "me", authKind: .privateKey)
        XCTAssertNil(profile.validate())
        XCTAssertTrue(try store.profiles().isEmpty)
        try store.save(profile)
        try store.saveSecret("KEY", for: profile)
        XCTAssertEqual(try store.profiles().count, 1)
        XCTAssertEqual(try store.secret(for: profile), "KEY")

        let second = ServerProfile(name: "work", host: "work.example", username: "me", authKind: .password)
        try store.save(second)
        XCTAssertEqual(try store.profiles().count, 2)
        try store.delete(profile.id)
        XCTAssertEqual(try store.profiles().count, 1)
        XCTAssertNil(try store.secret(for: profile))
    }

    func testCorruptProfileListIsReportedNotReplaced() {
        let memory = MemorySecretStore()
        let store = ProfileStore(store: memory)
        XCTAssertNoThrow(try memory.set(Data("not json".utf8), for: "profiles.v1"))
        XCTAssertThrowsError(try store.profiles()) { error in
            XCTAssertEqual(error as? CredentialStoreError, .corrupted)
        }
        XCTAssertEqual(try memory.value(for: "profiles.v1"), Data("not json".utf8))
    }

    func testValidationRejectsURLsAndBadPorts() {
        XCTAssertNotNil(ServerProfile(name: "x", host: "https://example.com", username: "me", authKind: .password).validate())
        XCTAssertNotNil(ServerProfile(name: "x", host: "example.com", port: 0, username: "me", authKind: .password).validate())
        XCTAssertNotNil(ServerProfile(name: "x", host: "example.com", username: "two words", authKind: .password).validate())
    }
}
