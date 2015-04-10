package org.mitre.sdmiley.httpproxy;

import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.StringWriter;
import java.net.URL;


public class ProxyTest {

    private String url = System.getProperty("proxy.url", "http://localhost:8080/smiley-proxy");

    @Test
    public void testSimple() throws Exception {
        URL u = new URL(url);
        StringWriter sw = new StringWriter();
        IOUtils.copy(u.openStream(), sw, "utf-8");
        String html = sw.toString();
        Assert.assertTrue(html.contains("Hi Smiley !"));
    }

}
