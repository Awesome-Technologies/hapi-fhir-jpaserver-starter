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
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Endpoint;
import org.hl7.fhir.r4.model.IdType;
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
 * Copyright (C) 2020, 2021 Awesome Technologies Innovationslabor GmbH
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
 * Server interceptor which creates push notifications
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
    else if (myResourceName.startsWith("DiagnosticReport")) handleDiagnosticReport(theRequestDetails, myOperationType);
  }

  private void handleServiceRequests(ServletRequestDetails theRequestDetails, String myOperationType) {
    // only push when ServiceRequest is created or updated
    if (!myOperationType.equals("create") && !myOperationType.equals("update")) return;

    final IBaseResource serviceRequest = theRequestDetails.getResource();
    if (serviceRequest == null || !(serviceRequest instanceof ServiceRequest)) {
      ourLog.warn("ServiceRequest is not readable");
      return;
    }
    final ServiceRequest myServiceRequest = (ServiceRequest) serviceRequest;

    // check if status is 'active', 'on hold' or 'completed'
    final ServiceRequestStatus status = myServiceRequest.getStatus();

    if (!status.getDisplay().toLowerCase().equals("active")
      && !status.getDisplay().toLowerCase().equals("on hold")
      && !status.getDisplay().toLowerCase().equals("completed")) {
      return;
    }

    // read ServiceRequest id
    String serviceRequestId = myServiceRequest.getId();
    final String requestType = getReferenceType(serviceRequestId);
    if (!requestType.equals("ServiceRequest")) {
      ourLog.warn("Reference is not a ServiceRequest but: " + requestType);
      return;
    }

    // find requester organization
    final Reference requester = myServiceRequest.getRequester();
    final String requesterType = getReferenceType(requester.getReference());
    if (!requesterType.equals("Organization")) {
      ourLog.warn("Requester is empty or not an Organization but: " + requesterType);
      return;
    }

    // find performer organization
    final Reference performer = myServiceRequest.getPerformerFirstRep();
    final String performerType = getReferenceType(performer.getReference());
    if (!performerType.equals("Organization")) {
      ourLog.warn("Performer is empty or not an Organization but: " + performerType);
      return;
    }

    Map<String, Endpoint> endpointMap = new HashMap<>();
    List<String> pushTokens = new ArrayList<String>();
    List<String> backgroundPushTokens = new ArrayList<String>();
    String push_type = null;

    if (myOperationType.equals("create")) {
      // ServiceRequest was created
      if (status.getDisplay().toLowerCase().equals("active")) {
        if (myServiceRequest.hasReplaces()) {
          // ServiceRequest was forwarded -> push to new performer, background push to old performer and requester (CASE_FORWARDED)
          push_type = "CASE_FORWARDED";

          // push to old performer with old serviceRequestId first
          // find old performer
          final Reference oldServiceRequestReference = myServiceRequest.getReplacesFirstRep();
          IFhirResourceDao<ServiceRequest> serviceRequestDao = myDaoRegistry.getResourceDao("ServiceRequest");
          ServiceRequest oldServiceRequest = serviceRequestDao.read(new IdType(oldServiceRequestReference.getReference()));
          final Reference oldPerformer = oldServiceRequest.getPerformerFirstRep();
          backgroundPushTokens.addAll(getPushTokens(oldPerformer, "push_token", "", endpointMap));

          // send pushes to old performer
          List<String> rejectedTokens = new ArrayList<String>();
          if (!backgroundPushTokens.isEmpty()) {
            rejectedTokens.addAll(sendPushNotification(backgroundPushTokens, myOperationType, oldServiceRequest.getId(), myAppIdNormal, true, push_type));
          }

          if (!rejectedTokens.isEmpty()) {
            removePushTokens(rejectedTokens, endpointMap, "push_token");
          }
          backgroundPushTokens = new ArrayList<String>();

          // push to new performer and background push to requester
          pushTokens.addAll(getPushTokens(performer, "push_token", "", endpointMap));
          backgroundPushTokens.addAll(getPushTokens(requester, "push_token", "", endpointMap));
        }
      }
    } else {
      // ServiceRequest was updated
      if (!status.getDisplay().toLowerCase().equals("active")
          && !status.getDisplay().toLowerCase().equals("on hold")
          && !status.getDisplay().toLowerCase().equals("completed")) {
        return;
      }

      // lookup the sender of the request
      final String authHeader = theRequestDetails.getHeader("Authorization");
      final BearerToken bearerToken = new BearerToken(authHeader);
      // get list of organizations
      final List<IdType> myOrgIds = bearerToken.getAuthorizedOrganizations();

      // user requests or accepts to close the case -> push to sender of the request, background push to receiver of the request (CLOSE_CASE_REQUEST, CLOSE_CASE_CONFIRMED)
      if (myOrgIds.contains(new IdType(requester.getReference()))) {
           // requester sent this request
           pushTokens.addAll(getPushTokens(performer, "push_token", "", endpointMap));
           backgroundPushTokens.addAll(getPushTokens(requester, "push_token", "", endpointMap));
      }

      if (myOrgIds.contains(new IdType(performer.getReference()))) {
           // performer sent this request
           pushTokens.addAll(getPushTokens(requester, "push_token", "", endpointMap));
           backgroundPushTokens.addAll(getPushTokens(performer, "push_token", "", endpointMap));
      }

      if (status.getDisplay().toLowerCase().equals("active")) {
        push_type = "CLOSE_CASE_DECLINED";
        // send background pushes to everyone
        backgroundPushTokens.addAll(pushTokens);
        pushTokens.clear();
      }

      if (status.getDisplay().toLowerCase().equals("on hold")) {
        push_type = "CLOSE_CASE_REQUEST";
      }

      if (status.getDisplay().toLowerCase().equals("completed")) {
        push_type = "CLOSE_CASE_CONFIRMED";
      }
    }

    // send push notification to endpoints
    List<String> rejectedTokens = new ArrayList<String>();
    if (!pushTokens.isEmpty()) {
      rejectedTokens.addAll(sendPushNotification(pushTokens, myOperationType, serviceRequestId, myAppIdNormal, false, push_type));
    }
    if (!backgroundPushTokens.isEmpty()) {
      rejectedTokens.addAll(sendPushNotification(backgroundPushTokens, myOperationType, serviceRequestId, myAppIdNormal, true, push_type));
    }

    if (!rejectedTokens.isEmpty()) {
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

    // check if status is 'completed' or 'in progress'
    final CommunicationStatus status = myCommunication.getStatus();
    if (!status.getDisplay().toLowerCase().equals("completed")
      && !status.getDisplay().toLowerCase().equals("in progress")
      ) {
      return;
    }

    // find sender organization
    final Reference sender = myCommunication.getSender();
    final String senderType = getReferenceType(sender.getReference());
    if (!senderType.equals("Organization")) {
      ourLog.warn("Sender is empty or not an Organization but: " + senderType);
      return;
    }

    // find recipient organization
    final Reference recipient = myCommunication.getRecipientFirstRep();
    final String recipientType = getReferenceType(recipient.getReference());
    if (!recipientType.equals("Organization")) {
      ourLog.warn("Recipient is empty or not an Organization but: " + recipientType);
      return;
    }

    String app_id = myAppIdNormal;
    String push_type = null;
    List<String> pushTokens = new ArrayList<String>();
    List<String> backgroundPushTokens = new ArrayList<String>();
    Map<String, Endpoint> endpointMap = new HashMap<>();

    // check if status is 'completed'
    if (status.getDisplay().toLowerCase().equals("completed")) {
        // check if topic is PHONE-CONSULT
        if (myCommunication.hasTopic()) {
          final CodeableConcept topic = myCommunication.getTopic();
          if (!topic.getText().toLowerCase().equals("phone-consult")) {
            return;
          }
          // pending call has ended -> background push to sender and recipient (CALL_ENDED)
          backgroundPushTokens.addAll(getPushTokenFromPayload(myCommunication.getPayloadFirstRep().getContentStringType().toString(), sender ,true, endpointMap));
          backgroundPushTokens.addAll(getPushTokenFromPayload(myCommunication.getPayloadFirstRep().getContentStringType().toString(), recipient ,false, endpointMap));

          push_type = "CALL_ENDED";
        } else {
          // check if Communication is already read / has 'received' date set
          if (myCommunication.hasReceived()) {
        	// user has read a message -> background push to sender and recipient (CASE_COMMUNICATION_READ)
            backgroundPushTokens.addAll(getPushTokens(sender, "push_token", "", endpointMap));
            backgroundPushTokens.addAll(getPushTokens(recipient, "push_token", "", endpointMap));
            push_type = "CASE_COMMUNICATION_READ";
          }
        }
    } else {
      // status is 'in progress'
        // a new Communication was sent -> push to recipient, background push to sender (CASE_REQUEST_NEW)
        pushTokens.addAll(getPushTokens(recipient, "push_token", "", endpointMap));
        // background push to senders
        backgroundPushTokens.addAll(getPushTokens(sender, "push_token", "", endpointMap));
        push_type = "CASE_REQUEST_NEW";
    }

    // send a push notification via Sygnal to APNS
    List<String> rejectedTokens = new ArrayList<String>();
    if (!pushTokens.isEmpty()) {
      rejectedTokens.addAll(sendPushNotification(pushTokens, myOperationType, communicationId, app_id, false, push_type));
    }
    if (!backgroundPushTokens.isEmpty()) {
      rejectedTokens.addAll(sendPushNotification(backgroundPushTokens, myOperationType, communicationId, app_id, true, push_type));
    }

    if (!rejectedTokens.isEmpty()) {
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

    // check if status is active, revoked or completed
    final CommunicationRequestStatus myStatus = myCommunicationRequest.getStatus();
    String status = myStatus.getDisplay().toLowerCase();
    if (!status.equals("active")
      && !status.equals("revoked")
      && !status.equals("completed")) {
      return;
    }

    // find requester organization
    final Reference sender = myCommunicationRequest.getSender();
    final String senderType = getReferenceType(sender.getReference());
    if (!senderType.equals("Organization")) {
      ourLog.warn("Sender is empty or not an Organization but: " + senderType);
      return;
    }

    // find recipient organization
    final Reference recipient = myCommunicationRequest.getRecipientFirstRep();
    final String recipientType = getReferenceType(recipient.getReference());
    if (!recipientType.equals("Organization")) {
      ourLog.warn("Recipient is empty or not an Organization but: " + recipientType);
      return;
    }

    String app_id = myAppIdNormal;
    Boolean backgroundPush = true;
    final List<String> pushTokens;

    Map<String, Endpoint> endpointMap = new HashMap<>();
    String token_type;
    String push_type = null;

    if (myOperationType.equals("create")) {
      // for newly created CommunicationRequests send a voip push (CALL_REQUEST)
      app_id = myAppIdVoip;
      backgroundPush = false;
      // read endpoints from Organization
      token_type = "voip_token";
      pushTokens = getPushTokens(recipient, token_type, "", endpointMap);
      push_type = "CALL_REQUEST";
    } else {
      // user ended call early -> background push to recipients (CALL_ENDED)
      token_type = "push_token";
      pushTokens = getPushTokens(recipient, token_type, "", endpointMap);
      push_type = "CALL_ENDED";
      if (status.equals("completed")) {
      // user accepted the call -> background push to sender and all other recipients (CALL_ACCEPTED)
        pushTokens.addAll(getPushTokenFromPayload(myCommunicationRequest.getPayloadFirstRep().getContentStringType().toString(), sender, true, endpointMap));
        // remove the pushToken of the device that just accepted the call to avoid that it hangs up immediately
        List<String> callRecipients = getPushTokenFromPayload(myCommunicationRequest.getPayloadFirstRep().getContentStringType().toString(), recipient, false, endpointMap);
        for (String callRecipient : callRecipients) {
          pushTokens.remove(callRecipient);
        }
        push_type = "CALL_ACCEPTED";
      }
    }

    // send a push notification via Sygnal to APNS
    List<String> rejectedTokens = sendPushNotification(pushTokens, myOperationType, communicationRequestId, app_id, backgroundPush, push_type);

    if (rejectedTokens != null) {
      removePushTokens(rejectedTokens, endpointMap, token_type);
    }
  }

  private void handleDiagnosticReport(ServletRequestDetails theRequestDetails, String myOperationType) {
    // only push when DiagnosticReport is created or updated
    if (!myOperationType.equals("create") && !myOperationType.equals("update")) return;

    final IBaseResource diagnosticReport = theRequestDetails.getResource();
      if (diagnosticReport == null || !(diagnosticReport instanceof DiagnosticReport)) {
        ourLog.warn("DiagnosticReport is not readable");
        return;
      }
      final DiagnosticReport myDiagnosticReport = (DiagnosticReport) diagnosticReport;

      // read Communication id
      final String diagnosticReportId = myDiagnosticReport.getId();
      final String requestType = getReferenceType(diagnosticReportId);
      if (!requestType.equals("DiagnosticReport")) {
        ourLog.warn("Reference is not a DiagnosticReport but: " + requestType);
        return;
      }

      // find organizations from ServiceRequest
      final Reference serviceRequestReference = myDiagnosticReport.getBasedOnFirstRep();
      IFhirResourceDao<ServiceRequest> serviceRequestDao = myDaoRegistry.getResourceDao("ServiceRequest");
      ServiceRequest serviceRequest = serviceRequestDao.read(new IdType(serviceRequestReference.getReference()));

      final Reference srPerformer = serviceRequest.getPerformerFirstRep();
      final String performerType = getReferenceType(srPerformer.getReference());
      if (!performerType.equals("Organization")) {
        ourLog.warn("Performer is empty or not an Organization but: " + performerType);
        return;
      }

      final Reference srRequester = serviceRequest.getRequester();
      final String requesterType = getReferenceType(srRequester.getReference());
      if (!requesterType.equals("Organization")) {
        ourLog.warn("Requester is empty or not an Organization but: " + requesterType);
        return;
      }

      String app_id = myAppIdNormal;
      List<String> pushTokens = new ArrayList<String>();
      List<String> backgroundPushTokens = new ArrayList<String>();
      Map<String, Endpoint> endpointMap = new HashMap<>();
      String push_type = null;

      if (myOperationType.equals("create")) {
        // DiagnosticReport was created -> push to recipient, background push to sender (CASE_CONSULTATION_REPORT_NEW)
        pushTokens.addAll(getPushTokens(srRequester, "push_token", "", endpointMap));
        backgroundPushTokens.addAll(getPushTokens(srPerformer, "push_token", "", endpointMap));
        push_type = "CASE_CONSULTATION_REPORT_NEW";
      } else {
        // DiagnosticReport was accepted -> push to sender, background push to recipient (CASE_CONSULTATION_REPORT_CONFIRMED)
        pushTokens.addAll(getPushTokens(srPerformer, "push_token", "", endpointMap));
        backgroundPushTokens.addAll(getPushTokens(srRequester, "push_token", "", endpointMap));
        push_type = "CASE_CONSULTATION_REPORT_CONFIRMED";
      }

      // send a push notification via Sygnal to APNS
      List<String> rejectedTokens = new ArrayList<String>();
      if (!pushTokens.isEmpty()) {
        rejectedTokens.addAll(sendPushNotification(pushTokens, myOperationType, diagnosticReportId, app_id, false, push_type));
      }
      if (!backgroundPushTokens.isEmpty()) {
        rejectedTokens.addAll(sendPushNotification(backgroundPushTokens, myOperationType, diagnosticReportId, app_id, true, push_type));
      }

      if (!rejectedTokens.isEmpty()) {
        removePushTokens(rejectedTokens, endpointMap, "push_token");
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
  private List<String> sendPushNotification(List<String> pushTokens, String type, String requestId, String appId, Boolean background, String push_type) {
    List<String> rejectedTokens = new ArrayList<String>();

    if(null == push_type) {
      ourLog.warn("Missing push_type");
      return rejectedTokens;
    }

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
      notification.put("type", type);
      notification.put("request", requestId);
      notification.put("devices", devicelist);
      notification.put("background", background);
      notification.put("push_type", push_type);

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
