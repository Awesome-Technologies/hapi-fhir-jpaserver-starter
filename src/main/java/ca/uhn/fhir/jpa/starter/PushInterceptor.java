package ca.uhn.fhir.jpa.starter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.Validate;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.CommunicationRequest.CommunicationRequestStatus;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Endpoint;
import org.hl7.fhir.r4.model.Endpoint.EndpointStatus;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.ServiceRequest.ServiceRequestStatus;
import org.json.JSONArray;
import org.json.JSONObject;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;

/*
 * %%
 * Copyright (C) 2020 Awesome Technologies Innovationslabor GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

/**
 * Server interceptor which creates a push notification for each created or
 * updated ServiceRequest
 */
@Interceptor
public class PushInterceptor {

  private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(PushInterceptor.class);
  private static final String PUSH_APP_ID_NORMAL = "care.amp.intensiv";
  private static final String PUSH_APP_ID_VOIP = "care.amp.intensiv.voip";

  private final DaoRegistry myDaoRegistry;
  private final String myPushUrl;

  /**
   * Constructor for push notification interceptor
   *
   * @param theDaoRegistry The DAO registry (must not be null)
   */
  public PushInterceptor(DaoRegistry theDaoRegistry, String thePushUrl) {
    super();

    Validate.notNull(theDaoRegistry, "theDaoRegistry must not be null");
    myDaoRegistry = theDaoRegistry;
    myPushUrl = thePushUrl;
  }

  @Hook(Pointcut.SERVER_PROCESSING_COMPLETED_NORMALLY)
  public void processingCompletedNormally(ServletRequestDetails theRequestDetails) {

    //operationType
    if (theRequestDetails.getRestOperationType() == null) {
      ourLog.error("RestOperationType is null");
      return;
    }

    String myOperationType = theRequestDetails.getRestOperationType().getCode();
    // only handle create,update
    if (!myOperationType.startsWith("create") && !myOperationType.startsWith("update")) return;
    myOperationType = myOperationType.split("/")[0];

    //resourceName
    String myResourceName = theRequestDetails.getResourceName();
    if (myResourceName == null) return;

    if (myResourceName.startsWith("ServiceRequest")) handleServiceRequests(theRequestDetails, myOperationType);
    else if (myResourceName.startsWith("CommunicationRequest")) handleCommunicationRequests(theRequestDetails, myOperationType);
  }

  private void handleServiceRequests(ServletRequestDetails theRequestDetails, String theOperationType) {
    final IBaseResource serviceRequest = theRequestDetails.getResource();
    if (serviceRequest == null || !(serviceRequest instanceof ServiceRequest)) {
      ourLog.warn("ServiceRequest is not readable");
      return;
    }
    final ServiceRequest myServiceRequest = (ServiceRequest) serviceRequest;

    // check if status is active
    final ServiceRequestStatus status = myServiceRequest.getStatus();
    if (!status.getDisplay().toLowerCase().equals("active")) {
      ourLog.info("ServiceRequest status is not active but " + status.getDisplay().toLowerCase());
      return;
    }

    // read ServiceRequest id
    final String serviceRequestId = myServiceRequest.getId();
    final String requestType = getReferenceType(serviceRequestId);
    if (!requestType.equals("ServiceRequest")) {
      ourLog.warn("Reference is not a ServiceRequest but: " + requestType);
      return;
    }

    // read patient id
    final Reference patient = myServiceRequest.getSubject();
    if (patient == null) {
      ourLog.warn("No subject");
      return;
    }
    final String patientId = patient.getReference();
    final String referenceType = getReferenceType(patientId);
    if (!referenceType.equals("Patient")) {
      ourLog.warn("Subject is not a Patient but: " + referenceType);
      return;
    }

    // find requester organization
    final Reference requester = myServiceRequest.getRequester();
    if (requester == null) {
      ourLog.warn("No requester");
      return;
    }
    final String senderId = requester.getReference();
    final String requesterType = getReferenceType(senderId);
    if (!requesterType.equals("Organization")) {
      ourLog.warn("Requester is not an Organization but: " + requesterType);
      return;
    }

    // find recipient organization
    final Reference performer = myServiceRequest.getPerformerFirstRep();
    if (performer == null) {
      ourLog.warn("No performer set");
      return;
    }
    // read endpoints from Organization
    final List<String> pushTokens = getPushTokens(performer);

    // send push notification to endpoints
    sendPushNotification(pushTokens, theOperationType, senderId, patientId, serviceRequestId, PUSH_APP_ID_NORMAL);
  }

