/*
 * Copyright (C) 2020, 2021 Awesome Technologies Innovationslabor GmbH. All rights reserved.
 *
 *
 * ResourceAuthorizationInterceptor.java is free software: you can redistribute it and/or modify it under the
 * terms of the Apache License, Version 2.0 (the License);
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * The software is provided "AS IS", without any warranty of any kind, express or implied,
 * including but not limited to the warranties of merchantability, fitness for a particular
 * purpose and noninfringement, in no event shall the authors or copyright holders be
 * liable for any claim, damages or other liability, whether in action of contract, tort or
 * otherwise, arising from, out of or in connection with the software or the use or other
 * dealings in the software.
 *
 * See README file for the full disclaimer information and LICENSE file for full license
 * information in the project root.
 *
 * @author Awesome Technologies Innovationslabor GmbH
 *
 * Interceptor which checks the authorization to access a resource
 */

package ca.uhn.fhir.jpa.starter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.Validate;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Communication;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Media;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.ServiceRequest.ServiceRequestStatus;
import org.hl7.fhir.r4.model.Communication.CommunicationStatus;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.ReferenceOrListParam;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRuleBuilder;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;

import ca.uhn.fhir.rest.api.server.storage.ResourcePersistentId;
import org.springframework.beans.factory.annotation.Autowired;

public class ResourceAuthorizationInterceptor extends AuthorizationInterceptor {
  private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(ResourceAuthorizationInterceptor.class);

  @Autowired
  private DaoRegistry myDaoRegistry;

  @Autowired
  private BearerTokenFactory bearerTokenFactory = BearerToken::new;

  /**
   * Constructor for resource authorization interceptor
   *
   * @param theDaoRegistry The DAO registry (must not be null)
   */
  /* TODO: use constructor injection so that verification can still happen */
  public ResourceAuthorizationInterceptor(DaoRegistry theDaoRegistry, BearerTokenFactory bearerTokenFactory) {
    super();
    if (bearerTokenFactory != null) {
      this.bearerTokenFactory = bearerTokenFactory;
    }

    Validate.notNull(theDaoRegistry, "theDaoRegistry must not be null");
    myDaoRegistry = theDaoRegistry;
  }

  @Autowired
  public void setBearerTokenFactory(BearerTokenFactory bearerTokenFactory) {
    this.bearerTokenFactory = bearerTokenFactory;
  }

  public void setDaoRegistry(DaoRegistry myDaoRegistry) {
    this.myDaoRegistry = myDaoRegistry;
  }

