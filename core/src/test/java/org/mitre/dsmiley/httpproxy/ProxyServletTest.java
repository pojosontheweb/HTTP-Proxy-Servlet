package org.mitre.dsmiley.httpproxy;

import com.meterware.httpunit.GetMethodWebRequest;
import com.meterware.httpunit.PostMethodWebRequest;
import com.meterware.httpunit.WebRequest;
import com.meterware.httpunit.WebResponse;
import com.meterware.servletunit.ServletRunner;
import com.meterware.servletunit.ServletUnitClient;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.RequestLine;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.localserver.LocalTestServer;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * @author David Smiley - dsmiley@mitre.org
 */
public class ProxyServletTest
{
  private static final Log log = LogFactory.getLog(ProxyServletTest.class);

  /**
   * From Apache httpcomponents/httpclient. Note httpunit has a similar thing called PseudoServlet but it is
   * not as good since you can't even make it echo the request back.
   */
  protected LocalTestServer localTestServer;

  /** From Meterware httpunit. */
  protected ServletRunner servletRunner;
  private ServletUnitClient sc;

  protected String targetBaseUri;
  protected String sourceBaseUri;

  protected String servletName = ProxyServlet.class.getName();
  protected String servletPath = "/proxyMe";

  @Before
  public void setUp() throws Exception {
    localTestServer = new LocalTestServer(null, null);
    localTestServer.start();
    localTestServer.register("/targetPath*", new RequestInfoHandler());//matches /targetPath and /targetPath/blahblah

    servletRunner = new ServletRunner();

    Properties servletProps = new Properties();
    servletProps.setProperty("http.protocol.handle-redirects", "false");
    servletProps.setProperty(ProxyServlet.P_LOG, "true");
    servletProps.setProperty(ProxyServlet.P_FORWARDEDFOR, "true");
    setUpServlet(servletProps);

    sc = servletRunner.newClient();
    sc.getClientProperties().setAutoRedirect(false);//don't want httpunit itself to redirect

  }

  protected void setUpServlet(Properties servletProps) {
    servletProps.putAll(servletProps);
    targetBaseUri = "http://localhost:"+localTestServer.getServiceAddress().getPort()+"/targetPath";
    servletProps.setProperty("targetUri", targetBaseUri);
    servletRunner.registerServlet(servletPath + "/*", servletName, servletProps);//also matches /proxyMe (no path info)
    sourceBaseUri = "http://localhost/proxyMe";//localhost:0 is hard-coded in ServletUnitHttpRequest
  }

  @After
  public void tearDown() throws Exception {
   servletRunner.shutDown();
   localTestServer.stop();
  }

  //note: we don't include fragments:   "/p?#f","/p?#" because
  //  user agents aren't supposed to send them. HttpComponents has behaved
  //  differently on sending them vs not sending them.
  private static String[] testUrlSuffixes = new String[]{
          "","/pathInfo","?q=v","/p?q=v",
          "/p?query=note:Leitbild",//colon  Issue#4
          "/p?id=p%20i", "/p%20i" // encoded space in param then in path
  };
  //TODO add "/p//doubleslash//f.txt" however HTTPUnit gets in the way. See issue #24

  @Test
  public void testGet() throws Exception {
    for (String urlSuffix : testUrlSuffixes) {
      execAssert(makeGetMethodRequest(sourceBaseUri + urlSuffix));
    }
  }

  @Test
  public void testPost() throws Exception {
    for (String urlSuffix : testUrlSuffixes) {
      execAndAssert(makePostMethodRequest(sourceBaseUri + urlSuffix));
    }
  }

