package com.emerge.app.ui

import org.junit.Assert.*
import org.junit.Test

class ServiceConnectionUiPolicyTest {
    @Test fun connectionUiRejectsHttpEvenForLocalDevelopmentAddresses() {
        listOf("http://localhost:8000", "http://127.0.0.1:8000", "http://192.168.1.8", "http://example.org")
            .forEach { assertEquals("仅支持 HTTPS", connectionTestEndpointError(it)) }
    }

    @Test fun credentialsAndQueriesCannotBeEmbeddedIntoTheServiceAddress() {
        listOf("https://name:secret@example.org", "https://example.org?token=secret", "https://example.org#secret",
            "https:///missing-host", "https://example.org:70000")
            .forEach { assertNotNull(connectionTestEndpointError(it)) }
    }

    @Test fun existingHttpsGatewayAndCustomHttpsPathRemainValid() {
        assertNull(connectionTestEndpointError("https://xiaoyan.net.cn/pojian"))
        assertNull(connectionTestEndpointError("  https://example.org:8443/service/  "))
    }
}
