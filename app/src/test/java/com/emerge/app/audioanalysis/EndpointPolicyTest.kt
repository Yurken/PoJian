package com.emerge.app.audioanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointPolicyTest {
    @Test fun productionAllowsHttpsAndPreservesItsBasePath() {
        assertEquals("https://api.example.com/v1", EndpointPolicy.validate(" https://api.example.com/v1/ ", false))
        assertEquals("HTTPS://Example.COM:8443/v1", EndpointPolicy.validate("HTTPS://Example.COM:8443/v1", false))
        assertEquals("https://[::1]:8443", EndpointPolicy.validate("https://[::1]:8443/", false))
    }

    @Test fun releaseNeverPermitsPlainHttpIncludingLocalDevices() {
        listOf("http://localhost:8000", "http://127.0.0.1:8000", "http://192.168.1.10:8000", "http://api.example.com")
            .forEach { endpoint -> assertThrows(endpoint, IllegalArgumentException::class.java) {
                EndpointPolicy.validate(endpoint, false)
            } }
    }

    @Test fun debugHttpIsLimitedToLoopbackAndValidPrivateSubnetAddresses() {
        listOf("http://localhost:8000", "http://LOCALHOST:8000", "http://127.0.0.2:8000", "http://[::1]:8000",
            "http://10.0.0.1", "http://192.168.255.255", "http://172.16.0.1", "http://172.31.255.254")
            .forEach { endpoint -> assertEquals(endpoint, EndpointPolicy.validate(endpoint, true)) }
        listOf("http://api.example.com", "http://8.8.8.8", "http://172.15.0.1", "http://172.32.0.1",
            "http://192.169.1.1", "http://10.0.0.999", "http://localhost.example.com")
            .forEach { endpoint -> assertThrows(endpoint, IllegalArgumentException::class.java) {
                EndpointPolicy.validate(endpoint, true)
            } }
    }

    @Test fun credentialsQueryFragmentsMissingHostAndInvalidPortsAreRejected() {
        listOf("https://user:secret@api.example.com", "https://api.example.com?token=secret",
            "https://api.example.com#part", "https:///missing-host", "https://api.example.com:0",
            "https://api.example.com:65536", "file:///tmp/service", "not a url")
            .forEach { endpoint -> assertThrows(endpoint, IllegalArgumentException::class.java) {
                EndpointPolicy.validate(endpoint, true)
            } }
    }
}
