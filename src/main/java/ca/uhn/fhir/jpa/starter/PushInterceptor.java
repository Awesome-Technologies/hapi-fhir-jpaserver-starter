package ca.uhn.fhir.jpa.starter;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.commons.lang3.Validate;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Communication;
import org.hl7.fhir.r4.model.Communication.CommunicationStatus;
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
import org.json.JSONException;
import org.json.JSONObject;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;

import javax.annotation.Nullable;

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

  private final DaoRegistry myDaoRegistry;
  private final String myPushUrl;
  private final String myAppIdNormal;
  private final String myAppIdVoip;

  /**
   * Constructor for push notification interceptor
   *
   * @param theDaoRegistry The DAO registry (must not be null)
   */
  public PushInterceptor(DaoRegistry theDaoRegistry, String thePushUrl, String theAppIdNormal, String theAppIdVoip) {
    super();

    Validate.notNull(theDaoRegistry, "theDaoRegistry must not be null");
    myDaoRegistry = theDaoRegistry;
    myPushUrl = thePushUrl;
    myAppIdNormal = theAppIdNormal;
    myAppIdVoip = theAppIdVoip;
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
    else if (myResourceName.startsWith("Communication")) handleCommunication(theRequestDetails, myOperationType);
  }

  private void handleServiceRequests(ServletRequestDetails theRequestDetails, String theOperationType) {
    // only push when ServiceRequest is created
    String myOperationType = theRequestDetails.getRestOperationType().getCode();
    if (!myOperationType.equals("create")) return;

    final IBaseResource serviceRequest = theRequestDetails.getResource();
    if (serviceRequest == null || !(serviceRequest instanceof ServiceRequest)) {
      ourLog.warn("ServiceRequest is not readable");
      return;
    }
    final ServiceRequest myServiceRequest = (ServiceRequest) serviceRequest;

    // check if status is active
    final ServiceRequestStatus status = myServiceRequest.getStatus();
    if (!status.getDisplay().toLowerCase().equals("active")) {
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

    Map<String, Endpoint> endpointMap = new HashMap<>();

    // read endpoints from Organization
    final List<String> pushTokens = getPushTokens(performer, "push_token", "", endpointMap);

    // send push notification to endpoints
    final List<String> rejectedTokens = sendPushNotification(pushTokens, theOperationType, senderId, patientId, serviceRequestId, myAppIdNormal, false);

    if (rejectedTokens != null) {
      removePushTokens(rejectedTokens, endpointMap, "push_token");
    }
  }

  private void handleCommunication(ServletRequestDetails theRequestDetails, String myOperationType) {
    // only push when Communication is updated
    if (!myOperationType.equals("update")) return;

    final IBaseResource communication = theRequestDetails.getResource();
    if (communication == null || !(communication instanceof Communication)) {
      ourLog.warn("Communication is not readable");
      return;
    }
    final Communication myCommunication = (Communication) communication;

    // read Communication id
    final String communicationId = myCommunication.getId();
    final String requestType = getReferenceType(communicationId);
    if (!requestType.equals("Communication")) {
      ourLog.warn("Reference is not a Communication but: " + requestType);
      return;
    }

    // check if status is 'completed' or 'aborted'
    final CommunicationStatus status = myCommunication.getStatus();
    if (!status.getDisplay().toLowerCase().equals("completed")
      && !status.getDisplay().toLowerCase().equals("in progress")
      && !status.getDisplay().toLowerCase().equals("not done")
      && !status.getDisplay().toLowerCase().equals("aborted")
      ) {
      return;
    }

    // check if Communication is already read
    if (myCommunication.hasReceived()) {
      return;
    }

    // read patient id
    final Reference patient = myCommunication.getSubject();
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

    // find sender organization
    final Reference sender = myCommunication.getSender();
    if (sender == null) {
        ourLog.warn("No sender");
        return;
    }
    final String senderId = sender.getReference();
    final String senderType = getReferenceType(senderId);
    if (!senderType.equals("Organization")) {
      ourLog.warn("Sender is not an Organization but: " + senderType);
      return;
    }

    // find recipient organization
    final Reference recipient = myCommunication.getRecipientFirstRep();
    if (recipient == null) {
      ourLog.warn("No recipient set");
      return;
    }

    String app_id = myAppIdNormal;
    Boolean backgroundPush = false;
    List<String> pushTokens = new ArrayList<String>();

    // check if topic is PHONE-CONSULT
    Map<String, Endpoint> endpointMap = new HashMap<>();
    if (myCommunication.hasTopic()) {
      final CodeableConcept topic = myCommunication.getTopic();
      if (!topic.getText().toLowerCase().equals("phone-consult")) {
        return;
      }
      backgroundPush = true;
      pushTokens.addAll(getPushTokenFromPayload(myCommunication.getPayloadFirstRep().getContentStringType().toString(), recipient ,false, endpointMap));
    } else {
      if (status.getDisplay().toLowerCase().equals("completed")) return; // do not push on completed
      pushTokens.addAll(getPushTokens(recipient, "push_token", "", endpointMap));
    }

    // send a push notification via Sygnal to APNS
    List<String> rejectedTokens = sendPushNotification(pushTokens, myOperationType, senderId, patientId, communicationId, app_id, backgroundPush);

    if (rejectedTokens != null) {
      removePushTokens(rejectedTokens, endpointMap, "push_token");
    }
  }

  private void handleCommunicationRequests(ServletRequestDetails theRequestDetails, String myOperationType) {
    final IBaseResource communicationRequest = theRequestDetails.getResource();
    if (communicationRequest == null || !(communicationRequest instanceof CommunicationRequest)) {
      ourLog.warn("CommunicationRequest is not readable");
      return;
    }
    final CommunicationRequest myCommunicationRequest = (CommunicationRequest) communicationRequest;

    // read CommunicationRequest id
    final String communicationRequestId = myCommunicationRequest.getId();
    final String requestType = getReferenceType(communicationRequestId);
    if (!requestType.equals("CommunicationRequest")) {
      ourLog.warn("Reference is not a CommunicationRequest but: " + requestType);
      return;
    }

    // check if status is active
    final CommunicationRequestStatus myStatus = myCommunicationRequest.getStatus();
    String status = myStatus.getDisplay().toLowerCase();
    if (!status.equals("active")
      && !status.equals("revoked")
      && !status.equals("completed")) {
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
    final Reference sender = myCommunicationRequest.getSender();
    if (sender == null) {
        ourLog.warn("No sender");
        return;
    }
    final String senderId = sender.getReference();
    final String senderType = getReferenceType(senderId);
    if (!senderType.equals("Organization")) {
      ourLog.warn("Sender is not an Organization but: " + senderType);
      return;
    }

    // find recipient organization
    final Reference recipient = myCommunicationRequest.getRecipientFirstRep();
    if (recipient == null) {
      ourLog.warn("No recipient set");
      return;
    }
    final String recipientId = recipient.getReference();
    final String recipientType = getReferenceType(senderId);
    if (!recipientType.equals("Organization")) {
      ourLog.warn("Recipient is not an Organization but: " + recipientType);
      return;
    }

    String app_id = myAppIdNormal;
    Boolean backgroundPush = true;
    final List<String> pushTokens;

    Map<String, Endpoint> endpointMap = new HashMap<>();
    String token_type;

    if (myOperationType.equals("create")) {
      // for newly created CommunicationRequests send a voip push
      app_id = myAppIdVoip;
      backgroundPush = false;
      // read endpoints from Organization
      token_type = "voip_token";
      pushTokens = getPushTokens(recipient, token_type, "", endpointMap);
    } else {
      // CommunicationRequest updated -> background push to recipients
      token_type = "push_token";
      pushTokens = getPushTokens(recipient, token_type, "", endpointMap);
      if (status.equals("completed")) {
        pushTokens.addAll(getPushTokenFromPayload(myCommunicationRequest.getPayloadFirstRep().getContentStringType().toString(), sender, true, endpointMap));
        // remove the pushToken of the device that just accepted the call to avoid that it hangs up immediately
        List<String> callRecipients = getPushTokenFromPayload(myCommunicationRequest.getPayloadFirstRep().getContentStringType().toString(), recipient, false, endpointMap);
        for (String callRecipient : callRecipients) {
          pushTokens.remove(callRecipient);
        }
      }
    }

    // send a push notification via Sygnal to APNS
    List<String> rejectedTokens = sendPushNotification(pushTokens, myOperationType, senderId, patientId, communicationRequestId, app_id, backgroundPush);

    if (rejectedTokens != null) {
      removePushTokens(rejectedTokens, endpointMap, token_type);
    }
  }

  private String getReferenceType(String reference) {
    if (reference == null) return "";
    return reference.split("/")[0];
  }

  private List<String> getPushTokens(Reference organization, String token_type, String device_id, @Nullable Map<String, Endpoint> getEndpointMap) {
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
      if (!myStatus.toCode().equalsIgnoreCase("active")) {
        continue;
      }

      if (!myEndpoint.getConnectionType().getSystem().equals("https://developer.apple.com/notifications/")) {
        continue;
      }

      // we store the push tokens as ContactPoints
      for (ContactPoint cp : myEndpoint.getContact()) {
        JSONObject json = new JSONObject(cp.getValue());
        if(!device_id.equals("")){
          if(!json.getString("device_id").equals(device_id)) continue;
        }
        final String tokenValue = json.getString(token_type);
        if(!tokenValue.equals("")){
          pushTokens.add(tokenValue);
          if (getEndpointMap != null)
            getEndpointMap.put(tokenValue, myEndpoint);
        }
      }
    }
    return pushTokens;
  }

  // extract device id from payload and search push token for the device
  private List<String> getPushTokenFromPayload(String payload, Reference org, boolean sender, @Nullable Map<String, Endpoint> getEndpointMap) {
    if (payload == null) return Collections.emptyList();

    JSONObject json = new JSONObject(payload);
    String device_id;

    if (sender) {
      if (json.has("offer")) {
        device_id = json.getJSONObject("offer").getString("sender_device_id");
      } else {
        return Collections.emptyList();
      }
    } else {
      if (json.has("answer")) {
        device_id = json.getJSONObject("answer").getString("sender_device_id");
      } else {
        return Collections.emptyList();
      }
    }
    return getPushTokens(org, "push_token", device_id, getEndpointMap);
  }

  // Remove push tokens from endpoints in case they were rejected by sygnal
  private void removePushTokens(List<String> pushTokens, Map<String, Endpoint> endpointMap, String token_type) {
    List<Endpoint> updatedEndpoints = new ArrayList<>(endpointMap.size());
    for (String token : pushTokens) {
      if (endpointMap.containsKey(token)) {
        final Endpoint myEndpoint = endpointMap.get(token);
        for (ContactPoint contact: myEndpoint.getContact()) {
          JSONObject contactPoint;
          try {
            contactPoint = new JSONObject(contact.getValue());
          } catch (JSONException e) {
            // TODO decide if something should be done here
            continue;
          }
          if (contactPoint.has(token_type) && pushTokens.contains(contactPoint.getString(token_type))) {
            contactPoint.remove(token_type);
            // Only need to write to the ContactPoint object if we actually change the value
            contact.setValue(contactPoint.toString());
            updatedEndpoints.add(myEndpoint);
          }
        }
      }
    }

    if (updatedEndpoints.size() > 0) {
      IFhirResourceDao<Endpoint> endpointDao = myDaoRegistry.getResourceDao(Endpoint.class);
      for (Endpoint myEndpoint : updatedEndpoints) {
        endpointDao.update(myEndpoint);
      }
    }
  }

  // send a push notification via Sygnal to APNS, returning a list of rejected tokens or null.
  @Nullable
  private List<String> sendPushNotification(List<String> pushTokens, String type, String senderId, String patientId, String requestId, String appId, Boolean background) {
    List<String> rejectedTokens = null;

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
      notification.put("background", background);

      JSONObject content = new JSONObject();
      content.put("notification", notification);

      OutputStream os = conn.getOutputStream();
      os.write(content.toString().getBytes());
      os.flush();

      if (conn.getResponseCode() != HttpURLConnection.HTTP_OK && conn.getResponseCode() != HttpURLConnection.HTTP_BAD_REQUEST) {
        throw new RuntimeException("Failed : HTTP error code : " + conn.getResponseCode());
      }

      if (conn.getContentType().equals("application/json")) {
        InputStream rawContent = (InputStream)conn.getContent();
        BufferedReader contentReader = new BufferedReader(new InputStreamReader(rawContent, StandardCharsets.UTF_8));
        String decodedReply = contentReader.lines().collect(Collectors.joining());
        ourLog.info(decodedReply);
        JSONObject reply = new JSONObject(decodedReply);
        if (reply.has("rejected")) {
          Object rejectedKeys = reply.get("rejected");
          if (rejectedKeys instanceof JSONArray) {
            JSONArray keysArray = (JSONArray) rejectedKeys;
            final List<Object> keysList = keysArray.toList();
            if (keysList.stream().allMatch(o -> o instanceof String)) {
              rejectedTokens = keysList.stream().map(o -> (String) o).collect(Collectors.toList());
            }
          }
        }
      }

      conn.disconnect();

    } catch (MalformedURLException e) {
        e.printStackTrace();
    } catch (IOException e) {
        e.printStackTrace();
    }

    return rejectedTokens;
  }

}
