import Foundation

/// Deployment is execution of the host's own tooling under the SSH user's normal
/// permissions. Nothing here elevates, and nothing here is presented as root.
public enum ContainerRuntime: String, CaseIterable, Sendable {
    case docker, podman

    public var label: String { rawValue.capitalized }
}

public struct ContainerSpec: Sendable, Equatable {
    public var image: String
    public var name: String
    public var ports: [PortMapping]
    public var volumes: [VolumeMapping]
    public var environment: [String: String]
    public var command: [String]

    public struct PortMapping: Sendable, Equatable, Hashable {
        public var host: Int
        public var container: Int
        public init(host: Int, container: Int) {
            self.host = host
            self.container = container
        }
    }

    public struct VolumeMapping: Sendable, Equatable, Hashable {
        public var hostPath: String
        public var containerPath: String
        public var readOnly: Bool
        public init(hostPath: String, containerPath: String, readOnly: Bool = false) {
            self.hostPath = hostPath
            self.containerPath = containerPath
            self.readOnly = readOnly
        }
    }

    public init(
        image: String,
        name: String,
        ports: [PortMapping] = [],
        volumes: [VolumeMapping] = [],
        environment: [String: String] = [:],
        command: [String] = []
    ) {
        self.image = image
        self.name = name
        self.ports = ports
        self.volumes = volumes
        self.environment = environment
        self.command = command
    }
}

public enum DeployError: Error, Equatable {
    case invalidImage
    case invalidName
    case invalidPort
    case invalidPath
    case invalidEnvironment(String)
    case invalidCommand
    case invalidContext
    case invalidNamespace
    case invalidUnit
    case tooManyFields
}

public enum Deploy {
    public static let managedLabel = "anthracite.managed"
    public static let maxPorts = 16
    public static let maxVolumes = 16
    public static let maxEnvironment = 32

    /// Registry reference grammar: no scheme, no shell characters, no whitespace.
    public static func isValidImage(_ image: String) -> Bool {
        guard !image.isEmpty, image.count <= 255 else { return false }
        let pattern = "^[a-z0-9]+([._-][a-z0-9]+)*(:[0-9]{1,5})?(/[a-z0-9]+([._-][a-z0-9]+)*)*(:[A-Za-z0-9._-]{1,128})?(@sha256:[a-f0-9]{64})?$"
        return image.range(of: pattern, options: .regularExpression) != nil
    }

    /// Container, unit and workload names share one conservative grammar, so the
    /// same name cannot mean two things on the host.
    public static func isValidName(_ name: String) -> Bool {
        guard !name.isEmpty, name.count <= 63 else { return false }
        return name.range(of: "^[a-z0-9][a-z0-9-]*[a-z0-9]$|^[a-z0-9]$", options: .regularExpression) != nil
    }

    public static func isValidPort(_ port: Int) -> Bool { port > 0 && port < 65536 }

    public static func isValidNamespace(_ namespace: String) -> Bool { isValidName(namespace) }

    public static func isValidContext(_ context: String) -> Bool {
        guard !context.isEmpty, context.count <= 128 else { return false }
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.@:/")
        return context.unicodeScalars.allSatisfy { allowed.contains($0) }
    }

    public static func isValidEnvironmentName(_ name: String) -> Bool {
        name.range(of: "^[A-Z][A-Z0-9_]{0,63}$", options: .regularExpression) != nil
    }

    public static func quote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    // MARK: - Containers

