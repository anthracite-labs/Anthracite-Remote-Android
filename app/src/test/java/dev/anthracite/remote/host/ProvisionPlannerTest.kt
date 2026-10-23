package dev.anthracite.remote.host

import dev.anthracite.remote.host.PlanAction.AlreadyPresent
import dev.anthracite.remote.host.PlanAction.Install
import dev.anthracite.remote.host.PlanAction.Unavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionPlannerTest {
    private fun facts(
        tools: Map<String, String> = mapOf("apt-get" to "/usr/bin/apt-get", "git" to "/usr/bin/git"),
        osId: String = "debian",
        version: String = "12",
        uid: Int = 1000,
        sudo: Boolean = true,
        diskKb: Long = 5_000_000,
        container: Boolean = false,
        workingDirectory: String = "/home/ada",
        machineId: String = "",
    ) = HostFacts(
        capturedAtMillis = 10,
        complete = true,
        osId = osId,
        osVersionId = version,
        osIdLike = emptyList(),
        osPretty = "$osId $version",
        kernel = "Linux",
        arch = "x86_64",
        kernelRelease = "6.1",
        uid = uid,
        user = "ada",
        primaryGroup = "ada",
        groups = listOf("ada"),
        home = "/home/ada",
        shell = "/bin/sh",
        workingDirectory = workingDirectory,
        cpuCount = 2,
        memoryTotalKb = 1_000_000,
        diskAvailableKb = diskKb,
        filesystem = "ext4",
        sudoInstalled = sudo,
        sudoNonInteractive = sudo,
        systemctlPresent = true,
        loginctlPresent = true,
        linger = null,
        containerDocker = container,
        containerHint = if (container) "docker" else "",
        pid1 = "systemd",
        tools = tools,
        kubeContexts = emptyList(),
        kubeCurrentContext = null,
        raw = if (machineId.isBlank()) emptyMap() else mapOf("machine_id" to machineId),
    )

    private fun request(vararg ids: String, acknowledge: Boolean = false) =
        BootstrapRequest(toolIds = ids.toList(), acknowledgeUnverified = acknowledge)

    @Test
    fun `already installed tools are not planned for installation`() {
        val plan = ProvisionPlanner.plan(facts(), request("git", "tmux"))
        assertEquals(1, plan.installActions.size)
        assertEquals("tmux", plan.installActions.single().tool.id)
        assertEquals(1, plan.actions.count { it is AlreadyPresent })
        assertEquals(listOf("tmux"), plan.steps.flatMap { it.packages })
    }

    @Test
    fun `an unknown tool is reported unavailable instead of being invented`() {
        val plan = ProvisionPlanner.plan(facts(), request("not-a-tool"))
        assertEquals(1, plan.actions.size)
        assertTrue(plan.actions.single() is Unavailable)
        assertTrue(plan.isNoOp)
    }

    @Test
    fun `every catalogue tool resolves to a package for every supported manager`() {
        // The catalogue is the only source of package names, so a manager with no
        // entry would be a silent gap. This test fails the build instead.
        for (tool in ProvisionCatalog.tools) {
            for (manager in PackageManagerKind.entries) {
                assertTrue(
                    "${tool.id} has no ${manager.id} package",
                    tool.packagesFor(manager).isNotEmpty(),
                )
            }
        }
    }

    @Test
    fun `every preset references only catalogued tools`() {
        for (profile in ProvisionCatalog.profiles) {
            assertTrue("${profile.id} has no tools", profile.toolIds.isNotEmpty())
            assertEquals(profile.toolIds.distinct(), profile.toolIds)
            for (id in profile.toolIds) {
                assertNotNull("${profile.id} references unknown tool $id", ProvisionCatalog.tool(id))
            }
        }
    }

    @Test
    fun `coding-agent CLIs are instructions, never native plans`() {
        assertTrue(ManualInstallers.entries.isNotEmpty())
        for (entry in ManualInstallers.entries) {
            assertTrue(entry.instructions.isNotBlank())
            assertTrue(entry.manualBecause.isNotBlank())
            // Nothing in the manual list may also be a silently executable catalog entry.
            assertEquals(null, ProvisionCatalog.tool(entry.id))
        }
    }

    @Test
    fun `an oversized selection is truncated with a warning instead of growing`() {
        val ids = (1..ProvisionPlanner.MAX_TOOLS + 5).map { "tool$it" }
        val plan = ProvisionPlanner.plan(facts(), request(*ids.toTypedArray()))
        assertEquals(ProvisionPlanner.MAX_TOOLS, plan.request.toolIds.size)
        assertTrue(plan.warnings.any { it.contains("first") })
        assertTrue(plan.actions.all { it is Unavailable })
    }

    @Test
    fun `steps are chunked and the digest covers exactly the steps file`() {
        val many = ProvisionCatalog.tools.map { it.id }
        val plan = ProvisionPlanner.plan(facts(tools = mapOf("apt-get" to "/usr/bin/apt-get")), request(*many.toTypedArray()))
        assertTrue(plan.steps.size >= 1)
        assertTrue(plan.steps.all { it.packages.size <= ProvisionPlanner.PACKAGES_PER_STEP })
        val bytes = ProvisionPlanner.stepsFileBytes(plan.steps)
        assertEquals(ProvisionPlanner.digestOf(bytes), plan.stepsDigest)
        assertEquals(64, plan.stepsDigest.length)
        assertTrue(String(bytes).lines().first().contains("\tinstall\t"))
    }

    @Test
    fun `passwordless sudo is required unless the user is root`() {
        val needsPassword = facts(sudo = false)
        val plan = ProvisionPlanner.plan(needsPassword, request("tmux"))
        val verdict = ProvisionPlanner.decide(plan, needsPassword)
        assertEquals(PlanDecision.NEEDS_INTERACTIVE_PRIVILEGE, verdict.decision)
        assertFalse(verdict.canLaunch)
        assertTrue(verdict.explanation.contains("never stores"))

        val asRoot = facts(uid = 0, sudo = false)
        // The unverified-distribution gate is covered by its own test; this one is
        // about privilege, so it starts from an acknowledged plan.
        val rootPlan = ProvisionPlanner.plan(asRoot, request("tmux", acknowledge = true))
        assertEquals(PlanDecision.ALLOWED, ProvisionPlanner.decide(rootPlan, asRoot).decision)
        assertFalse(rootPlan.requiresAdmin)
    }

    @Test
    fun `an unverified distribution needs an explicit acknowledgement`() {
        val host = facts(osId = "gentoo", version = "rolling")
        val plan = ProvisionPlanner.plan(host, request("tmux"))
        val verdict = ProvisionPlanner.decide(plan, host)
        assertEquals(PlanDecision.ALLOWED_UNVERIFIED, verdict.decision)
        assertTrue(verdict.canLaunch)

        val acknowledged = ProvisionPlanner.plan(host, request("tmux", acknowledge = true))
        assertEquals(PlanDecision.ALLOWED, ProvisionPlanner.decide(acknowledged, host).decision)
    }

    private fun evidence(
        capabilities: Set<ProvisionCapability>,
        osId: String = "debian",
        version: String = "12",
        arch: String = "x86_64",
    ) = DistributionEvidence(
        osId = osId,
        osVersion = version,
        arch = arch,
        image = "$osId:$version",
        imageDigest = "sha256:" + "a".repeat(64),
        testedAt = "2026-10-23",
        runUrl = "https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/1",
        capabilities = capabilities,
    )

    @Test
    fun `read-only discovery never certifies package provisioning`() {
        val host = facts(container = true)
        val discoveredOnly = listOf(
            evidence(setOf(ProvisionCapability.SSH_PTY, ProvisionCapability.OS_DISCOVERY)),
        )
        val plan = ProvisionPlanner.plan(host, request("tmux"), evidence = discoveredOnly)
        assertEquals(SupportTier.UNVERIFIED, plan.supportTier)
        assertTrue(plan.testedCapabilities.isEmpty())
        assertEquals(PlanDecision.ALLOWED_UNVERIFIED, ProvisionPlanner.decide(plan, host).decision)
        assertTrue(plan.warnings.any { it.contains("discovery alone") })
    }

    @Test
    fun `an image install result is honest but never authorises a silent run`() {
        val installed = listOf(
            evidence(
                setOf(
                    ProvisionCapability.PACKAGE_INSTALL,
                    ProvisionCapability.PACKAGE_VERIFY,
                    ProvisionCapability.CANCELLATION,
                    ProvisionCapability.CLEANUP,
                ),
            ),
        )
        val container = facts(container = true)
        val containerPlan = ProvisionPlanner.plan(container, request("tmux"), evidence = installed)
        assertEquals(SupportTier.TESTED_IN_IMAGE, containerPlan.supportTier)
        // The image is not this host: the acknowledgement is still required.
        assertEquals(PlanDecision.ALLOWED_UNVERIFIED, ProvisionPlanner.decide(containerPlan, container).decision)

        val realHost = facts(container = false)
        val hostPlan = ProvisionPlanner.plan(realHost, request("tmux"), evidence = installed)
        assertEquals(SupportTier.UNVERIFIED, hostPlan.supportTier)
        assertEquals(PlanDecision.ALLOWED_UNVERIFIED, ProvisionPlanner.decide(hostPlan, realHost).decision)

        val acknowledged = ProvisionPlanner.plan(
            realHost,
            request("tmux", acknowledge = true),
            evidence = installed,
        )
        assertEquals(PlanDecision.ALLOWED, ProvisionPlanner.decide(acknowledged, realHost).decision)
    }

    @Test
    fun `evidence for another release or architecture does not transfer`() {
        val installed = listOf(
            evidence(
                setOf(ProvisionCapability.PACKAGE_INSTALL, ProvisionCapability.PACKAGE_VERIFY),
                version = "11",
            ),
        )
        assertEquals(
            SupportTier.UNVERIFIED,
            ProvisionPlanner.plan(facts(version = "12"), request("tmux"), evidence = installed).supportTier,
        )
        val arm = listOf(
            evidence(
                setOf(ProvisionCapability.PACKAGE_INSTALL, ProvisionCapability.PACKAGE_VERIFY),
                arch = "aarch64",
            ),
        )
        assertEquals(
            SupportTier.UNVERIFIED,
            ProvisionPlanner.plan(facts(), request("tmux"), evidence = arm).supportTier,
        )
    }

    @Test
    fun `only evidence keyed to this exact host skips the acknowledgement`() {
        val host = facts(machineId = "0123456789abcdef0123456789abcdef")
        val verifiedHosts = mapOf(
            host.machineIdentity to setOf(
                ProvisionCapability.PACKAGE_INSTALL,
                ProvisionCapability.PACKAGE_VERIFY,
            ),
        )
        val plan = ProvisionPlanner.plan(host, request("tmux"), hostEvidence = verifiedHosts)
        assertEquals(SupportTier.VERIFIED_HOST, plan.supportTier)
        assertEquals(PlanDecision.ALLOWED, ProvisionPlanner.decide(plan, host).decision)

        // The same record must not transfer to a host with another identity.
        val other = facts(machineId = "fedcba9876543210fedcba9876543210")
        val otherPlan = ProvisionPlanner.plan(other, request("tmux"), hostEvidence = verifiedHosts)
        assertEquals(SupportTier.UNVERIFIED, otherPlan.supportTier)
        assertEquals(PlanDecision.ALLOWED_UNVERIFIED, ProvisionPlanner.decide(otherPlan, other).decision)
    }

    @Test
    fun `a host without a machine identity can never match host evidence`() {
        val host = facts(machineId = "")
        val plan = ProvisionPlanner.plan(
            host,
            request("tmux"),
            hostEvidence = mapOf("" to setOf(ProvisionCapability.PACKAGE_INSTALL)),
        )
        assertEquals(SupportTier.UNVERIFIED, plan.supportTier)
    }

    @Test
    fun `low disk blocks the plan before anything runs`() {
        val host = facts(diskKb = 1024)
        val plan = ProvisionPlanner.plan(host, request("tmux"))
        assertEquals(PlanDecision.BLOCKED_INSUFFICIENT_DISK, ProvisionPlanner.decide(plan, host).decision)
    }

    @Test
    fun `nothing to do is blocked rather than reported as success`() {
        val host = facts(tools = mapOf("apt-get" to "/usr/bin/apt-get", "tmux" to "/usr/bin/tmux"))
        val plan = ProvisionPlanner.plan(host, request("tmux"))
        val verdict = ProvisionPlanner.decide(plan, host)
        assertEquals(PlanDecision.BLOCKED_NOTHING_TO_DO, verdict.decision)
        assertFalse(verdict.canLaunch)
    }

    @Test
    fun `preview commands use the detected native manager`() {
        val host = facts(tools = mapOf("dnf" to "/usr/bin/dnf"))
        val plan = ProvisionPlanner.plan(host, request("tmux"))
        val commands = ProvisionPlanner.previewCommands(plan)
        assertEquals(listOf("sudo dnf install -y tmux"), commands)
    }

    @Test
    fun `the plan file carries the digest the runner verifies`() {
        val plan = ProvisionPlanner.plan(facts(), request("tmux"))
        val header = String(ProvisionPlanner.planFileBytes(plan, "0123456789abcdef"))
        assertTrue(header.contains("job_id=0123456789abcdef"))
        assertTrue(header.contains("plan_hash=${plan.stepsDigest}"))
        assertTrue(header.contains("manager=apt"))
        assertNotNull(ProvisionCatalog.profile("base"))
    }
}
