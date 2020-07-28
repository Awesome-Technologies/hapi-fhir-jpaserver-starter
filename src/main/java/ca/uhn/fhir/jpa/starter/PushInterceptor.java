package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import org.hl7.fhir.instance.model.api.IBaseResource;
import ca.uhn.fhir.model.primitive.IdDt;
import java.util.ArrayList;

import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.ServiceRequest.ServiceRequestStatus;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.CommunicationRequest.CommunicationRequestStatus;
import org.hl7.fhir.r4.model.Endpoint;
import org.hl7.fhir.r4.model.Endpoint.EndpointStatus;
import org.hl7.fhir.r4.model.ContactPoint;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import org.json.JSONObject;
import org.json.JSONArray;

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
  private final DaoRegistry myDaoRegistry;
  private final String myPushUrl;

  private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(PushInterceptor.class);

  private Logger myLogger = ourLog;
  private String myMessageFormat = "${operationType} - ${idOrResourceName}";


  /**
   * Constructor for push notification interceptor
   *
   * @param theDaoRegistry The DAO registry (must not be null)
   */
  public PushInterceptor(DaoRegistry theDaoRegistry, String pushUrl) {
    super();

    Validate.notNull(theDaoRegistry, "theDaoRegistry must not be null");
    myDaoRegistry = theDaoRegistry;

    myPushUrl = pushUrl;

    myLogger = LoggerFactory.getLogger("AMP.push");
  }

  @Hook(Pointcut.SERVER_PROCESSING_COMPLETED_NORMALLY)
  public void processingCompletedNormally(ServletRequestDetails theRequestDetails) {

    String myOperationType = "";
    String myResourceName = "";

    //operationType
    if (theRequestDetails.getRestOperationType() != null) {
      myOperationType = theRequestDetails.getRestOperationType().getCode();
      // only handle create,update
      if (!myOperationType.startsWith("create") && !myOperationType.startsWith("update")){
        return;
      }
      myOperationType = myOperationType.split("/")[0];
    }

    //resourceName
    if (theRequestDetails.getResourceName() != null) {
      myResourceName = theRequestDetails.getResourceName();
      // handle ServiceRequests
      if (myResourceName.startsWith("ServiceRequest")) {
        handleServiceRequests(theRequestDetails, myOperationType);
        return;
      }
      // handle CommunicationRequests
      if (myResourceName.startsWith("CommunicationRequest")) {
        handleCommunicationRequests(theRequestDetails, myOperationType);
        return;
      }
    }
  }


  private void handleServiceRequests(ServletRequestDetails theRequestDetails, String myOperationType) {
    Reference performer = null;
    List<Reference> endpointList = null;
    List<String> pushTokens = new ArrayList<String>();
    IFhirResourceDao dao = null;
    String patientId = "";
    String serviceRequestId = "";
    String senderId = "";

    IBaseResource resource = theRequestDetails.getResource();

    if (resource == null || !(resource instanceof ServiceRequest)) {
      myLogger.warn("ServiceRequest is not readable");
      return;
    }

    ServiceRequest myServiceRequest = (ServiceRequest) resource;
    ServiceRequestStatus status = myServiceRequest.getStatus();

    // check if status is active
    if (!status.getDisplay().toLowerCase().equals("active")) {
      myLogger.info("ServiceRequest status is not active but " + status.getDisplay().toLowerCase());
      return;
    }

    // read CommunicationRequest id
    final String requestType = myServiceRequest.getId().split("/")[0];
    if (!requestType.equals("ServiceRequest")) {
      myLogger.info("Reference is not an ServiceRequest but: " + requestType);
      return;
    }

    serviceRequestId = myServiceRequest.getId();

    // read patient id
    Reference patient = myServiceRequest.getSubject();
    final String referenceType = patient.getReference().split("/")[0];
    if(!referenceType.equals("Patient")){
      myLogger.info("Reference is not an Patient but: " + referenceType);
    } else {
      patientId = patient.getReference();
    }

    // find recipient organization
    performer = myServiceRequest.getPerformerFirstRep();

    if (performer != null) {
      final String performerType = performer.getReference().split("/")[0];
      if (!performerType.equals("Organization")) {
        myLogger.info("performer Reference is not an Organization but: " + performerType);
        return;
      }
      dao = myDaoRegistry.getResourceDao("Organization");
      resource = dao.read(new IdDt(performer.getReference()));
      if (resource instanceof Organization) {
        Organization myOrganization = (Organization) resource;
        // read endpoints from Organization
        endpointList = myOrganization.getEndpoint();
      }
    }

    // find requester organization
    Reference requester = null;
    requester = myServiceRequest.getRequester();
    if (requester != null) {
      final String requesterType = requester.getReference().split("/")[0];
      if (!requesterType.equals("Organization")) {
        myLogger.info("requester Reference is not an Organization but: " + requesterType);
        return;
      }
      dao = myDaoRegistry.getResourceDao("Organization");
      resource = dao.read(new IdDt(requester.getReference()));
      if (resource instanceof Organization) {
        senderId = requester.getReference();
      }
    }

    // find endpoints of organization
    dao = myDaoRegistry.getResourceDao("Endpoint");

    for (Reference ref : endpointList) {
      final String endpointType = ref.getReference().split("/")[0];
      if (!endpointType.equals("Endpoint")) {
        myLogger.info("Reference is not an Endpoint but: " + endpointType);
        return;
      }
      resource = dao.read(new IdDt(ref.getReference()));
      if (resource instanceof Endpoint) {
        Endpoint myEndpoint = (Endpoint) resource;
        EndpointStatus myStatus = myEndpoint.getStatus();
        // ignore non-active endpoints
        if (myStatus.toCode().toLowerCase() != "active") {
          continue;
        }

        List<ContactPoint> myContactPoints = myEndpoint.getContact();
        for (ContactPoint cp : myContactPoints) {
          pushTokens.add(cp.getValue());
          myLogger.info("Added pushtoken: " + cp.getValue());
        }
      }
    }

    // send push notification to endpoints
    sendPushNotification(pushTokens, myOperationType, senderId, patientId, serviceRequestId, myPushUrl);
  }

  private void handleCommunicationRequests(ServletRequestDetails theRequestDetails, String myOperationType) {
    Reference recipient = null;
    List<Reference> endpointList = null;
    List<String> pushTokens = new ArrayList<String>();
    IFhirResourceDao dao = null;
    String patientId = "";
    String communicationRequestId = "";
    String senderId = "";

    IBaseResource resource = theRequestDetails.getResource();

    if (resource == null || !(resource instanceof CommunicationRequest)) {
      myLogger.warn("CommunicationRequest is not readable");
      return;
    }

    CommunicationRequest myCommunicationRequest = (CommunicationRequest) resource;
    CommunicationRequestStatus status = myCommunicationRequest.getStatus();

    // read CommunicationRequest id
    final String requestType = myCommunicationRequest.getId().split("/")[0];
    if (!requestType.equals("CommunicationRequest")) {
      myLogger.info("Reference is not an CommunicationRequest but: " + requestType);
      return;
    }

    communicationRequestId = myCommunicationRequest.getId();

    // check if status is active
    if (!status.getDisplay().toLowerCase().equals("active")) {
      myLogger.info("CommunicationRequest status is not active but " + status.getDisplay().toLowerCase());
      return;
    }

    // read CommunicationRequest id
    String[] communicationRequestStrings = myCommunicationRequest.getId().split("/");
    if (!communicationRequestStrings[0].equals("CommunicationRequest")) {
      myLogger.info("Reference is not a CommunicationRequest but: " + communicationRequestStrings[0]);
    }

    // read patient id
    Reference patient = myCommunicationRequest.getSubject();

    final String patientType = patient.getReference().split("/")[0];
    if(!patientType.equals("Patient")){
      myLogger.info("Reference is not an Patient but: " + patientType);
    } else {
      patientId = patient.getReference();
    }

    // find recipient organization
    recipient = myCommunicationRequest.getRecipientFirstRep();

    if (recipient != null) {
      final String recipientType = recipient.getReference().split("/")[0];
      if (!recipientType.equals("Organization")) {
        myLogger.info("recipient Reference is not an Organization but: " + recipientType);
        return;
      }
      dao = myDaoRegistry.getResourceDao("Organization");
      resource = dao.read(new IdDt(recipient.getReference()));
      if (resource instanceof Organization) {
        myLogger.info("org ");
        Organization myOrganization = (Organization) resource;
        // read endpoints from Organization
        endpointList = myOrganization.getEndpoint();
      }
    }

    // find requester organization
    Reference requester = null;
    requester = myCommunicationRequest.getRequester();
    if (requester != null) {
      final String requesterType = requester.getReference().split("/")[0];
      if (!requesterType.equals("Organization")) {
        myLogger.info("requester Reference is not an Organization but: " + requesterType);
        return;
      }
      dao = myDaoRegistry.getResourceDao("Organization");
      resource = dao.read(new IdDt(requester.getReference()));
      if (resource instanceof Organization) {
        senderId = requester.getReference();
      }
    }

    // find endpoints of organization
    dao = myDaoRegistry.getResourceDao("Endpoint");

    for (Reference ref : endpointList) {
      final String endpointType = ref.getReference().split("/")[0];
      if (!endpointType.equals("Endpoint")) {
        myLogger.info("Reference is not an Endpoint but: " + endpointType);
        return;
      }
      resource = dao.read(new IdDt(ref.getReference()));
      if (resource instanceof Endpoint) {
        Endpoint myEndpoint = (Endpoint) resource;
        EndpointStatus myStatus = myEndpoint.getStatus();
        // ignore non-active endpoints
        if (myStatus.toCode().toLowerCase() != "active") {
          continue;
        }

        List<ContactPoint> myContactPoints = myEndpoint.getContact();
        for (ContactPoint cp : myContactPoints) {
          pushTokens.add(cp.getValue());
          myLogger.info("Add push token: " + cp.getValue());
        }
      }
    }

    // send push notification to endpoints
    sendPushNotification(pushTokens, myOperationType, senderId, patientId, communicationRequestId, myPushUrl);
  }

  // send a push notification via Sygnal to APNS
  private void sendPushNotification(List<String> pushTokens, String type, String senderId, String patientId, String requestId, String pushUrl) {
    try {
      URL url = new URL(pushUrl);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setDoOutput(true);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");

      // build content
      JSONArray devicelist = new JSONArray();

      for (String pt : pushTokens) {
        JSONObject device = new JSONObject();
        device.put("app_id", "care.amp.intensiv");
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
        throw new RuntimeException("Failed : HTTP error code : "
          + conn.getResponseCode());
      }

      BufferedReader br = new BufferedReader(new InputStreamReader(
          (conn.getInputStream())));

      String output;
      System.out.println("Output from push server .... \n");
      while ((output = br.readLine()) != null) {
        myLogger.info(output);
      }

      conn.disconnect();

    } catch (MalformedURLException e) {
        e.printStackTrace();
    } catch (IOException e) {
        e.printStackTrace();
    }

  }

}