  private void handleCommunicationRequests(ServletRequestDetails theRequestDetails, String myOperationType) {
    final IBaseResource communicationRequest = theRequestDetails.getResource();
    if (communicationRequest == null || !(communicationRequest instanceof CommunicationRequest)) {
      ourLog.warn("CommunicationRequest is not readable");
      return;
    }
    final CommunicationRequest myCommunicationRequest = (CommunicationRequest) communicationRequest;

    // check if status is active
    final CommunicationRequestStatus status = myCommunicationRequest.getStatus();
    if (!status.getDisplay().toLowerCase().equals("active")) {
      ourLog.info("CommunicationRequest status is not active but " + status.getDisplay().toLowerCase());
      return;
    }

    // read CommunicationRequest id
    final String communicationRequestId = myCommunicationRequest.getId();
    final String requestType = getReferenceType(communicationRequestId);
    if (!requestType.equals("CommunicationRequest")) {
      ourLog.warn("Reference is not a CommunicationRequest but: " + requestType);
      return;
    }

    // read patient id
    final Reference patient = myCommunicationRequest.getSubject();
    if (patient == null) {
      ourLog.warn("No subject");
      return;
    }
    final String patientId = patient.getReference();
    final String patientType = getReferenceType(patientId);
    if (!patientType.equals("Patient")) {
      ourLog.warn("Subject is not a Patient but: " + patientType);
      return;
    }

    // find requester organization
    final Reference requester = myCommunicationRequest.getRequester();
    if (requester == null) {
        ourLog.warn("No requester");
        return;
    }
    final String senderId = requester.getReference();
    final String requesterType = getReferenceType(senderId);
    if (!requesterType.equals("Organization")) {
      ourLog.warn("Requester is not an Organization but: " + requesterType);
      return;
    }

    // find recipient organization
    final Reference recipient = myCommunicationRequest.getRecipientFirstRep();
    if (recipient == null) {
      ourLog.warn("No performer set");
      return;
    }
    // read endpoints from Organization
    final List<String> pushTokens = getPushTokens(recipient);

    // for newly created CommunicationRequests send a voip push
    String app_id = myOperationType.equals("create") ? PUSH_APP_ID_VOIP : PUSH_APP_ID_NORMAL;

    // send a push notification via Sygnal to APNS
    sendPushNotification(pushTokens, myOperationType, senderId, patientId, communicationRequestId, app_id);
  }

  private String getReferenceType(String reference) {
    return reference.split("/")[0];
  }

  private List<String> getPushTokens(Reference organization) {
    List<String> pushTokens = new ArrayList<String>();
    final String organizationId = organization.getReference();
    final String referenceType = getReferenceType(organizationId);
    if (!referenceType.equals("Organization")) {
      ourLog.warn("reference is not an Organization but: " + referenceType);
      return pushTokens;
    }
    IFhirResourceDao daoOrganization = myDaoRegistry.getResourceDao("Organization");
    IBaseResource theOrganization = daoOrganization.read(new IdDt(organizationId));
    if (!(theOrganization instanceof Organization)) {
      ourLog.warn("reference is not an Organization");
      return pushTokens;
    }
    final List<Reference> endpointList = ((Organization) theOrganization).getEndpoint();

    // find endpoints of organization
    IFhirResourceDao daoEndpoint = myDaoRegistry.getResourceDao("Endpoint");
    for (Reference ref : endpointList) {
      final String endpointId = ref.getReference();
      final String endpointType = getReferenceType(endpointId);
      if (!endpointType.equals("Endpoint")) {
        ourLog.warn("Reference is not an Endpoint but: " + endpointType);
        return pushTokens;
      }
      final IBaseResource endpoint = daoEndpoint.read(new IdDt(endpointId));
      if (!(endpoint instanceof Endpoint)) {
          ourLog.warn("Reference is not an Endpoint");
          return pushTokens;
      }
      final Endpoint myEndpoint = (Endpoint) endpoint;
      EndpointStatus myStatus = myEndpoint.getStatus();
      // ignore non-active endpoints
      if (myStatus.toCode().toLowerCase() != "active") {
        continue;
      }

      // we store the push tokens as ContactPoints
      for (ContactPoint cp : myEndpoint.getContact()) {
        JSONObject json = new JSONObject(cp.getValue());
        pushTokens.add(json.getString("push_token"));
        ourLog.info("Add push token: " + json.getString("push_token"));
      }
    }
    return pushTokens;
  }

  // send a push notification via Sygnal to APNS
  private void sendPushNotification(List<String> pushTokens, String type, String senderId, String patientId, String requestId, String appId) {
    try {
      URL url = new URL(myPushUrl);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setDoOutput(true);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");

      // build content
      JSONArray devicelist = new JSONArray();

      for (String pt : pushTokens) {
        JSONObject device = new JSONObject();
        device.put("app_id", appId);
        device.put("pushkey", pt);
        devicelist.put(device);
      }

      JSONObject notification = new JSONObject();
      notification.put("sender", senderId);
      notification.put("type", type);
      notification.put("request", requestId);
      notification.put("patient", patientId);
      notification.put("devices", devicelist);

      JSONObject content = new JSONObject();
      content.put("notification", notification);

      OutputStream os = conn.getOutputStream();
      os.write(content.toString().getBytes());
      os.flush();

      if (conn.getResponseCode() != HttpURLConnection.HTTP_OK && conn.getResponseCode() != HttpURLConnection.HTTP_BAD_REQUEST) {
        throw new RuntimeException("Failed : HTTP error code : " + conn.getResponseCode());
      }

      ourLog.info("Output from push server .... \n");
      BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));
      for (String output = br.readLine(); output != null; output = br.readLine()) {
        ourLog.info(output);
      }

      conn.disconnect();

    } catch (MalformedURLException e) {
        e.printStackTrace();
    } catch (IOException e) {
        e.printStackTrace();
    }

  }

}
