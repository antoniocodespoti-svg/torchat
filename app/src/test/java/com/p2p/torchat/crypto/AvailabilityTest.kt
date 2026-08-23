package com.p2p.torchat.crypto

import org.junit.Test
import java.security.Security

class AvailabilityTest {
    @Test
    fun listProviders() {
        Security.getProviders().forEach { p ->
            println("Provider: ${p.name}")
            p.services.forEach { s ->
                if (s.algorithm.contains("Ed25519")) {
                    println("  - Service: ${s.type} / ${s.algorithm}")
                }
            }
        }
    }
}
