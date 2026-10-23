package com.riverguard.v2;

import static org.junit.Assert.*;
import org.junit.Test;

public class LocalAddressTest {
    @Test public void permitsPrivateWifiRangesAndPorts() {
        for (String value : new String[]{"http://192.168.1.20:8501/", "http://10.217.38.223:8501/api/ui/exports?kind=csv", "https://172.16.0.1", "http://172.31.255.254:8000"}) assertTrue(value, LocalAddress.isPrivateUrl(value));
    }
    @Test public void rejectsPublicLoopbackInvalidAndCredentialDestinations() {
        for (String value : new String[]{"https://example.com", "http://127.0.0.1:8501", "http://localhost:8501", "http://172.15.1.2", "http://172.32.1.2", "http://10.999.1.2", "http://user:password@192.168.1.2", "file:///tmp/a", "javascript:alert(1)", "http://192.168.1.2:0", "http://192.168.1.2:99999", "http://10.evil.example"}) assertFalse(value, LocalAddress.isPrivateUrl(value));
    }
}