  @Override
  public List<IAuthRule> buildRuleList(RequestDetails theRequestDetails) {
    // allow everyone to request the metadata
    if (theRequestDetails.getRestOperationType().equals(RestOperationTypeEnum.METADATA)) {
      return new RuleBuilder()
        .allowAll("Allow all")
        .build();
    }

    final String authHeader = theRequestDetails.getHeader("Authorization");
    final BearerToken bearerToken = bearerTokenFactory.apply(authHeader);

    // Grant administrators access to everything
    if (bearerToken.isAdmin()) {
      return new RuleBuilder()
      .allowAll("Allow all")
      .build();
    }

    // get list of authorized organizations
    final List<IdType> myOrgIds = bearerToken.getAuthorizedOrganizations();

    // get all organizations
    Set<IIdType> authorizedOrganizationList = new HashSet<>();
    Set<IIdType> organizationEndpointList = new HashSet<>();
    IFhirResourceDao<Organization> organizationDao = myDaoRegistry.getResourceDao("Organization");

    // check if our organizations can be found in the DAO
    for (IIdType myOrg : myOrgIds) {
      IBaseResource orgRes = organizationDao.read(myOrg);
      if (!orgRes.isEmpty()) {
        authorizedOrganizationList.add(myOrg);
        List<Reference> endpoints = ((Organization) orgRes).getEndpoint();
        for (Reference endpoint : endpoints) {
          organizationEndpointList.add(endpoint.getReferenceElement());
        }
      }
    }

    if (authorizedOrganizationList.isEmpty()) {
      // Throw an HTTP 401
      throw new AuthenticationException("No valid access role: Organization not found");
    }

    // allow paging
    if (theRequestDetails.getRestOperationType().equals(RestOperationTypeEnum.GET_PAGE)) {
      // allow all as forbidden resources were restricted for the initial search request
      return new RuleBuilder()
        .allowAll("Allow all")
        .build();
    }

    // creation of new resources
    if (theRequestDetails.getRestOperationType().equals(RestOperationTypeEnum.CREATE)) {
      switch(theRequestDetails.getResourceName()) {
        // allow creation of new patients and servicerequests
        case "Coverage":
        case "Observation":
        case "Patient":
        case "ServiceRequest":
          return new RuleBuilder()
            .allow("Create Coverage").create().resourcesOfType("Coverage").withAnyId().andThen()
            .allow("Create Observation").create().resourcesOfType("Observation").withAnyId().andThen()
            .allow("Create Patient").create().resourcesOfType("Patient").withAnyId().andThen()
            .allow("Create ServiceRequest").create().resourcesOfType("ServiceRequest").withAnyId().andThen()
            .build();
        case "Communication":
          // read resource
          final IBaseResource com = theRequestDetails.getResource();
            if (com == null || !(com instanceof Communication)) {
              ourLog.warn("Communication is not readable");
              break;
            }
            final Communication myCom = (Communication) com;

            // deny if corresponding serviceRequest is on_hold or completed
            if(isServiceRequestReadOnly(myCom.getBasedOn())) {
              return new RuleBuilder()
                        .denyAll("Corresponding ServiceRequest is read only")
                        .build();
            } else {
              return new RuleBuilder()
                        .allow("Create Communication").create().resourcesOfType("Communication").withAnyId().andThen()
                        .build();
            }
        case "CommunicationRequest":
          // read resource
          final IBaseResource comReq = theRequestDetails.getResource();
            if (comReq == null || !(comReq instanceof CommunicationRequest)) {
              ourLog.warn("Communication is not readable");
              break;
            }
            final CommunicationRequest myComReq = (CommunicationRequest) comReq;

            // deny if corresponding serviceRequest is on_hold or completed
            if(isServiceRequestReadOnly(myComReq.getBasedOn())) {
              return new RuleBuilder()
                        .denyAll("Corresponding ServiceRequest is read only")
                        .build();
            } else {
              return new RuleBuilder()
                        .allow("Create CommunicationRequest").create().resourcesOfType("CommunicationRequest").withAnyId().andThen()
                        .build();
            }
        case "DiagnosticReport":
          // read resource
          final IBaseResource dr = theRequestDetails.getResource();
            if (dr == null || !(dr instanceof DiagnosticReport)) {
              ourLog.warn("DiagnosticReport is not readable");
              break;
            }
            final DiagnosticReport myDr = (DiagnosticReport) dr;

            // deny if corresponding serviceRequest is on_hold or completed
            if(isServiceRequestReadOnly(myDr.getBasedOn())) {
              return new RuleBuilder()
                        .denyAll("Corresponding ServiceRequest is read only")
                        .build();
            } else {
              return new RuleBuilder()
                        .allow("Create DiagnosticReport").create().resourcesOfType("DiagnosticReport").withAnyId().andThen()
                        .build();
            }
        case "Media":
          // read resource
          final IBaseResource med = theRequestDetails.getResource();
            if (med == null || !(med instanceof Media)) {
              ourLog.warn("Media is not readable");
              break;
            }
            final Media myMed = (Media) med;

            // deny if corresponding serviceRequest is on_hold or completed
            if(isServiceRequestReadOnly(myMed.getBasedOn())) {
              return new RuleBuilder()
                        .denyAll("Corresponding ServiceRequest is read only")
                        .build();
            } else {
              return new RuleBuilder()
                        .allow("Create Media").create().resourcesOfType("Media").withAnyId().andThen()
                        .build();
            }
        default: // deny per default
          return new RuleBuilder()
             .denyAll("Deny all")
             .build();
      }

      return new RuleBuilder()
              .denyAll("Deny all")
              .build();
    }

    // if the request is a search, allow all as search narrowing has already restricted unauthorized resources
    if (theRequestDetails.getRestOperationType().equals(RestOperationTypeEnum.SEARCH_TYPE)) {
      return new RuleBuilder()
        .allowAll("Allow all")
        .build();
    }

    // allow only resource requests from here on
    if (theRequestDetails.getResourceName() == null) {
      return new RuleBuilder()
         .denyAll("Deny all")
         .build();
    }

    switch(theRequestDetails.getResourceName()) {
      case "Organization":
      case "Endpoint":
        return new RuleBuilder()
           .allow("Read all Organizations").read().resourcesOfType("Organization").withAnyId().andThen()
           .allow("Write Organization").write().allResources().inCompartment("Organization", authorizedOrganizationList).andThen()
           .allow("Read Endpoint").read().allResources().inCompartment("Endpoint", organizationEndpointList).andThen()
           .allow("Write Endpoint").write().allResources().inCompartment("Endpoint", organizationEndpointList).andThen()
           .denyAll("Deny all")
           .build();
      case "Patient":
        return buildPatientRules(authorizedOrganizationList, theRequestDetails);
      case "Communication":
        return buildCommunicationRules(authorizedOrganizationList, theRequestDetails);
      case "CommunicationRequest":
        return buildCommunicationRequestRules(authorizedOrganizationList, theRequestDetails);
      case "ServiceRequest":
        return buildServiceRequestRules(authorizedOrganizationList, theRequestDetails);
      case "DiagnosticReport":
        return buildDiagnosticReportRules(authorizedOrganizationList, theRequestDetails);
      case "Observation":
        return buildObservationRules(authorizedOrganizationList, theRequestDetails);
      case "Media":
        return buildMediaRules(authorizedOrganizationList, theRequestDetails);
      case "Coverage":
        return buildCoverageRules(authorizedOrganizationList, theRequestDetails);
      default: // deny per default
        return new RuleBuilder()
           .denyAll("Deny all")
           .build();
    }
  }

