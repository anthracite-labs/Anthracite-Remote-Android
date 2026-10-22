package dev.anthracite.remote.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostFactsParserTest {
    private val debianProbe = """
        probe_version=1
        os_id=debian
        os_version_id=12
        os_id_like=
        os_pretty=Debian GNU/Linux 12 (bookworm)
        kernel=Linux
        arch=x86_64
        kernel_release=6.1.0-18-amd64
        uid=1000
        user=ada
        group=ada
        groups=ada sudo
        home=/home/ada
        shell=/bin/bash
        pwd=/home/ada/project
        cpu_count=4
        mem_total_kb=8000000
        disk_avail_kb=52428800
        filesystem=ext4
        sudo_present=yes
        sudo_noninteractive=yes
        systemctl_present=yes
        loginctl_present=yes
        linger=no
        container_docker=no
        container_hint=
        pid1=systemd
        tool.git=/usr/bin/git
        tool.tmux=/usr/bin/tmux
        tool.apt-get=/usr/bin/apt-get
        tool.kubectl=/usr/local/bin/kubectl
        kube_current=prod
        kube_contexts=prod,staging,
        done=1
    """.trimIndent()

    private fun parse(text: String = debianProbe): HostFacts = HostFactsParser.parse(text, 1_700_000_000_000)

    @Test
    fun `parses a complete probe`() {
        val facts = parse()
        assertTrue(facts.complete)
        assertEquals("debian", facts.osId)
        assertEquals("Debian GNU/Linux 12 (bookworm)", facts.displayName)
        assertEquals(4, facts.cpuCount)
        assertEquals(1000, facts.uid)
        assertEquals(listOf("ada", "sudo"), facts.groups)
        assertEquals("/home/ada/project", facts.workingDirectory)
        assertEquals(false, facts.linger)
        assertEquals(listOf("prod", "staging"), facts.kubeContexts)
        assertEquals("prod", facts.kubeCurrentContext)
        assertEquals(PackageManagerKind.APT, facts.packageManager)
        assertEquals(Supervision.SYSTEMD, facts.supervision)
        assertFalse(facts.containerized)
    }

    @Test
    fun `missing tool lines mean the tool is absent, not unknown`() {
        val facts = parse()
        assertTrue(facts.hasGit)
        assertTrue(facts.hasTmux)
        assertFalse(facts.tools.containsKey("docker"))
        assertEquals(CapabilityState.UNSUPPORTED, facts.capabilities().docker)
        assertEquals(CapabilityState.AVAILABLE, facts.capabilities().kubernetes)
    }

    @Test
    fun `truncated probe is reported as incomplete`() {
        val facts = parse(debianProbe.substringBefore("done=1"))
        assertFalse(facts.complete)
    }

    @Test
    fun `a container cannot claim durable logout sessions`() {
        val facts = parse(
            debianProbe
                .replace("linger=no", "linger=yes")
                .replace("container_docker=no", "container_docker=yes"),
        )
        val durability = facts.durability()
        assertEquals(CapabilityState.AVAILABLE, durability.disconnected)
        assertEquals(CapabilityState.UNKNOWN, durability.logout)
        assertEquals(CapabilityState.UNSUPPORTED, durability.reboot)
        assertTrue(durability.detail.contains("container"))
    }

    @Test
    fun `without tmux nothing is promised to survive a dropped channel`() {
        val facts = parse(debianProbe.replace("tool.tmux=/usr/bin/tmux\n", ""))
        val durability = facts.durability()
        assertEquals(CapabilityState.UNSUPPORTED, durability.disconnected)
        assertEquals(CapabilityState.UNSUPPORTED, durability.logout)
        assertTrue(durability.detail.contains("tmux is not installed"))
    }

    @Test
    fun `a docker binary alone is detected but not proven usable`() {
        val facts = parse(debianProbe + "\ntool.docker=/usr/bin/docker")
        assertEquals(CapabilityState.DETECTED_UNUSABLE, facts.capabilities().docker)
        assertTrue(facts.capabilities().detail.any { it.contains("root") })
    }

    @Test
    fun `no package manager means provisioning is unsupported`() {
        val facts = parse(
            debianProbe
                .replace("tool.apt-get=/usr/bin/apt-get\n", "")
                .replace("os_id=debian", "os_id=plan9"),
        )
        assertNull(facts.packageManager)
        assertEquals(CapabilityState.UNSUPPORTED, facts.capabilities().provisioning)
        // Provisioning and host Git are separate capabilities: git is still on PATH.
        assertEquals(CapabilityState.AVAILABLE, facts.capabilities().hostGit)
    }

    @Test
    fun `malformed lines and unknown keys are ignored`() {
        val facts = parse("$debianProbe\nnot-a-pair\n=value\nbad key=value\ntool.=x")
        assertEquals("debian", facts.osId)
        assertTrue(facts.complete)
        assertFalse(facts.tools.containsKey(""))
    }

    @Test
    fun `an unrecognised distribution is reported as unknown family`() {
        val facts = parse("os_id=void\nos_version_id=rolling\nos_id_like=\n")
        assertEquals(EnvironmentFamily.UNKNOWN, facts.family)
        assertFalse(facts.complete)
    }
}
