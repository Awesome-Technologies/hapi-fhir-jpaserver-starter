package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.CacheControlDirective;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.api.MethodOutcome;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Subscription;
import org.hl7.fhir.r5.model.SubscriptionTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static ca.uhn.fhir.util.TestUtil.waitForSize;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExampleServerR5IT {

  static {
    HapiProperties.forceReload();
    HapiProperties.setProperty(HapiProperties.DATASOURCE_URL, "jdbc:h2:mem:dbr5");
    HapiProperties.setProperty(HapiProperties.FHIR_VERSION, "R5");
    HapiProperties.setProperty(HapiProperties.SUBSCRIPTION_WEBSOCKET_ENABLED, "true");
    TestSetup.ourCtx = FhirContext.forR5();
  }

  @Test
  public void testCreateAndRead() {
    TestSetup.ourLog.info("Base URL is: " + HapiProperties.getServerAddress());
    String methodName = "testCreateResourceConditional";

    Patient pt = new Patient();
    pt.addName().setFamily(methodName);
    IIdType id = TestSetup.ourClient.create().resource(pt).execute().getId();

    Patient pt2 = TestSetup.ourClient.read().resource(Patient.class).withId(id).execute();
    assertEquals(methodName, pt2.getName().get(0).getFamily());
  }

  @Test
  public void testWebsocketSubscription() throws Exception {

    /*
     * Create topic
     */
    SubscriptionTopic topic = new SubscriptionTopic();
    topic.getResourceTrigger().getQueryCriteria().setCurrent("Observation?status=final");

    /*
     * Create subscription
     */
    Subscription subscription = new Subscription();
    subscription.getTopic().setResource(topic);
    subscription.setReason("Monitor new neonatal function (note, age will be determined by the monitor)");
    subscription.setStatus(Enumerations.SubscriptionState.REQUESTED);
    subscription.getChannelType()
      .setSystem("http://terminology.hl7.org/CodeSystem/subscription-channel-type")
      .setCode("websocket");
    subscription.setContentType("application/json");

    MethodOutcome methodOutcome = TestSetup.ourClient.create().resource(subscription).execute();
    IIdType mySubscriptionId = methodOutcome.getId();

    // Wait for the subscription to be activated
    waitForSize(1, () -> TestSetup.ourClient.search().forResource(Subscription.class).where(Subscription.STATUS.exactly().code("active")).cacheControl(new CacheControlDirective().setNoCache(true)).returnBundle(Bundle.class).execute().getEntry().size());

    /*
     * Attach websocket
     */

    WebSocketClient myWebSocketClient = new WebSocketClient();
    SocketImplementation mySocketImplementation = new SocketImplementation(mySubscriptionId.getIdPart(), EncodingEnum.JSON);

    myWebSocketClient.start();
    URI echoUri = new URI("ws://localhost:" + TestSetup.ourPort + "/hapi-fhir-jpaserver/websocket");
    ClientUpgradeRequest request = new ClientUpgradeRequest();
    TestSetup.ourLog.info("Connecting to : {}", echoUri);
    Future<Session> connection = myWebSocketClient.connect(mySocketImplementation, echoUri, request);
    Session session = connection.get(2, TimeUnit.SECONDS);

    TestSetup.ourLog.info("Connected to WS: {}", session.isOpen());

    /*
     * Create a matching resource
     */
    Observation obs = new Observation();
    obs.setStatus(Enumerations.ObservationStatus.FINAL);
    TestSetup.ourClient.create().resource(obs).execute();

    /*
     * Ensure that we receive a ping on the websocket
     */
    await().until(()->mySocketImplementation.myPingCount > 0);

    /*
     * Clean up
     */
    TestSetup.ourClient.delete().resourceById(mySubscriptionId).execute();
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
}