    public static func containerRunCommand(
        _ spec: ContainerSpec,
        runtime: ContainerRuntime,
        detach: Bool = true
    ) throws -> String {
        guard isValidImage(spec.image) else { throw DeployError.invalidImage }
        guard isValidName(spec.name) else { throw DeployError.invalidName }
        guard spec.ports.count <= maxPorts, spec.volumes.count <= maxVolumes,
              spec.environment.count <= maxEnvironment else { throw DeployError.tooManyFields }
        var arguments = ["run"]
        if detach { arguments.append("--detach") }
        arguments.append(contentsOf: ["--name", quote(spec.name)])
        arguments.append(contentsOf: ["--label", quote("\(managedLabel)=1")])
        for mapping in spec.ports {
            guard isValidPort(mapping.host), isValidPort(mapping.container) else { throw DeployError.invalidPort }
            arguments.append(contentsOf: ["--publish", quote("\(mapping.host):\(mapping.container)")])
        }
        for volume in spec.volumes {
            guard RemotePath.isValidAbsolute(volume.hostPath), volume.containerPath.hasPrefix("/"),
                  !volume.containerPath.contains("..") else { throw DeployError.invalidPath }
            let suffix = volume.readOnly ? ":ro" : ""
            arguments.append(contentsOf: ["--volume", quote("\(volume.hostPath):\(volume.containerPath)\(suffix)")])
        }
        for key in spec.environment.keys.sorted() {
            guard isValidEnvironmentName(key) else { throw DeployError.invalidEnvironment(key) }
            let value = spec.environment[key] ?? ""
            guard !value.contains("\u{0}"), value.count <= 4096 else { throw DeployError.invalidEnvironment(key) }
            arguments.append(contentsOf: ["--env", quote("\(key)=\(value)")])
        }
        arguments.append(quote(spec.image))
        for part in spec.command {
            guard !part.isEmpty, !part.contains("\u{0}"), part.count <= 4096 else { throw DeployError.invalidCommand }
            arguments.append(quote(part))
        }
        return ([runtime.rawValue] + arguments).joined(separator: " ")
    }

    public static func inspectLabelCommand(runtime: ContainerRuntime, name: String) throws -> String {
        guard isValidName(name) else { throw DeployError.invalidName }
        let format = "{{index .Config.Labels \"\(managedLabel)\"}}"
        return "\(runtime.rawValue) inspect --format \(quote(format)) \(quote(name))"
    }

    /// Removal is only ever offered after the host confirms this app created it.
    public static func containerRemoveCommand(runtime: ContainerRuntime, name: String) throws -> String {
        guard isValidName(name) else { throw DeployError.invalidName }
        return "\(runtime.rawValue) rm --force \(quote(name))"
    }

    public static func containerLogsCommand(runtime: ContainerRuntime, name: String, tail: Int) throws -> String {
        guard isValidName(name) else { throw DeployError.invalidName }
        let lines = max(1, min(tail, 2000))
        return "\(runtime.rawValue) logs --tail \(lines) \(quote(name))"
    }

    // MARK: - Kubernetes

    public static func kubectlApplyCommand(context: String, namespace: String) throws -> String {
        guard isValidContext(context) else { throw DeployError.invalidContext }
        guard isValidNamespace(namespace) else { throw DeployError.invalidNamespace }
        return "kubectl --context \(quote(context)) --namespace \(quote(namespace)) apply -f -"
    }

    /// The permission check runs immediately before the mutation, in the same
    /// context and namespace the mutation will use.
    public static func kubectlPermissionCommand(context: String, namespace: String, verb: String) throws -> String {
        guard isValidContext(context) else { throw DeployError.invalidContext }
        guard isValidNamespace(namespace) else { throw DeployError.invalidNamespace }
        guard ["get", "list", "create", "patch", "delete"].contains(verb) else {
            throw DeployError.invalidContext
        }
        return "kubectl --context \(quote(context)) --namespace \(quote(namespace)) auth can-i \(verb) \(quote("deploy.apps"))"
    }

    public static func kubectlRolloutCommand(context: String, namespace: String, name: String) throws -> String {
        guard isValidContext(context), isValidNamespace(namespace), isValidName(name) else {
            throw DeployError.invalidContext
        }
        return "kubectl --context \(quote(context)) --namespace \(quote(namespace)) rollout status "
            + quote("deployment/\(name)") + " --timeout=120s"
    }