  @Test
  public void testRedirect() throws IOException, SAXException {
    localTestServer.register("/targetPath*",new HttpRequestHandler()
    {
      public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        response.setHeader(HttpHeaders.LOCATION,request.getFirstHeader("xxTarget").getValue());
        response.setStatusCode(HttpStatus.SC_MOVED_TEMPORARILY);
      }
    });//matches /targetPath and /targetPath/blahblah
    GetMethodWebRequest request = makeGetMethodRequest(sourceBaseUri);
    assertRedirect(request, "/dummy", "/dummy");//TODO represents a bug to fix
    assertRedirect(request, targetBaseUri+"/dummy?a=b", sourceBaseUri+"/dummy?a=b");
  }

  private void assertRedirect(GetMethodWebRequest request, String origRedirect, String resultRedirect) throws IOException, SAXException {
    request.setHeaderField("xxTarget", origRedirect);
    WebResponse rsp = sc.getResponse( request );

    assertEquals(HttpStatus.SC_MOVED_TEMPORARILY,rsp.getResponseCode());
    assertEquals("",rsp.getText());
    String gotLocation = rsp.getHeaderField(HttpHeaders.LOCATION);
    assertEquals(resultRedirect, gotLocation);
  }

  @Test
  public void testSendFile() throws Exception {
    //TODO test with url parameters (i.e. a=b); but HttpUnit is faulty so we can't
    final PostMethodWebRequest request = new PostMethodWebRequest(
            rewriteMakeMethodUrl("http://localhost/proxyMe"), true);//true: mime encoded
    InputStream data = new ByteArrayInputStream("testFileData".getBytes("UTF-8"));
    request.selectFile("fileNameParam", "fileName", data, "text/plain");
    WebResponse rsp = execAndAssert(request);
    assertTrue(rsp.getText().contains("Content-Type: multipart/form-data; boundary="));
  }

  @Test
  public void testProxyWithUnescapedChars() throws Exception {
    execAssert(makeGetMethodRequest(sourceBaseUri + "?fq={!f=field}"), "?fq=%7B!f=field%7D");//has squiggly brackets
    execAssert(makeGetMethodRequest(sourceBaseUri + "?fq=%7B!f=field%7D"));//already escaped; don't escape twice
  }

  /** http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html */
  @Test
  public void testHopByHopHeadersOnSource() throws Exception {
    //"Proxy-Authenticate" is a hop-by-hop header
    final String HEADER = "Proxy-Authenticate";
    localTestServer.register("/targetPath*", new RequestInfoHandler() {
      public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        assertNull(request.getFirstHeader(HEADER));
        response.setHeader(HEADER, "from-server");
        super.handle(request, response, context);
      }
    });

    GetMethodWebRequest req = makeGetMethodRequest(sourceBaseUri);
    req.getHeaders().put(HEADER, "from-client");
    WebResponse rsp = execAndAssert(req, "");
    assertNull(rsp.getHeaderField(HEADER));
  }

  @Test
  public void testWithExistingXForwardedFor() throws Exception {
    final String HEADER = "X-Forwarded-For";

    localTestServer.register("/targetPath*", new RequestInfoHandler() {
      public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        Header xForwardedForHeader = request.getFirstHeader(HEADER);
        assertEquals("192.168.1.1, 127.0.0.1", xForwardedForHeader.getValue());
        super.handle(request, response, context);
      }
    });

    GetMethodWebRequest req = makeGetMethodRequest(sourceBaseUri);
    req.setHeaderField(HEADER, "192.168.1.1");
    WebResponse rsp = execAndAssert(req, "");
  }

  @Test
  public void testEnabledXForwardedFor() throws Exception {
    final String HEADER = "X-Forwarded-For";

    localTestServer.register("/targetPath*", new RequestInfoHandler() {
      public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        Header xForwardedForHeader = request.getFirstHeader(HEADER);
        assertEquals("127.0.0.1", xForwardedForHeader.getValue());
        super.handle(request, response, context);
      }
    });

    GetMethodWebRequest req = makeGetMethodRequest(sourceBaseUri);
    WebResponse rsp = execAndAssert(req, "");
  }

  @Test
  public void testSetCookie() throws Exception {
    final String HEADER = "Set-Cookie";
    localTestServer.register("/targetPath*", new RequestInfoHandler() {
      public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        response.setHeader(HEADER, "JSESSIONID=1234; Path=/proxy/path/that/we/dont/want; Expires=Wed, 13 Jan 2021 22:23:01 GMT; Domain=.foo.bar.com; HttpOnly");
        super.handle(request, response, context);
      }
    });

    GetMethodWebRequest req = makeGetMethodRequest(sourceBaseUri);
    WebResponse rsp = execAndAssert(req, "");
    // note httpunit doesn't set all cookie fields, ignores max-agent, secure, etc.
    assertEquals("!Proxy!" + servletName + "JSESSIONID=1234;path=" + servletPath, rsp.getHeaderField(HEADER));
  }

  @Test
  public void testSetCookie2() throws Exception {
    final String HEADER = "Set-Cookie2";
    localTestServer.register("/targetPath*", new RequestInfoHandler() {
      public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        response.setHeader(HEADER, "JSESSIONID=1234; Path=/proxy/path/that/we/dont/want; Max-Age=3600; Domain=.foo.bar.com; Secure");
        super.handle(request, response, context);
      }
    });

    GetMethodWebRequest req = makeGetMethodRequest(sourceBaseUri);
    WebResponse rsp = execAndAssert(req, "");
    // note httpunit doesn't set all cookie fields, ignores max-agent, secure, etc.
    // also doesn't support more than one header of same name so I can't test this working on two cookies
    assertEquals("!Proxy!" + servletName + "JSESSIONID=1234;path=" + servletPath, rsp.getHeaderField("Set-Cookie"));
  }

  @Test
  public void testSetCookieHttpOnly() throws Exception { //See GH #50
    final String HEADER = "Set-Cookie";
    localTestServer.register("/targetPath*", new RequestInfoHandler() {
      public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        response.setHeader(HEADER, "JSESSIONID=1234; Path=/proxy/path/that/we/dont/want/; HttpOnly");
        super.handle(request, response, context);
      }
    });

    GetMethodWebRequest req = makeGetMethodRequest(sourceBaseUri);
    WebResponse rsp = execAndAssert(req, "");
    // note httpunit doesn't set all cookie fields, ignores max-agent, secure, etc.
    assertEquals("!Proxy!" + servletName + "JSESSIONID=1234;path=" + servletPath, rsp.getHeaderField(HEADER));
  }

  @Test
  public void testSendCookiesToProxy() throws Exception {
    final StringBuffer captureCookieValue = new StringBuffer();
    final String HEADER = "Cookie";
    localTestServer.register("/targetPath*", new RequestInfoHandler() {
      public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        captureCookieValue.append(request.getHeaders(HEADER)[0].getValue());
        super.handle(request, response, context);
      }
    });

    GetMethodWebRequest req = makeGetMethodRequest(sourceBaseUri);
    req.setHeaderField(HEADER,
            "LOCALCOOKIE=ABC; " +
            "!Proxy!" + servletName + "JSESSIONID=1234; " +
            "!Proxy!" + servletName + "COOKIE2=567; " +
            "LOCALCOOKIELAST=ABCD");
    WebResponse rsp = execAndAssert(req, "");
    assertEquals("JSESSIONID=1234; COOKIE2=567", captureCookieValue.toString());
  }

  /**
   * If we're proxying a remote service that tries to set cookies, we need to make sure the cookies are not captured
   * by the httpclient in the ProxyServlet, otherwise later requests from ALL users will all access the remote proxy
   * with the same cookie as the first user
   */
  @Test
  public void testMultipleRequestsWithDiffCookies() throws Exception {
    final AtomicInteger requestCounter = new AtomicInteger(1);
    final StringBuffer captureCookieValue = new StringBuffer();
    localTestServer.register("/targetPath*", new RequestInfoHandler() {
      public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
        // there shouldn't be a cookie sent since each user request in this test is logging in for the first time
        if (request.getFirstHeader("Cookie") != null) {
            captureCookieValue.append(request.getFirstHeader("Cookie"));
        } else {
            response.setHeader("Set-Cookie", "JSESSIONID=USER_" + requestCounter.getAndIncrement() + "_SESSION");
        }
        super.handle(request, response, context);
      }
    });

    // user one logs in for the first time to a proxied web service
    GetMethodWebRequest req = makeGetMethodRequest(sourceBaseUri);
    WebResponse rsp = execAndAssert(req, "");
    assertEquals("", captureCookieValue.toString());
    assertEquals("USER_1_SESSION", sc.getCookieJar().getCookie("!Proxy!" + servletName + "JSESSIONID").getValue());

    // user two logs in for the first time to a proxied web service
    sc.clearContents(); // clear httpunit cookies since we want to login as a different user
    req = makeGetMethodRequest(sourceBaseUri);
    rsp = execAndAssert(req, "");
    assertEquals("", captureCookieValue.toString());
    assertEquals("USER_2_SESSION", sc.getCookieJar().getCookie("!Proxy!" + servletName + "JSESSIONID").getValue());
  }

  private WebResponse execAssert(GetMethodWebRequest request, String expectedUri) throws Exception {
    return execAndAssert(request, expectedUri);
  }

  private WebResponse execAssert(GetMethodWebRequest request) throws Exception {
    return execAndAssert(request,null);
  }

  private WebResponse execAndAssert(PostMethodWebRequest request) throws Exception {
    request.setParameter("abc","ABC");

    WebResponse rsp = execAndAssert(request, null);

    assertTrue(rsp.getText().contains("ABC"));
    return rsp;
  }

  private WebResponse execAndAssert(WebRequest request, String expectedUri) throws Exception {
    WebResponse rsp = sc.getResponse( request );

    assertEquals(HttpStatus.SC_OK,rsp.getResponseCode());
    //HttpUnit doesn't pass the message; not a big deal
    //assertEquals("TESTREASON",rsp.getResponseMessage());
    final String text = rsp.getText();
    assertTrue(text.startsWith("REQUESTLINE:"));

    String expectedTargetUri = getExpectedTargetUri(request, expectedUri);
    String expectedFirstLine = "REQUESTLINE: "+(request instanceof GetMethodWebRequest ? "GET" : "POST");
    expectedFirstLine += " " + expectedTargetUri + " HTTP/1.1";

    String firstTextLine = text.substring(0,text.indexOf(System.getProperty("line.separator")));

    assertEquals(expectedFirstLine, firstTextLine);

    return rsp;
  }

  protected String getExpectedTargetUri(WebRequest request, String expectedUri) throws MalformedURLException, URISyntaxException {
    if (expectedUri == null)
      expectedUri = request.getURL().toString().substring(sourceBaseUri.length());
    return new URI(this.targetBaseUri).getPath() + expectedUri;
  }

  private GetMethodWebRequest makeGetMethodRequest(final String url) {
    return makeMethodRequest(url,GetMethodWebRequest.class);
  }

  private PostMethodWebRequest makePostMethodRequest(final String url) {
    return makeMethodRequest(url,PostMethodWebRequest.class);
  }

  //Fixes problems in HttpUnit in which I can't specify the query string via the url. I don't want to use
  // setParam on a get request.
  @SuppressWarnings({"unchecked"})
  private <M> M makeMethodRequest(String incomingUrl, Class<M> clazz) {
    log.info("Making request to url "+incomingUrl);
    final String url = rewriteMakeMethodUrl(incomingUrl);
    String urlNoQuery;
    final String queryString;
    int qIdx = url.indexOf('?');
    if (qIdx == -1) {
      urlNoQuery = url;
      queryString = null;
    } else {
      urlNoQuery = url.substring(0,qIdx);
      queryString = url.substring(qIdx + 1);

    }
    //WARNING: Ugly! Groovy could do this better.
    if (clazz == PostMethodWebRequest.class) {
      return (M) new PostMethodWebRequest(urlNoQuery) {
        @Override
        public String getQueryString() {
          return queryString;
        }
        @Override
        protected String getURLString() {
          return url;
        }
      };
    } else if (clazz == GetMethodWebRequest.class) {
      return (M) new GetMethodWebRequest(urlNoQuery) {
        @Override
        public String getQueryString() {
          return queryString;
        }
        @Override
        protected String getURLString() {
          return url;
        }
      };
    }
    throw new IllegalArgumentException(clazz.toString());
  }

  //subclass extended
  protected String rewriteMakeMethodUrl(String url) {
    return url;
  }

  /**
   * Writes all information about the request back to the response.
   */
  private static class RequestInfoHandler implements HttpRequestHandler
  {

    public void handle(HttpRequest request, HttpResponse response, HttpContext context) throws HttpException, IOException {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      PrintWriter pw = new PrintWriter(baos,false);
      final RequestLine rl = request.getRequestLine();
      pw.println("REQUESTLINE: " + rl);

      for (Header header : request.getAllHeaders()) {
        pw.println(header.getName() + ": " + header.getValue());
      }
      pw.println("BODY: (below)");
      pw.flush();//done with pw now

      if (request instanceof HttpEntityEnclosingRequest) {
        HttpEntityEnclosingRequest enclosingRequest = (HttpEntityEnclosingRequest) request;
        HttpEntity entity = enclosingRequest.getEntity();
        byte[] body = EntityUtils.toByteArray(entity);
        baos.write(body);
      }

      response.setStatusCode(200);
      response.setReasonPhrase("TESTREASON");
      response.setEntity(new ByteArrayEntity(baos.toByteArray()));
    }
  }
}
