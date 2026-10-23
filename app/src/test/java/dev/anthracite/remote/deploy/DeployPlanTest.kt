package dev.anthracite.remote.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deployment creation is only as safe as the values that reach the host command.
 * These tests exercise that grammar directly: hostile names, images, ports,
 * volumes, environment values and manifests must be refused before a plan exists,
 * and an accepted plan must render exactly the commands that were previewed.
 */
class DeployPlanTest {
    private fun rejected(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (expected: IllegalArgumentException) {
        true
    }

    private val name = "anthracite-1a2b3c4d"

    private fun container(
        image: String = "nginx:1.27",
        ports: List<PortMapping> = emptyList(),
        volumes: List<VolumeMount> = emptyList(),
        environment: List<EnvVar> = emptyList(),
        runtime: DeployCommands.Runtime = DeployCommands.Runtime.DOCKER,
    ) = DeployPlan.Container(name, runtime, image, ports, volumes, environment)

    private val manifest = """
        apiVersion: apps/v1
        kind: Deployment
        metadata:
          name: anthracite-1a2b3c4d
          labels:
            app.kubernetes.io/managed-by: anthracite
        spec:
          replicas: 1
          selector:
            matchLabels:
              app: anthracite-1a2b3c4d
          template:
            metadata:
              labels:
                app: anthracite-1a2b3c4d
            spec:
              containers:
                - name: app
                  image: nginx:1.27
    """.trimIndent()

    private fun workload(
        body: String = manifest,
        kind: WorkloadKind = WorkloadKind.DEPLOYMENT,
        context: String = "prod-cluster",
        namespace: String = "demo",
    ) = DeployPlan.Workload(name, context, namespace, kind, body)

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
        assertEquals(listOf("docker pull nginx:1.27", plan.runCommand()), commands)
        val run = plan.runCommand()
        assertTrue(run.startsWith("docker run --detach --name anthracite-1a2b3c4d"))
        assertTrue(run.contains("--label anthracite.managed=1"))
        assertTrue(run.contains("--publish 8080:80"))
        assertTrue(run.contains("--volume /srv/data:/var/lib/data:ro"))
        // A value with a space stays one token: it cannot become another flag.
        assertTrue(run.contains("--env 'GREETING=hello world'"))
        assertTrue(run.endsWith(" nginx:1.27"))
        assertTrue(plan.warnings.any { it.contains("root") })
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
        assertTrue(plan.applyCommand().startsWith("kubectl --context prod-cluster --namespace demo"))
        assertTrue(plan.applyCommand().endsWith("apply -f -"))
        assertTrue(plan.deleteCommand().endsWith("delete deployment/anthracite-1a2b3c4d --ignore-not-found --wait=false"))
        assertTrue(plan.rollbackCommand().contains("rollout undo deployment/anthracite-1a2b3c4d"))
        assertTrue(plan.rolloutCommand().contains("rollout status deployment/anthracite-1a2b3c4d"))
        assertEquals("kubectl config current-context", plan.contextCommand())
        assertTrue(plan.permissionCommand().endsWith("auth can-i patch deployment"))
    }

    @Test
    fun `a workload cannot escape its context or namespace`() {
        for (bad in listOf("prod; rm -rf /", "", "prod cluster", "-n", "a/b")) {
            assertTrue("context '$bad' should have been refused", rejected { workload(context = bad) })
        }
        for (bad in listOf("demo; id", "", "Demo", "a/b", "-") ) {
            assertTrue("namespace '$bad' should have been refused", rejected { workload(namespace = bad) })
        }
    }

    @Test
    fun `cluster-scoped and misnamed manifests are refused`() {
        val secret = """
            apiVersion: v1
            kind: Secret
            metadata:
              name: anthracite-1a2b3c4d
        """.trimIndent()
        assertTrue(rejected { workload(body = secret) })

        val namespace = """
            apiVersion: v1
            kind: Namespace
            metadata:
              name: anthracite-1a2b3c4d
        """.trimIndent()
        assertTrue(rejected { workload(body = namespace) })

        val wrongName = manifest.replace("name: anthracite-1a2b3c4d", "name: production-api")
        assertTrue(rejected { workload(body = wrongName) })

        val twoDocuments = manifest + "\n---\n" + manifest
        assertTrue(rejected { workload(body = twoDocuments) })

        val missingKind = manifest.replace("kind: Deployment\n", "")
        assertTrue(rejected { workload(body = missingKind) })

        // A StatefulSet manifest is not accepted as a Deployment plan.
        val stateful = manifest.replace("kind: Deployment", "kind: StatefulSet")
        assertTrue(rejected { workload(body = stateful) })
        assertTrue(!rejected { workload(body = stateful, kind = WorkloadKind.STATEFULSET) })

        val huge = manifest + "\n# " + "x".repeat(DeployPlans.MAX_MANIFEST_BYTES)
        assertTrue(rejected { workload(body = huge) })
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
        assertTrue(rejected { DeployPlan.Workload("app", "ctx", "demo", WorkloadKind.DEPLOYMENT, manifest) })
    }
}
