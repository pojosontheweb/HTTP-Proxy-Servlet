package org.mitre.sdmiley.httpproxy;

import com.pojosontheweb.selenium.ManagedDriverJunit4TestBase;
import org.junit.Test;

import static com.pojosontheweb.selenium.Findrs.*;
import static org.openqa.selenium.By.*;

public class ProxyTest extends ManagedDriverJunit4TestBase {

    private String url = System.getProperty("proxy.url", "http://localhost:8080/smiley-proxy");

    @Test
    public void testSimple() {
        getWebDriver().get(url);
        findr()
            .elem(id("main-title"))
            .where(textEquals("Hi Smiley !"))
            .eval();
    }

}