  // checks if all referenced ServiceRequests are on_hold or completed
  private boolean isServiceRequestReadOnly(List<Reference> serviceRequests) {
    IFhirResourceDao<ServiceRequest> srDao = myDaoRegistry.getResourceDao("ServiceRequest");

    for(Reference reference : serviceRequests) {
      // read ServiceRequest
      IBaseResource sr = srDao.read((IIdType) reference);
      if (!(sr instanceof ServiceRequest)) {
        ourLog.warn("reference is not an ServiceRequest");
        return true;
      }

      // check if status is 'on hold' or 'completed'
      final ServiceRequestStatus status = ((ServiceRequest) sr).getStatus();
      if (!status.getDisplay().toLowerCase().equals("completed")
          && !status.getDisplay().toLowerCase().equals("on hold")
          ) {
            return false;
        }
    }

    // all ServiceRequests are either on_hold or completed
    return true;
  }

  private Set<IIdType> getPatients(Set<IIdType> authorizedOrganizationList, RequestDetails theRequestDetails) {
    Set<IIdType> authorizedPatientList = new HashSet<>();

    // check if the patient is managed by the organization
    IFhirResourceDao<Patient> patientDao = myDaoRegistry.getResourceDao("Patient");

    ReferenceOrListParam orgReferences = new ReferenceOrListParam();

    for (IIdType authorizedOrganization : authorizedOrganizationList) {
      orgReferences.addOr(new ReferenceParam(authorizedOrganization.getValue()));
    }

    SearchParameterMap orgParams = new SearchParameterMap();
    orgParams.add(Patient.SP_ORGANIZATION, orgReferences);

    Set<ResourcePersistentId> searchAuthorizedPatientIdList = patientDao.searchForIds(orgParams, theRequestDetails);

    for (ResourcePersistentId id : searchAuthorizedPatientIdList) {
      authorizedPatientList.add(new IdType("Patient/" + id));
    }

    // check if there are ServiceRequests for the patient connected with the organization
    IFhirResourceDao<ServiceRequest> serviceRequestdao = myDaoRegistry.getResourceDao("ServiceRequest");
    Set<IIdType> authorizedServiceRequests = getServiceRequests(authorizedOrganizationList, theRequestDetails);

    for (IIdType authorizedServiceRequest : authorizedServiceRequests) {
      ServiceRequest sr = serviceRequestdao.read(authorizedServiceRequest);
      authorizedPatientList.add(sr.getSubject().getReferenceElement());
    }

    // check if there are Communications for the patient connected with the organization
    IFhirResourceDao<Communication> communicationDao = myDaoRegistry.getResourceDao("Communication");
    Set<IIdType> authorizedCommunications = getCommunications(authorizedOrganizationList, theRequestDetails);

    for (IIdType authorizedCommunication : authorizedCommunications) {
      Communication com = communicationDao.read(authorizedCommunication);
      authorizedPatientList.add(com.getSubject().getReferenceElement());
    }

    return authorizedPatientList;
  }

