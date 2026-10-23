package dev.anthracite.remote.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wizard the user actually drives, without the Android UI.
 *
 * The name is chosen when the wizard is created — before the first field is
 * shown — and every plan it produces carries that exact name. A workload is
 * described by typed fields, so the reviewed manifest and the applied manifest
 * are the same document: there is no free text to disagree with.
 */
class DeployWizardTest {
    private fun rejected(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (expected: IllegalArgumentException) {
        true
    }

    @Test
    fun `the name exists before the first prompt and every plan uses it`() {
        val wizard = DeployWizard()
        assertTrue(DeployPlans.isManagedName(wizard.name))

        // The user's path: kind, image, replicas, port.
        val plan = wizard.workload(
            kind = WorkloadKind.DEPLOYMENT,
            context = "prod-cluster",
            namespace = "demo",
            image = "nginx:1.27",
            replicas = "2",
            containerPort = "8080",
        )
        assertEquals(wizard.name, plan.name)
        assertTrue(plan.manifest().contains("  name: ${wizard.name}\n"))
        assertTrue(plan.warnings.any { it.contains(wizard.name) || it.contains("re-checked") || it.contains("re-read") })
    }

    @Test
    fun `a container created through the wizard keeps its reviewed name`() {
        val wizard = DeployWizard()
        val plan = wizard.container(
            runtime = DeployCommands.Runtime.DOCKER,
            image = " nginx:1.27 ",
            ports = "8080:80",
            volumes = "",
            environment = "TOKEN=abc123",
        )
        assertEquals(wizard.name, plan.name)
        assertTrue(plan.runCommand().contains("--name ${wizard.name}"))
        assertFalse(plan.commands().any { it.contains("abc123") })
    }

    @Test
    fun `the wizard refuses a name outside the managed grammar`() {
        assertTrue(rejected { DeployWizard("nginx") })
        assertTrue(rejected { DeployWizard("anthracite-") })
        assertTrue(rejected { DeployWizard("anthracite-a;rm") })
    }

    @Test
    fun `empty numeric fields keep the documented defaults`() {
        assertEquals(1, DeployWizard.parseReplicas(""))
        assertEquals(8080, DeployWizard.parseContainerPort(""))
        assertEquals(3, DeployWizard.parseReplicas(" 3 "))
        assertEquals(8443, DeployWizard.parseContainerPort("8443"))
    }

    @Test
    fun `out of range or non numeric fields are refused`() {
        for (bad in listOf("0", "-1", "11", "two", "1.5")) {
            assertTrue("replicas '$bad'", rejected { DeployWizard.parseReplicas(bad) })
        }
        for (bad in listOf("0", "65536", "-80", "http")) {
            assertTrue("port '$bad'", rejected { DeployWizard.parseContainerPort(bad) })
        }
    }

    @Test
    fun `the reviewed text never contains a secret and always names the object`() {
        val wizard = DeployWizard()
        val secret = "hunter2-do-not-print"
        val plan = wizard.container(
            runtime = DeployCommands.Runtime.PODMAN,
            image = "nginx:1.27",
            ports = "",
            volumes = "",
            environment = "PASSWORD=$secret",
        )
        val review = DeployPlanReview.detail(
            plan = plan,
            review = "${plan.name} will be created. These are the exact commands:",
            manifestHeader = "Manifest, generated from the fields you entered:",
            environmentHeader = "Environment, values never displayed:",
        )
        assertTrue(review.contains(plan.name))
        assertTrue(review.contains("PASSWORD=***"))
        assertFalse(review.contains(secret))
        assertFalse(plan.commands().any { it.contains(secret) })
        assertFalse(plan.envFileRemoveCommand().contains(secret))
    }
}
