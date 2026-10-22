package dev.anthracite.remote.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployParserTest {
    @Test
    fun `docker json lines are parsed one container at a time`() {
        val output = """
            {"ID":"3f2a1b","Names":"web","Image":"nginx:1","State":"running","Status":"Up 2 hours","Ports":"0.0.0.0:80->80/tcp"}
            {"ID":"9c8b7a","Names":"db","Image":"postgres:16","State":"exited","Status":"Exited (0) 1 hour ago","Ports":""}
            not json at all
            {"Image":"missing-id"}
        """.trimIndent()
        val (containers, unparsed) = parseDockerPs(output)
        assertEquals(2, containers.size)
        assertEquals(1, unparsed)
        assertEquals("web", containers[0].name)
        assertTrue(containers[0].running)
        assertEquals("0.0.0.0:80->80/tcp", containers[0].ports)
        assertFalse(containers[1].running)
    }

    @Test
    fun `podman array output is parsed and ports are summarised`() {
        val output = """
            [{"Id":"abc123","Names":["api","api-alias"],"Image":"api:dev","State":"running",
              "Status":"Up 5 minutes","Ports":[{"host_port":8080}]},
             {"Id":"def456","Image":"worker:dev","State":"created","Status":"Created"}]
        """.trimIndent()
        val (containers, unparsed) = parsePodmanPs(output)
        assertEquals(0, unparsed)
        assertEquals(2, containers.size)
        assertEquals("api,api-alias", containers[0].name)
        assertEquals("1", containers[0].ports)
        assertEquals("def456", containers[1].name.take(12))
    }

    @Test
    fun `malformed podman output is reported, not silently empty`() {
        val (containers, unparsed) = parsePodmanPs("{}")
        assertTrue(containers.isEmpty())
        assertEquals(1, unparsed)
    }

    @Test
    fun `kubernetes pods carry readiness, restarts and node`() {
        val output = """
            {"items":[
              {"metadata":{"name":"web-0","namespace":"prod"},
               "spec":{"nodeName":"node-a","containers":[{"name":"web"},{"name":"sidecar"}]},
               "status":{"phase":"Running","containerStatuses":[
                 {"ready":true,"restartCount":2},{"ready":false,"restartCount":1}]}},
              {"metadata":{"name":"job-1"},"status":{"phase":"Succeeded"}}
            ]}
        """.trimIndent()
        val (pods, error) = parseKubePods(output)
        assertNull(error)
        assertEquals(2, pods.size)
        assertEquals("web-0", pods[0].name)
        assertEquals("prod", pods[0].namespace)
        assertEquals(1, pods[0].readyContainers)
        assertEquals(2, pods[0].totalContainers)
        assertEquals(3, pods[0].restarts)
        assertEquals("node-a", pods[0].node)
        assertFalse(pods[0].ready)
        assertEquals("default", pods[1].namespace)
    }

    @Test
    fun `unreadable kubernetes output explains itself`() {
        assertEquals("unreadable JSON", parseKubePods("not json").second)
        assertEquals("no items in response", parseKubePods("{}").second)
    }

    @Test
    fun `container actions come from a fixed verb table`() {
        assertEquals("docker start abc123", DeployCommands.dockerAction(
            "abc123",
            DeployCommands.Action.START,
            DeployCommands.Runtime.DOCKER,
        ))
        assertEquals("podman stop abc123", DeployCommands.dockerAction(
            "abc123",
            DeployCommands.Action.STOP,
            DeployCommands.Runtime.PODMAN,
        ))
        assertFalse(DeployCommands.Action.STOP.destructive)
        assertThrows(IllegalArgumentException::class.java) {
            DeployCommands.dockerAction("abc;reboot", DeployCommands.Action.STOP, DeployCommands.Runtime.DOCKER)
        }
    }

    @Test
    fun `log requests clamp the line count`() {
        assertTrue(DeployCommands.dockerLogs("abc123", 5000).contains("--tail 1000"))
        assertTrue(DeployCommands.dockerLogs("abc123", 0).contains("--tail 1"))
        assertTrue(DeployCommands.podmanLogs("abc123").contains("podman logs"))
    }

    @Test
    fun `kubernetes commands always name context and namespace`() {
        val command = DeployCommands.kubePods("prod-cluster", "team-a")
        assertTrue(command.contains("--context prod-cluster"))
        assertTrue(command.contains("--namespace team-a"))
        assertTrue(command.contains("get pods"))

        assertThrows(IllegalArgumentException::class.java) { DeployCommands.kubePods("prod; rm -rf /", "team-a") }
        assertThrows(IllegalArgumentException::class.java) { DeployCommands.kubePods("prod", "Team_A") }

        val delete = DeployCommands.kubeDeletePod("prod", "default", "web-0")
        assertTrue(delete.contains("delete pod web-0"))
        assertTrue(delete.contains("--wait=false"))
    }

    @Test
    fun `the docker warning states that socket access is root-equivalent`() {
        assertTrue(DeployCommands.Runtime.DOCKER.warning.contains("root"))
        assertTrue(DeployCommands.Runtime.PODMAN.warning.contains("rootless"))
    }
}