  private List<IAuthRule> buildPatientRules(Set<IIdType> authorizedOrganizationList, RequestDetails theRequestDetails) {
    Set<IIdType> authorizedPatientList = new HashSet<>();
    IAuthRuleBuilder ruleBuilder = new RuleBuilder();
    String requestResource = theRequestDetails.getRequestPath();

    // check if the patient is managed by the organization
    IFhirResourceDao<Patient> patientDao = myDaoRegistry.getResourceDao("Patient");

    ReferenceOrListParam orgReferences = new ReferenceOrListParam();

    for (IIdType authorizedOrganization : authorizedOrganizationList) {
      orgReferences.addOr(new ReferenceParam(authorizedOrganization.getValue()));
    }

    SearchParameterMap orgParams = new SearchParameterMap();
    orgParams.add(Patient.SP_ORGANIZATION, orgReferences);

    Set<ResourcePersistentId> searchAuthorizedPatientIdList = patientDao.searchForIds(orgParams, theRequestDetails);

    for (ResourcePersistentId id : searchAuthorizedPatientIdList) {
      if (requestResource.equals("Patient/" + id)) {
        authorizedPatientList.add(new IdType(requestResource));
        return ruleBuilder.allow("Read Patient").read().allResources().inCompartment("Patient", authorizedPatientList).andThen()
                     .allow("Write Patient").write().allResources().inCompartment("Patient", authorizedPatientList).andThen()
                     .denyAll("Deny all").build();
      }
    }

    // check if there are ServiceRequests for the patient connected with the organization
    IFhirResourceDao<ServiceRequest> serviceRequestdao = myDaoRegistry.getResourceDao("ServiceRequest");
    Set<IIdType> authorizedServiceRequests = getServiceRequests(authorizedOrganizationList, theRequestDetails);

    for (IIdType authorizedServiceRequest : authorizedServiceRequests) {
      ServiceRequest sr = serviceRequestdao.read(authorizedServiceRequest);
      if (sr.getSubject().getReferenceElement().toString().equals(requestResource)) {
        authorizedPatientList.add(new IdType(requestResource));
        return ruleBuilder.allow("Read Patient").read().allResources().inCompartment("Patient", authorizedPatientList).andThen()
                     .allow("Write Patient").write().allResources().inCompartment("Patient", authorizedPatientList).andThen()
                     .denyAll("Deny all").build();
      }
    }

    // check if there are Communications for the patient connected with the organization
    IFhirResourceDao<Communication> communicationDao = myDaoRegistry.getResourceDao("Communication");
    Set<IIdType> authorizedCommunications = getCommunications(authorizedOrganizationList, theRequestDetails);

    for (IIdType authorizedCommunication : authorizedCommunications) {
      Communication com = communicationDao.read(authorizedCommunication);
      if (com.getSubject().getReferenceElement().toString().equals(requestResource)) {
        authorizedPatientList.add(new IdType(requestResource));
        return ruleBuilder.allow("Read Patient").read().allResources().inCompartment("Patient", authorizedPatientList).andThen()
                     .allow("Write Patient").write().allResources().inCompartment("Patient", authorizedPatientList).andThen()
                     .denyAll("Deny all").build();
      }
    }

    return ruleBuilder.allow("Read Patient").read().allResources().inCompartment("Patient", authorizedPatientList).andThen()
                 .allow("Write Patient").write().allResources().inCompartment("Patient", authorizedPatientList).andThen()
                 .denyAll("Deny all").build();

  }


  private Set<IIdType> getCommunications(Set<IIdType> authorizedOrganizationList, RequestDetails theRequestDetails) {
    Set<IIdType> authorizedCommunicationList = new HashSet<>();
    ReferenceOrListParam orgReferences = new ReferenceOrListParam();

    for (IIdType authorizedOrganization : authorizedOrganizationList) {
      orgReferences.addOr(new ReferenceParam(authorizedOrganization.getValue()));
    }

    // search all Communications for the organization
    IFhirResourceDao<Communication> communicationDao = myDaoRegistry.getResourceDao("Communication");
    SearchParameterMap senderParams = new SearchParameterMap();
    senderParams.add(Communication.SP_SENDER, orgReferences);
    SearchParameterMap recipientParams = new SearchParameterMap();
    recipientParams.add(Communication.SP_RECIPIENT, orgReferences);

    Set<ResourcePersistentId> searchAuthorizedCommunicationIdList = communicationDao.searchForIds(senderParams, theRequestDetails);
    for (ResourcePersistentId id : searchAuthorizedCommunicationIdList) {
      authorizedCommunicationList.add(new IdType("Communication/" + id.toString()));
    }

    searchAuthorizedCommunicationIdList = communicationDao.searchForIds(recipientParams, theRequestDetails);
    for (ResourcePersistentId id : searchAuthorizedCommunicationIdList) {
      authorizedCommunicationList.add(new IdType("Communication/" + id.toString()));
    }

    // search Communications connected to forwarded ServiceRequests
    ReferenceOrListParam srReferences = new ReferenceOrListParam();
    Set<IIdType> authorizedServiceRequestList = getServiceRequests(authorizedOrganizationList, theRequestDetails);

    for (IIdType authorizedServiceRequest : authorizedServiceRequestList) {
      srReferences.addOr(new ReferenceParam(authorizedServiceRequest.getValue()));
    }

    SearchParameterMap srParams = new SearchParameterMap();
    srParams.add(Communication.SP_BASED_ON, srReferences);

    searchAuthorizedCommunicationIdList = communicationDao.searchForIds(srParams, theRequestDetails);
    for (ResourcePersistentId id : searchAuthorizedCommunicationIdList) {
      authorizedCommunicationList.add(new IdType("Communication/" + id.toString()));
    }

    return authorizedCommunicationList;
  }

