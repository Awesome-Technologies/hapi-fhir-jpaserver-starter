package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.dstu2.resource.Patient;
import org.hl7.fhir.instance.model.api.IIdType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExampleServerDstu2IT {

  static {
    HapiProperties.forceReload();
    HapiProperties.setProperty(HapiProperties.FHIR_VERSION, "DSTU2");
    HapiProperties.setProperty(HapiProperties.DATASOURCE_URL, "jdbc:h2:mem:dbr2");
    TestSetup.ourCtx = FhirContext.forDstu2();
  }

  @AfterAll
  public static void afterClass() throws Exception {
    TestSetup.ourServer.stop();
  }

  @BeforeAll
  public static void beforeClass() throws Exception {
    TestSetup.init();
  }

  public static void main(String[] theArgs) throws Exception {
    beforeClass();
  }

  @Test
  public void testCreateAndRead() {
    TestSetup.ourLog.info("Base URL is: " + HapiProperties.getServerAddress());
    String methodName = "testCreateResourceConditional";

    Patient pt = new Patient();
    pt.addName().addFamily(methodName);
    IIdType id = TestSetup.ourClient.create().resource(pt).execute().getId();

    Patient pt2 = TestSetup.ourClient.read().resource(Patient.class).withId(id).execute();
    assertEquals(methodName, pt2.getName().get(0).getFamily().get(0).getValue());
  }
}
