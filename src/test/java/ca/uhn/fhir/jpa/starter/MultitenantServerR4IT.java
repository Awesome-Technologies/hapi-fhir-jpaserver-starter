package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.CacheControlDirective;
import ca.uhn.fhir.rest.client.interceptor.UrlTenantSelectionInterceptor;
import ca.uhn.fhir.rest.server.provider.ProviderConstants;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MultitenantServerR4IT {

  private static UrlTenantSelectionInterceptor ourClientTenantInterceptor;

  static {
    HapiProperties.forceReload();
    HapiProperties.setProperty(HapiProperties.DATASOURCE_URL, "jdbc:h2:mem:dbr4-mt");
    HapiProperties.setProperty(HapiProperties.FHIR_VERSION, "R4");
    HapiProperties.setProperty(HapiProperties.SUBSCRIPTION_WEBSOCKET_ENABLED, "true");
    HapiProperties.setProperty(HapiProperties.PARTITIONING_ENABLED, "true");
    HapiProperties.setProperty(HapiProperties.PARTITIONING_MULTITENANCY_ENABLED, "true");
    TestSetup.ourCtx = FhirContext.forR4();
  }

  @Test
  public void testCreateAndReadInTenantA() {
    TestSetup.ourLog.info("Base URL is: " + HapiProperties.getServerAddress());

    // Create tenant A
    ourClientTenantInterceptor.setTenantId("DEFAULT");
    TestSetup.ourClient
      .operation()
      .onServer()
      .named(ProviderConstants.PARTITION_MANAGEMENT_CREATE_PARTITION)
      .withParameter(Parameters.class, ProviderConstants.PARTITION_MANAGEMENT_PARTITION_ID, new IntegerType(1))
      .andParameter(ProviderConstants.PARTITION_MANAGEMENT_PARTITION_NAME, new CodeType("TENANT-A"))
      .execute();


    ourClientTenantInterceptor.setTenantId("TENANT-A");
    Patient pt = new Patient();
    pt.addName().setFamily("Family A");
    TestSetup.ourClient.create().resource(pt).execute().getId();

    Bundle searchResult = TestSetup.ourClient.search().forResource(Patient.class).returnBundle(Bundle.class).cacheControl(new CacheControlDirective().setNoCache(true)).execute();
    assertEquals(1, searchResult.getEntry().size());
    Patient pt2 = (Patient) searchResult.getEntry().get(0).getResource();
    assertEquals("Family A", pt2.getName().get(0).getFamily());
  }

  @Test
  public void testCreateAndReadInTenantB() {
    TestSetup.ourLog.info("Base URL is: " + HapiProperties.getServerAddress());

    // Create tenant A
    ourClientTenantInterceptor.setTenantId("DEFAULT");
    TestSetup.ourClient
      .operation()
      .onServer()
      .named(ProviderConstants.PARTITION_MANAGEMENT_CREATE_PARTITION)
      .withParameter(Parameters.class, ProviderConstants.PARTITION_MANAGEMENT_PARTITION_ID, new IntegerType(2))
      .andParameter(ProviderConstants.PARTITION_MANAGEMENT_PARTITION_NAME, new CodeType("TENANT-B"))
      .execute();


    ourClientTenantInterceptor.setTenantId("TENANT-B");
    Patient pt = new Patient();
    pt.addName().setFamily("Family B");
    TestSetup.ourClient.create().resource(pt).execute().getId();

    Bundle searchResult = TestSetup.ourClient.search().forResource(Patient.class).returnBundle(Bundle.class).cacheControl(new CacheControlDirective().setNoCache(true)).execute();
    assertEquals(1, searchResult.getEntry().size());
    Patient pt2 = (Patient) searchResult.getEntry().get(0).getResource();
    assertEquals("Family B", pt2.getName().get(0).getFamily());
  }

  @AfterAll
  public static void afterClass() throws Exception {
    TestSetup.ourServer.stop();
  }

  @BeforeAll
  public static void beforeClass() throws Exception {
    TestSetup.init();

    ourClientTenantInterceptor = new UrlTenantSelectionInterceptor();
    TestSetup.ourClient.registerInterceptor(ourClientTenantInterceptor);
  }

  public static void main(String[] theArgs) throws Exception {
    beforeClass();
  }
}