  private List<IAuthRule> buildCommunicationRules(Set<IIdType> authorizedOrganizationList, RequestDetails theRequestDetails) {
    Set<IIdType> authorizedCommunicationList = new HashSet<>();
    IAuthRuleBuilder ruleBuilder = new RuleBuilder();

    // read the Communication resource
    IFhirResourceDao<Communication> communicationDao = myDaoRegistry.getResourceDao("Communication");
    String requestResource = theRequestDetails.getRequestPath();

    Communication com = communicationDao.read(new IdType(requestResource));

    for (IIdType authorizedOrganization : authorizedOrganizationList) {
      // check if organization is the sender
      if ( authorizedOrganization.getValue().equals(com.getSender().getReferenceElement().getValue()) ) {
        authorizedCommunicationList.add(new IdType(requestResource));
        // check if read only ServiceRequest should be updated
        if(theRequestDetails.getRestOperationType().equals(RestOperationTypeEnum.UPDATE) && isServiceRequestReadOnly(com.getBasedOn())) {
          return ruleBuilder.denyAll("Corresponding ServiceRequest is read only").build();
        } else {
            return ruleBuilder.allow("Read Communication").read().allResources().inCompartment("Communication", authorizedCommunicationList).andThen()
                    .allow("Write Communication").write().allResources().inCompartment("Communication", authorizedCommunicationList).andThen()
                    .denyAll("Deny all").build();
        }
      }

      // check if organization is a recipient
      List<Reference> recipients = com.getRecipient();
      for (Reference recipient : recipients) {
        if (recipient != null && authorizedOrganization.getValue().equals(recipient.getReferenceElement().getValue())) {
          authorizedCommunicationList.add(new IdType(requestResource));
          // check if read only ServiceRequest should be updated
          if(theRequestDetails.getRestOperationType().equals(RestOperationTypeEnum.UPDATE) && isServiceRequestReadOnly(com.getBasedOn())) {
            return ruleBuilder.denyAll("Corresponding ServiceRequest is read only").build();
          } else {
            return ruleBuilder.allow("Read Communication").read().allResources().inCompartment("Communication", authorizedCommunicationList).andThen()
                      .allow("Write Communication").write().allResources().inCompartment("Communication", authorizedCommunicationList).andThen()
                      .denyAll("Deny all").build();
          }
        }
      }
    }

    // check if Communication is connected with a ServiceRequest that was forwarded
    Set<IIdType> authorizedServiceRequestList = getServiceRequests(authorizedOrganizationList, theRequestDetails);
    List<Reference> basedOn = com.getBasedOn();
    for (IIdType authorizedSR : authorizedServiceRequestList) {
      for (Reference basedOnRef : basedOn) {
        if (basedOnRef != null && authorizedSR.getValue().equals(basedOnRef.getReferenceElement().getValue())) {
          authorizedCommunicationList.add(new IdType(requestResource));
          // check if read only ServiceRequest should be updated
          if(theRequestDetails.getRestOperationType().equals(RestOperationTypeEnum.UPDATE) && isServiceRequestReadOnly(com.getBasedOn())) {
            return ruleBuilder.denyAll("Corresponding ServiceRequest is read only").build();
          } else {
            return ruleBuilder.allow("Read Communication").read().allResources().inCompartment("Communication", authorizedCommunicationList).andThen()
                      .allow("Write Communication").write().allResources().inCompartment("Communication", authorizedCommunicationList).andThen()
                      .denyAll("Deny all").build();
          }
        }
      }
    }

    return ruleBuilder.denyAll("Deny all").build();
  }