    /// A manifest generated from typed fields, never from user-supplied YAML.
    public static func manifest(
        name: String,
        image: String,
        replicas: Int,
        containerPort: Int,
        namespace: String
    ) throws -> String {
        guard isValidName(name) else { throw DeployError.invalidName }
        guard isValidImage(image) else { throw DeployError.invalidImage }
        guard isValidNamespace(namespace) else { throw DeployError.invalidNamespace }
        guard isValidPort(containerPort) else { throw DeployError.invalidPort }
        let count = max(1, min(replicas, 10))
        return """
        apiVersion: apps/v1
        kind: Deployment
        metadata:
          name: \(name)
          namespace: \(namespace)
          labels:
            \(managedLabel): "1"
        spec:
          replicas: \(count)
          selector:
            matchLabels:
              app: \(name)
          template:
            metadata:
              labels:
                app: \(name)
            spec:
              containers:
                - name: \(name)
                  image: \(image)
                  ports:
                    - containerPort: \(containerPort)
        """
    }

    public static func kubectlDeleteCommand(context: String, namespace: String, name: String) throws -> String {
        guard isValidContext(context), isValidNamespace(namespace), isValidName(name) else {
            throw DeployError.invalidContext
        }
        return "kubectl --context \(quote(context)) --namespace \(quote(namespace)) delete deployment "
            + quote(name) + " --ignore-not-found"
    }

    // MARK: - Native

    /// A native program runs as a systemd *user* unit: the SSH user's own
    /// permissions, no elevation, and `--collect` so a failed unit does not linger.
    public static func systemdRunCommand(unit: String, executable: String, arguments: [String]) throws -> String {
        guard isValidName(unit) else { throw DeployError.invalidUnit }
        guard RemotePath.isValidAbsolute(executable) else { throw DeployError.invalidPath }
        var parts = ["systemd-run", "--user", "--collect", "--unit", quote(unit), quote(executable)]
        for argument in arguments {
            guard !argument.contains("\u{0}"), argument.count <= 4096 else { throw DeployError.invalidCommand }
            parts.append(quote(argument))
        }
        return parts.joined(separator: " ")
    }

    public static func systemdStatusCommand(unit: String) throws -> String {
        guard isValidName(unit) else { throw DeployError.invalidUnit }
        return "systemctl --user status --no-pager \(quote(unit))"
    }

    public static func systemdStopCommand(unit: String) throws -> String {
        guard isValidName(unit) else { throw DeployError.invalidUnit }
        return "systemctl --user stop \(quote(unit))"
    }
}

/// Absolute remote paths, validated once and reused by files, deployments and Git.
public enum RemotePath {
    public static let maxLength = 4096

    public static func isValidAbsolute(_ path: String) -> Bool {
        guard path.hasPrefix("/"), path.count > 1, path.count <= maxLength else { return false }
        guard !path.contains("\u{0}") else { return false }
        guard !path.unicodeScalars.contains(where: { $0.value < 0x20 }) else { return false }
        let components = path.split(separator: "/", omittingEmptySubsequences: false)
        return !components.contains { $0 == ".." || $0 == "." }
    }

    /// Joins a child name onto a directory. A child may not escape the directory.
    public static func join(_ directory: String, _ name: String) -> String? {
        guard isValidAbsolute(directory), !name.isEmpty, name.count <= 255 else { return nil }
        guard !name.contains("/"), !name.contains("\u{0}") else { return nil }
        guard name != ".", name != ".." else { return nil }
        let base = directory.hasSuffix("/") ? directory : directory + "/"
        let joined = base + name
        return isValidAbsolute(joined) ? joined : nil
    }

    public static func parent(_ path: String) -> String? {
        guard isValidAbsolute(path) else { return nil }
        let trimmed = path.hasSuffix("/") && path.count > 1 ? String(path.dropLast()) : path
        guard let index = trimmed.lastIndex(of: "/") else { return nil }
        let parent = index == trimmed.startIndex ? "/" : String(trimmed[..<index])
        return isValidAbsolute(parent) || parent == "/" ? parent : nil
    }
}
