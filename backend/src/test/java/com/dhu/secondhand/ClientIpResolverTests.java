package com.dhu.secondhand;

import com.dhu.secondhand.common.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class ClientIpResolverTests {
    @Test
    void forwardingHeadersAreIgnoredByDefaultAndForUntrustedPeers() {
        var request = request("192.0.2.10", "198.51.100.1");
        assertEquals("192.0.2.10", new ClientIpResolver("").resolve(request));
        assertEquals("192.0.2.10", new ClientIpResolver("127.0.0.1").resolve(request));
    }

    @Test
    void trustedChainsStopAtTheFirstUntrustedHop() {
        var resolver = new ClientIpResolver("127.0.0.1,192.0.2.10");
        assertEquals("198.51.100.2", resolver.resolve(request("127.0.0.1", "198.51.100.2,192.0.2.10")));
        // 攻击者提供的左侧地址不能覆盖实际客户端（第一个不可信节点）。
        assertEquals("198.51.100.2", resolver.resolve(request("127.0.0.1", "203.0.113.99,198.51.100.2")));
    }

    @Test
    void malformedMissingAndDuplicateHeadersFallBackToThePeer() {
        var resolver = new ClientIpResolver("127.0.0.1");
        assertEquals("127.0.0.1", resolver.resolve(request("127.0.0.1", null)));
        for (String header : new String[]{"", "unknown", "localhost", "999.1.1.1", "127.1", "198.51.100.1,",
                "198.51.100.1:1234", "1".repeat(4097)}) {
            assertEquals("127.0.0.1", resolver.resolve(request("127.0.0.1", header)), header);
        }
        var duplicate = request("127.0.0.1", "198.51.100.1");
        duplicate.addHeader("X-Forwarded-For", "198.51.100.2");
        assertEquals("127.0.0.1", resolver.resolve(duplicate));
    }

    @Test
    void ipv6AddressesAreCanonicalizedAndConfigurationRejectsHostnames() {
        var resolver = new ClientIpResolver("::1");
        assertEquals("2001:db8:0:0:0:0:0:1", resolver.resolve(request("0:0:0:0:0:0:0:1", "2001:db8::1")));
        assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver("localhost"));
        assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver("192.0.2.0/24"));
    }

    private MockHttpServletRequest request(String peer, String forwarded) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
        return request;
    }
}