  private List<IAuthRule> buildCommunicationRequestRules(Set<IIdType> authorizedOrganizationList, RequestDetails theRequestDetails) {
    Set<IIdType> authorizedCommunicationRequestList = new HashSet<>();

    // read the CommunicationRequest resource
    IFhirResourceDao<CommunicationRequest> communicationRequestdao = myDaoRegistry.getResourceDao("CommunicationRequest");
    String requestResource = theRequestDetails.getRequestPath();

    CommunicationRequest comReq = communicationRequestdao.read(new IdType(requestResource));

    for (IIdType authorizedOrganization : authorizedOrganizationList) {
      // check if organization is the sender
      if ( authorizedOrganization.getValue().equals(comReq.getSender().getReferenceElement().getValue()) ) {
        authorizedCommunicationRequestList.add(new IdType(requestResource));
        break;
      }

      // check if organization is a recipient
      List<Reference> recipients = comReq.getRecipient();
      for (Reference recipient : recipients) {
        if (recipient != null && authorizedOrganization.getValue().equals(recipient.getReferenceElement().getValue())) {
          authorizedCommunicationRequestList.add(new IdType(requestResource));
          break;
        }
      }
    }

    IAuthRuleBuilder ruleBuilder = new RuleBuilder();
    // check if read only ServiceRequest should be updated
    if(theRequestDetails.getRestOperationType().equals(RestOperationTypeEnum.UPDATE) && isServiceRequestReadOnly(comReq.getBasedOn())) {
      return ruleBuilder.denyAll("Corresponding ServiceRequest is read only").build();
    } else {
      return ruleBuilder.allow("Read CommunicationRequest").read().allResources().inCompartment("CommunicationRequest", authorizedCommunicationRequestList).andThen()
                .allow("Write CommunicationRequest").write().allResources().inCompartment("CommunicationRequest", authorizedCommunicationRequestList).andThen()
                .denyAll("Deny all").build();
    }
  }


  private Set<IIdType> getServiceRequests(Set<IIdType> authorizedOrganizationList, RequestDetails theRequestDetails) {
    IFhirResourceDao<ServiceRequest> serviceRequestdao = myDaoRegistry.getResourceDao("ServiceRequest");
    Set<IIdType> authorizedServiceRequestList = new HashSet<>();
    ReferenceOrListParam orgReferences = new ReferenceOrListParam();

    for (IIdType authorizedOrganization : authorizedOrganizationList) {
      orgReferences.addOr(new ReferenceParam(authorizedOrganization.getValue()));
    }

    SearchParameterMap requesterParams = new SearchParameterMap();
    requesterParams.add(ServiceRequest.SP_REQUESTER, orgReferences);
    SearchParameterMap performerParams = new SearchParameterMap();
    performerParams.add(ServiceRequest.SP_PERFORMER, orgReferences);

    Set<ResourcePersistentId> searchAuthorizedServiceRequestIdList = serviceRequestdao.searchForIds(performerParams, theRequestDetails);

    for (ResourcePersistentId id : searchAuthorizedServiceRequestIdList) {
      authorizedServiceRequestList.add(new IdType("ServiceRequest/" + id.toString()));
    }

    searchAuthorizedServiceRequestIdList = serviceRequestdao.searchForIds(requesterParams, theRequestDetails);

    for (ResourcePersistentId id : searchAuthorizedServiceRequestIdList) {
      authorizedServiceRequestList.add(new IdType("ServiceRequest/" + id.toString()));
    }

    return authorizedServiceRequestList;
  }

  private List<IAuthRule> buildServiceRequestRules(Set<IIdType> authorizedOrganizationList, RequestDetails theRequestDetails) {
    Set<IIdType> authorizedServiceRequestList = new HashSet<>();

    // read the Communication resource
    IFhirResourceDao<ServiceRequest> serviceRequestDao = myDaoRegistry.getResourceDao("ServiceRequest");
    String requestResource = theRequestDetails.getRequestPath();

    ServiceRequest sr = serviceRequestDao.read(new IdType(requestResource));

    IAuthRuleBuilder ruleBuilder = new RuleBuilder();

    // check if status is 'on hold' or 'completed'
    if (theRequestDetails.getRestOperationType().equals(RestOperationTypeEnum.UPDATE)) {
    final ServiceRequestStatus status = ((ServiceRequest) sr).getStatus();
    if (status.getDisplay().toLowerCase().equals("completed")
          || status.getDisplay().toLowerCase().equals("on hold")
          ) {
        return ruleBuilder.denyAll("ServiceRequest is read only").build();
      }
    }

    for (IIdType authorizedOrganization : authorizedOrganizationList) {
      // check if organization is the requester
      if ( authorizedOrganization.getValue().equals(sr.getRequester().getReferenceElement().getValue()) ) {
        authorizedServiceRequestList.add(new IdType(requestResource));
        break;
      }

      // check if organization is a performer
      List<Reference> performers = sr.getPerformer();
      for (Reference performer : performers) {
        if (performer != null && authorizedOrganization.getValue().equals(performer.getReferenceElement().getValue())) {
          authorizedServiceRequestList.add(new IdType(requestResource));
          return ruleBuilder.allow("Read ServiceRequest").read().allResources().inCompartment("ServiceRequest", authorizedServiceRequestList).andThen()
            .allow("Write ServiceRequest").write().allResources().inCompartment("ServiceRequest", authorizedServiceRequestList).andThen()
            .denyAll("Deny all").build();
        }
      }
    }
    return ruleBuilder.allow("Read ServiceRequest").read().allResources().inCompartment("ServiceRequest", authorizedServiceRequestList).andThen()
      .allow("Write ServiceRequest").write().allResources().inCompartment("ServiceRequest", authorizedServiceRequestList).andThen()
      .denyAll("Deny all").build();
  }


