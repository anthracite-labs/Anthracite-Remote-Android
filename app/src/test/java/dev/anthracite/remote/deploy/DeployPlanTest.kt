package dev.anthracite.remote.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deployment creation is only as safe as the values that reach the host command.
 * These tests exercise that grammar directly: hostile names, images, ports,
 * volumes and environment values must be refused before a plan exists, an
 * accepted plan must render exactly the commands that were previewed, and no
 * environment value may appear in anything the user or the host can see.
 */
class DeployPlanTest {
    private fun rejected(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (expected: IllegalArgumentException) {
        true
    }

    private val name = "anthracite-1a2b3c4d"
    private val kubernetesUid = "0f8f8d5e-1c2b-4a3c-9d7e-2b6f5a4c3d21"

    private fun container(
        image: String = "nginx:1.27",
        ports: List<PortMapping> = emptyList(),
        volumes: List<VolumeMount> = emptyList(),
        environment: List<EnvVar> = emptyList(),
        runtime: DeployCommands.Runtime = DeployCommands.Runtime.DOCKER,
    ) = DeployPlan.Container(name, runtime, image, ports, volumes, environment)

    private fun workload(
        kind: WorkloadKind = WorkloadKind.DEPLOYMENT,
        context: String = "prod-cluster",
        namespace: String = "demo",
        image: String = "nginx:1.27",
        replicas: Int = 1,
        containerPort: Int = 8080,
        appliedUid: String? = null,
    ) = DeployPlan.Workload(name, context, namespace, kind, image, replicas, containerPort, appliedUid)

    // ---------------------------------------------------------------- ownership

    @Test
    fun `only anthracite-prefixed names are managed`() {
        assertTrue(DeployPlans.isManagedName(name))
        assertFalse(DeployPlans.isManagedName("nginx"))
        assertFalse(DeployPlans.isManagedName("anthracite-"))
        assertFalse(DeployPlans.isManagedName("Anthracite-abcd"))
        assertFalse(DeployPlans.isManagedName("anthracite-abcd;rm"))
        assertTrue(DeployPlans.isManagedName(DeployPlans.newName()))
    }

    @Test
    fun `a mismatched label means the container is not ours`() {
        assertTrue(DeployPlans.isOwnedResource(name, "1"))
        assertFalse(DeployPlans.isOwnedResource(name, null))
        assertFalse(DeployPlans.isOwnedResource(name, "0"))
        assertFalse(DeployPlans.isOwnedResource("somebody-elses", "1"))
    }

    // ------------------------------------------------------------------ container

    @Test
    fun `a container plan renders a labelled, quoted run command`() {
        val plan = container(
            ports = listOf(PortMapping(8080, 80)),
            volumes = listOf(VolumeMount("/srv/data", "/var/lib/data", readOnly = true)),
            environment = listOf(EnvVar("GREETING", "hello world")),
        )
        val commands = plan.commands()
        assertEquals(
            listOf(
                "docker pull nginx:1.27",
                plan.envFileCommand(),
                plan.runCommand(),
                plan.envFileRemoveCommand(),
            ),
            commands,
        )
        val run = plan.runCommand()
        assertTrue(run.startsWith("docker run --detach --name anthracite-1a2b3c4d"))
        assertTrue(run.contains("--label anthracite.managed=1"))
        assertTrue(run.contains("--publish 8080:80"))
        assertTrue(run.contains("--volume /srv/data:/var/lib/data:ro"))
        // The value is not an argument: the runtime reads a 0600 file instead.
        assertTrue(run.contains("--env-file ${plan.envFilePath()}"))
        assertFalse(run.contains("GREETING"))
        assertTrue(run.endsWith(" nginx:1.27"))
        assertTrue(plan.warnings.any { it.contains("root") })
        assertTrue(plan.warnings.any { it.contains("inspect") })
    }

    @Test
    fun `an environment value is never printed, never an argument and reaches a 0600 file`() {
        val secret = "s3cr3t-token-value"
        val plan = container(environment = listOf(EnvVar("TOKEN", secret)))
        // The review surface the Activity renders: commands, preview, warnings.
        val review = DeployPlanReview.detail(plan, "review", "manifest", "environment")
        assertFalse(review.contains(secret))
        assertTrue(review.contains("TOKEN=***"))
        assertFalse(plan.commands().any { it.contains(secret) })
        assertEquals("TOKEN=***", plan.environmentPreview().single())
        assertEquals("TOKEN=$secret\n", String(plan.envFileBytes()))
        // The file is created with a private umask and removed by path only.
        assertTrue(plan.envFileCommand().startsWith("umask 077; d=\"\$HOME/.anthracite/deploy\""))
        // The operation claims a private directory of its own, creates the file
        // exclusively inside it, and removes only what it created there.
        assertTrue(plan.envFileCommand().contains("set -C; mkdir \"\$p\""))
        assertTrue(plan.envFileCommand().contains("if ! cat > \"\$f\""))
        assertTrue(plan.envFileCommand().contains("then rm -f \"\$f\"; rmdir \"\$p\""))
        assertTrue(plan.envFileRemoveCommand().startsWith("rm -f \""))
        // Cleanup removes the file, then the directory only while it is empty, and
        // is idempotent. It is never a recursive delete.
        assertTrue(plan.envFileRemoveCommand().contains("; rmdir "))
        assertTrue(plan.envFileRemoveCommand().endsWith("; [ ! -e "))
        assertFalse(plan.envFileRemoveCommand().contains("rm -rf"))
        assertFalse(plan.envFileRemoveCommand().contains(secret))
    }

    @Test
    fun `a plan without environment has no file steps`() {
        val plan = container()
        assertEquals(listOf("docker pull nginx:1.27", plan.runCommand()), plan.commands())
        assertFalse(plan.runCommand().contains("--env"))
    }

    @Test
    fun `podman uses the podman binary and warns about rootful mode`() {
        val plan = container(runtime = DeployCommands.Runtime.PODMAN)
        assertTrue(plan.runCommand().startsWith("podman run "))
        assertTrue(plan.warnings.any { it.contains("Rootless") })
    }

    @Test
    fun `hostile image references never reach a command`() {
        for (bad in listOf(
            "nginx; rm -rf /",
            "nginx && echo pwned",
            "\$(reboot)",
            "nginx:1.27 --privileged",
            "registry/../etc",
            "nginx:",
            "nginx:1.27\n--privileged",
            "",
        )) {
            assertTrue("image '$bad' should have been refused", rejected { container(image = bad) })
        }
        // A digest-pinned reference is accepted and kept verbatim.
        val digest = "ghcr.io/owner/app@sha256:" + "a".repeat(64)
        assertTrue(container(image = digest).runCommand().contains(digest))
    }

    @Test
    fun `port mappings are validated`() {
        assertEquals(
            listOf(PortMapping(8080, 80), PortMapping(8443, 443)),
            DeployPlans.parsePorts("8080:80, 8443:443"),
        )
        for (bad in listOf("8080", "0:80", "8080:0", "8080:70000", "a:b", "8080:80:90")) {
            assertTrue("ports '$bad' should have been refused", rejected { DeployPlans.parsePorts(bad) })
        }
        val many = (1..DeployPlans.MAX_PORTS + 1).joinToString(",") { "$it:$it" }
        assertTrue(rejected { DeployPlans.parsePorts(many) })
    }

    @Test
    fun `volumes must be absolute and may only be read-only`() {
        val mounts = DeployPlans.parseVolumes("/srv/app:/app\n~/data:/var/data:ro")
        assertEquals("/srv/app", mounts[0].hostPath)
        assertFalse(mounts[0].readOnly)
        assertTrue(mounts[1].readOnly)

        for (bad in listOf("relative:/app", "/srv/../etc:/etc", "/srv/app:relative", "/srv/app:/app:rw")) {
            assertTrue("volume '$bad' should have been refused", rejected { DeployPlans.parseVolumes(bad) })
        }
    }

    @Test
    fun `environment values stay on one line and keep their equals signs`() {
        val variables = DeployPlans.parseEnvironment("TOKEN=a=b=c\nDEBUG=1")
        assertEquals("a=b=c", variables[0].value)
        assertEquals("DEBUG", variables[1].key)
        for (bad in listOf("NOEQUALS", " bad = x", "KEY=line1\nBAD", "1KEY=x")) {
            assertTrue("env '$bad' should have been refused", rejected { DeployPlans.parseEnvironment(bad) })
        }
    }

    // ---------------------------------------------------------------- Kubernetes

    @Test
    fun `a reviewed workload plan names one context, namespace and resource`() {
        val plan = workload()
        assertTrue(plan.createCommand().startsWith("kubectl --context prod-cluster --namespace demo"))
        assertEquals(listOf(plan.createCommand(), plan.rolloutCommand()), plan.commands())
        assertTrue(plan.createCommand().endsWith("create -f -"))
        assertTrue(
            plan.conditionalDeleteCommand(kubernetesUid).endsWith(
                "delete --raw=/apis/apps/v1/namespaces/demo/deployments/anthracite-1a2b3c4d -f -",
            ),
        )
        assertTrue(plan.rollbackCommand().contains("rollout undo deployment/anthracite-1a2b3c4d"))
        assertTrue(plan.rolloutCommand().contains("rollout status deployment/anthracite-1a2b3c4d"))
        assertEquals("kubectl config current-context", plan.contextCommand())
        assertTrue(plan.permissionCommand("patch").endsWith("auth can-i patch deployment"))
        assertTrue(plan.permissionCommand("delete").endsWith("auth can-i delete deployment"))
        assertTrue(plan.permissionCommand("create").endsWith("auth can-i create deployment"))
        assertTrue(rejected { plan.permissionCommand("exec") })
    }

    @Test
    fun `a conditional delete refuses an injected identity`() {
        val plan = workload()
        for (bad in listOf("", "not-a-uid", "0f8f8d5e-1c2b-4a3c-9d7e-2b6f5a4c3d2", "0f8f8d5e; rm -rf /")) {
            assertTrue("uid '$bad' should have been refused", rejected { plan.conditionalDeleteCommand(bad) })
        }
        // The UID is the only variable part, and it is carried in the document the
        // cluster reads, never spliced into a shell word.
        assertTrue(plan.conditionalDeleteCommand(kubernetesUid).contains(""""preconditions":{"uid":"$kubernetesUid"}"""))
    }

    @Test
    fun `a statefulset delete uses its own API path`() {
        val plan = workload(kind = WorkloadKind.STATEFULSET)
        assertTrue(
            plan.conditionalDeleteCommand(kubernetesUid)
                .contains("/apis/apps/v1/namespaces/demo/statefulsets/anthracite-1a2b3c4d"),
        )
    }

    @Test
    fun `a workload cannot escape its context or namespace`() {
        for (bad in listOf("prod; rm -rf /", "", "prod cluster", "-n", "a/b")) {
            assertTrue("context '$bad' should have been refused", rejected { workload(context = bad) })
        }
        for (bad in listOf("demo; id", "", "Demo", "a/b", "-")) {
            assertTrue("namespace '$bad' should have been refused", rejected { workload(namespace = bad) })
        }
    }

    @Test
    fun `the manifest is generated as exactly one owned resource`() {
        val plan = workload(kind = WorkloadKind.STATEFULSET, replicas = 3, containerPort = 9090)
        val manifest = plan.manifest()
        assertEquals(1, Regex("^kind: ", RegexOption.MULTILINE).findAll(manifest).count())
        // One document: no `---` separator can hide a second resource.
        assertEquals(0, Regex("^---", RegexOption.MULTILINE).findAll(manifest).count())
        assertTrue(manifest.contains("kind: StatefulSet"))
        assertTrue(manifest.contains("  name: anthracite-1a2b3c4d\n"))
        assertTrue(manifest.contains("  namespace: demo\n"))
        assertTrue(manifest.contains("    anthracite.managed: \"1\"\n"))
        assertTrue(manifest.contains("  replicas: 3\n"))
        assertTrue(manifest.contains("            - containerPort: 9090\n"))
        assertTrue(manifest.contains("  serviceName: anthracite-1a2b3c4d\n"))
        // Every line is generated from a validated field: no free text survives.
        assertFalse(manifest.contains("%"))
    }

    @Test
    fun `hostile workload fields are refused before a manifest exists`() {
        for (bad in listOf(
            "nginx:1.27\nkind: Secret",
            "nginx; rm -rf /",
            "nginx:1.27 && id",
            "\$(reboot)",
            "",
        )) {
            assertTrue("image '$bad' should have been refused", rejected { workload(image = bad) })
        }
        assertTrue(rejected { workload(replicas = 0) })
        assertTrue(rejected { workload(replicas = DeployPlans.MAX_REPLICAS + 1) })
        assertTrue(rejected { workload(containerPort = 0) })
        assertTrue(rejected { workload(containerPort = 65_536) })
        // An identity is a UUID or nothing: a forged one is refused outright.
        assertTrue(rejected { workload(appliedUid = "not-a-uid") })
        assertTrue(rejected { workload(appliedUid = "00000000-0000-0000-0000-00000000000") })
    }

    // -------------------------------------------------------------------- native

    @Test
    fun `a native plan is a user unit with quoted arguments`() {
        val plan = DeployPlan.Native(
            name = name,
            workingDirectory = "/home/ada/project",
            executable = "./run.sh",
            arguments = listOf("--port", "8080", "a b"),
        )
        val command = plan.commands().single()
        assertTrue(command.startsWith("systemd-run --user --collect --unit=anthracite-1a2b3c4d"))
        assertTrue(command.contains("--working-directory=/home/ada/project"))
        assertTrue(command.endsWith("-- ./run.sh --port 8080 'a b'"))
        assertTrue(plan.statusCommand().contains("systemctl --user is-active anthracite-1a2b3c4d"))
        assertTrue(plan.warnings.any { it.contains("not a root service") })
    }

    @Test
    fun `a native plan refuses traversal and shell syntax`() {
        assertTrue(
            rejected {
                DeployPlan.Native(name, "relative/dir", "./run.sh", emptyList())
            },
        )
        assertTrue(
            rejected {
                DeployPlan.Native(name, "/home/ada/../../etc", "./run.sh", emptyList())
            },
        )
        for (bad in listOf("./run.sh; id", "./run.sh && id", "\$(id)", "./run\nid")) {
            assertTrue(
                "executable '$bad' should have been refused",
                rejected { DeployPlan.Native(name, "/home/ada", bad, emptyList()) },
            )
        }
        assertTrue(
            rejected {
                DeployPlan.Native(name, "/home/ada", "./run.sh", listOf("line1\nline2"))
            },
        )
    }

    @Test
    fun `a deployment name outside the managed grammar is refused by every plan`() {
        assertTrue(rejected { DeployPlan.Container("nginx", DeployCommands.Runtime.DOCKER, "nginx:1.27") })
        assertTrue(rejected { DeployPlan.Native("app", "/home/ada", "./run.sh", emptyList()) })
        assertTrue(rejected { DeployPlan.Workload("app", "ctx", "demo", WorkloadKind.DEPLOYMENT, "nginx:1.27") })
    }
}
