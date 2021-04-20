package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.AdditionalRequestHeadersInterceptor;
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor;

import ca.uhn.fhir.test.utilities.JettyUtil;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;


public class TestSetup {

  public static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(MultitenantServerR4IT.class);
  public static IGenericClient ourClient;
  public static FhirContext ourCtx;
  public static int ourPort;
  public static Server ourServer;

  @Autowired
  static AppProperties appProperties;

  public static void init() throws Exception {
    ourPort = 8080;
    String path = Paths.get("").toAbsolutePath().toString();
    String token = "eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJvdHV4YVV4ZzM4Skh4ODNqcjEyd1hobnZwSHkxRXRQMEVXbE9adEJNbjljIn0.eyJleHAiOjE2MDgwNTI5NzgsImlhdCI6MTYwODA1MjY3OCwianRpIjoiNzI3Y2JkNTEtY2ZkOC00NTRiLTg2MjItMjA3ZDk2NDQ3MDkyIiwiaXNzIjoiaHR0cHM6Ly9hdXRoLmFtcC5pbnN0aXR1dGUvYXV0aC9yZWFsbXMvSEFQSUZISVIiLCJhdWQiOiJhY2NvdW50Iiwic3ViIjoiMTkzMjIwNTItMTQ1MC00NDNiLWI5MzMtYzJjY2RlMjZmMmU2IiwidHlwIjoiQmVhcmVyIiwiYXpwIjoiaGFwaWZoaXItY2xpZW50Iiwic2Vzc2lvbl9zdGF0ZSI6ImVjYTAyMDliLTYyM2YtNDY0ZS1hYzIxLTMwM2RmMWRhYWUyYSIsImFjciI6IjEiLCJyZWFsbV9hY2Nlc3MiOnsicm9sZXMiOlsiQWRtaW5pc3RyYXRvciIsIm9mZmxpbmVfYWNjZXNzIiwidW1hX2F1dGhvcml6YXRpb24iLCJPcmdhbml6YXRpb24vNCJdfSwicmVzb3VyY2VfYWNjZXNzIjp7ImFjY291bnQiOnsicm9sZXMiOlsibWFuYWdlLWFjY291bnQiLCJtYW5hZ2UtYWNjb3VudC1saW5rcyIsInZpZXctcHJvZmlsZSJdfX0sInNjb3BlIjoicHJvZmlsZSBvcGVuaWQgZW1haWwiLCJlbWFpbF92ZXJpZmllZCI6ZmFsc2UsInByZWZlcnJlZF91c2VybmFtZSI6ImFkbWluIiwiZmhpck9yZ2FuaXphdGlvbiI6IkFkbWluaXN0cmF0b3IifQ.IcpfR8DIZrdoJL5ISDjxiHjK3I6ueyTCmbePKDKYpaqMb7pb6y6W_P-fnjReEXbfBQXr2NaD_YB9bloo3vUnC0KGNZT8-qIB_ueAtyUOdtokEucxNkYldj6MEV_ledURm1yAfLoiN2NJ6yhpUMk0igFS62v3zqkAQmp-JhRw11a5VlbndZLkpdVeQ3R3djpHSKq-_eVk-PqqmpSeBSQSWx6YNVIzj1sSukF_Uj0-Uo_J5IdewlkHQsZHTnvp0NrfLvxa7__DkGNdAf11c0bZnPLJw_DKDSoKu0kc8hRZ_7oLkuaeyA4yAfaTzUPRP9vBlhYDU1wHiE2BpvEdkHbERQ";
    Map<String, List<String>> header = new HashMap<>();
    List<String> header_value = new ArrayList<>();
    header_value.add("BEARER " + token);
    header.put("Authorization", header_value);

    ourLog.info("Project base path is: {}", path);

    ourServer = new Server(0);

    WebAppContext webAppContext = new WebAppContext();
    webAppContext.setContextPath("/hapi-fhir-jpaserver");
    webAppContext.setDisplayName("HAPI FHIR");
    webAppContext.setDescriptor(path + "/src/main/webapp/WEB-INF/web.xml");
    webAppContext.setResourceBase(path + "/target/hapi-fhir-jpaserver-starter");
    webAppContext.setParentLoaderPriority(true);

    ourServer.setHandler(webAppContext);
    ourServer.start();

    ourPort = JettyUtil.getPortForStartedServer(ourServer);

    ourCtx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
    ourCtx.getRestfulClientFactory().setSocketTimeout(1200 * 1000);
    String ourServerBase = appProperties.getServer_address();
    ourServerBase = "http://localhost:" + ourPort + "/hapi-fhir-jpaserver/fhir/";

    ourClient = ourCtx.newRestfulGenericClient(ourServerBase);
    ourClient.registerInterceptor(new LoggingInterceptor(true));
    ourClient.registerInterceptor(new AdditionalRequestHeadersInterceptor(header));
  }
}