  private List<IAuthRule> buildDiagnosticReportRules(Set<IIdType> authorizedOrganizationList, RequestDetails theRequestDetails) {
    Set<IIdType> authorizedDiagnosticReportList = new HashSet<>();
    Set<IIdType> authorizedServiceRequestList = getServiceRequests(authorizedOrganizationList, theRequestDetails);

    // read the DiagnosticReport resource
    IFhirResourceDao<DiagnosticReport> diagnosticReportDao = myDaoRegistry.getResourceDao("DiagnosticReport");
    String requestResource = theRequestDetails.getRequestPath();

    DiagnosticReport dr = diagnosticReportDao.read(new IdType(requestResource));

    IAuthRuleBuilder ruleBuilder = new RuleBuilder();

    for (IIdType authorizedSR : authorizedServiceRequestList) {
      // check if DiagnosticReport is based on the ServiceRequest
      List<Reference> basedOn = dr.getBasedOn();
      for (Reference basedOnRef : basedOn) {
        if (basedOnRef != null && authorizedSR.getValue().equals(basedOnRef.getReferenceElement().getValue())) {
          authorizedDiagnosticReportList.add(new IdType(requestResource));
          // check if read only ServiceRequest should be updated
          if(theRequestDetails.getRestOperationType().equals(RestOperationTypeEnum.UPDATE) && isServiceRequestReadOnly(dr.getBasedOn())) {
            return ruleBuilder.denyAll("Corresponding ServiceRequest is read only").build();
          } else {
            return ruleBuilder.allow("Read DiagnosticReport").read().allResources().inCompartment("DiagnosticReport", authorizedDiagnosticReportList).andThen()
                      .allow("Write DiagnosticReport").write().allResources().inCompartment("DiagnosticReport", authorizedDiagnosticReportList).andThen()
                      .denyAll("Deny all").build();
          }
        }
      }
    }
    return ruleBuilder.denyAll("Deny all").build();
  }


  private List<IAuthRule> buildObservationRules(Set<IIdType> authorizedOrganizationList, RequestDetails theRequestDetails) {
    Set<IIdType> authorizedObservationList = new HashSet<>();
    Set<IIdType> authorizedPatientList = getPatients(authorizedOrganizationList, theRequestDetails);

    // read the Observation resource
    IFhirResourceDao<Observation> observationDao = myDaoRegistry.getResourceDao("Observation");
    String requestResource = theRequestDetails.getRequestPath();

    // check if the Observations subject is an Patient that the organization is authorized for
    Observation obs = observationDao.read(new IdType(requestResource));
    Reference subject = obs.getSubject();

    for (IIdType authorizedPatient : authorizedPatientList) {
      if (subject != null && authorizedPatient.getValue().equals(subject.getReferenceElement().getValue())) {
        authorizedObservationList.add(new IdType(requestResource));
        break;
      }
    }

    IAuthRuleBuilder ruleBuilder = new RuleBuilder();
    return ruleBuilder.allow("Read Observation").read().allResources().inCompartment("Observation", authorizedObservationList).andThen()
                      .allow("Write Observation").write().allResources().inCompartment("Observation", authorizedObservationList).andThen()
                      .denyAll("Deny all").build();
  }


  private List<IAuthRule> buildMediaRules(Set<IIdType> authorizedOrganizationList, RequestDetails theRequestDetails) {
    // search all Media authorized for the organization
    Set<IIdType> authorizedMediaList = new HashSet<>();
    Set<IIdType> authorizedDeletableMediaList = new HashSet<>();
    Set<IIdType> authorizedCommunicationList = getCommunications(authorizedOrganizationList, theRequestDetails);
    IFhirResourceDao<Media> mediaDao = myDaoRegistry.getResourceDao("Media");
    IFhirResourceDao<Communication> comDao = myDaoRegistry.getResourceDao("Communication");
    String requestResource = theRequestDetails.getRequestPath();

    // read the Media resource
    Media med = mediaDao.read(new IdType(requestResource));

    // check if Media is connected with a Communication that the organization is authorized for
    List<Reference> partOf = med.getPartOf();

    IAuthRuleBuilder ruleBuilder = new RuleBuilder();

    for (IIdType authorizedCom : authorizedCommunicationList) {
      for (Reference partOfRef : partOf) {
        if (partOfRef != null && authorizedCom.getValue().equals(partOfRef.getReferenceElement().getValue())) {
          authorizedMediaList.add(new IdType(requestResource));
          // the Communication is in preparation and I am the sender -> I am allowed to delete the Media
          Communication com = (Communication) comDao.read(partOfRef.getReferenceElement());
          Reference sender = com.getSender();
          for (IIdType authorizedOrganization : authorizedOrganizationList) {
            if (sender != null
                && authorizedOrganization.getValue().equals(sender.getReferenceElement().getValue())
                && com.getStatus().getDisplay().toLowerCase().equals("preparation")
            ) {
              authorizedDeletableMediaList.add(new IdType(requestResource));
            }
          }
        }
        if (authorizedMediaList.size() > 0) {
          // check if read only ServiceRequest should be updated
          if(theRequestDetails.getRestOperationType().equals(RestOperationTypeEnum.UPDATE) && isServiceRequestReadOnly(med.getBasedOn())) {
            return ruleBuilder.denyAll("Corresponding ServiceRequest is read only").build();
          } else {
            return ruleBuilder.allow("Read Media").read().allResources().inCompartment("Media", authorizedMediaList).andThen()
                      .allow("Write Media").write().allResources().inCompartment("Media", authorizedMediaList).andThen()
                      .allow("Delete Media").delete().allResources().inCompartment("Media", authorizedDeletableMediaList).andThen()
                      .denyAll("Deny all").build();
            }
        }
      }
    }

    // check if Media is connected with a ServiceRequest that was forwarded
    Set<IIdType> authorizedServiceRequestList = getServiceRequests(authorizedOrganizationList, theRequestDetails);
    List<Reference> basedOn = med.getBasedOn();
    for (IIdType authorizedSR : authorizedServiceRequestList) {
      for (Reference basedOnRef : basedOn) {
        if (basedOnRef != null && authorizedSR.getValue().equals(basedOnRef.getReferenceElement().getValue())) {
          authorizedMediaList.add(new IdType(requestResource));
          // check if read only ServiceRequest should be updated
          if(theRequestDetails.getRestOperationType().equals(RestOperationTypeEnum.UPDATE) && isServiceRequestReadOnly(med.getBasedOn())) {
            return ruleBuilder.denyAll("Corresponding ServiceRequest is read only").build();
          } else {
            return ruleBuilder.allow("Read Media").read().allResources().inCompartment("Media", authorizedMediaList).andThen()
              .allow("Write Media").write().allResources().inCompartment("Media", authorizedMediaList).andThen()
              .denyAll("Deny all").build();
          }
        }
      }
    }

    return ruleBuilder.denyAll("Deny all").build();
  }


  private List<IAuthRule> buildCoverageRules(Set<IIdType> authorizedOrganizationList, RequestDetails theRequestDetails) {
    Set<IIdType> authorizedCoverageList = new HashSet<>();
    Set<IIdType> authorizedPatientList = getPatients(authorizedOrganizationList, theRequestDetails);

    // read the Coverage resource
    IFhirResourceDao<Coverage> coverageDao = myDaoRegistry.getResourceDao("Coverage");
    String requestResource = theRequestDetails.getRequestPath();

    Coverage cov = coverageDao.read(new IdType(requestResource));
    Reference policyHolder = cov.getPolicyHolder();

    for (IIdType authorizedPatient : authorizedPatientList) {
      if (policyHolder != null && authorizedPatient.getValue().equals(policyHolder.getReferenceElement().getValue())) {
        authorizedCoverageList.add(new IdType(requestResource));
        break;
      }
    }

    IAuthRuleBuilder ruleBuilder = new RuleBuilder();
    return ruleBuilder.allow("Read Coverage").read().allResources().inCompartment("Coverage", authorizedCoverageList).andThen()
                      .allow("Write Coverage").write().allResources().inCompartment("Coverage", authorizedCoverageList).andThen()
                      .denyAll("Deny all").build();
  }
}
