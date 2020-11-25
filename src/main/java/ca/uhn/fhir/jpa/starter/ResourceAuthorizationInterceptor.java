/*
 * Copyright (C) 2020  Awesome Technologies Innovationslabor GmbH. All rights reserved.
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

import java.util.ArrayList;
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

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRuleBuilder;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;

public class ResourceAuthorizationInterceptor extends AuthorizationInterceptor {

  private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(PushInterceptor.class);
  private final DaoRegistry myDaoRegistry;

  /**
   * Constructor for resource authorization interceptor
   *
   * @param theDaoRegistry The DAO registry (must not be null)
   */
  public ResourceAuthorizationInterceptor(DaoRegistry theDaoRegistry) {
    super();

    Validate.notNull(theDaoRegistry, "theDaoRegistry must not be null");
    myDaoRegistry = theDaoRegistry;
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
    final BearerToken bearerToken = new BearerToken(authHeader);

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
    IFhirResourceDao<?> organizationDao = myDaoRegistry.getResourceDao("Organization");

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

    // allow creation of new resources
    if (theRequestDetails.getRestOperationType().equals(RestOperationTypeEnum.CREATE)) {
      return new RuleBuilder()
        .allow("Create Patient").create().resourcesOfType​("Patient").withAnyId().andThen()
        .allow("Create Communication").create().resourcesOfType​("Communication").withAnyId().andThen()
        .allow("Create ServiceRequest").create().resourcesOfType​("ServiceRequest").withAnyId().andThen()
        .allow("Create CommunicationRequest").create().resourcesOfType​("CommunicationRequest").withAnyId().andThen()
        .allow("Create DiagnosticReport").create().resourcesOfType​("DiagnosticReport").withAnyId().andThen()
        .allow("Create Observation").create().resourcesOfType​("Observation").withAnyId().andThen()
        .allow("Create Media").create().resourcesOfType​("Media").withAnyId().andThen()
        .allow("Create Coverage").create().resourcesOfType​("Coverage").withAnyId().andThen()
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
           .allow("Read all Organizations").read().resourcesOfType​("Organization").withAnyId().andThen()
           .allow("Write Organization").write().allResources().inCompartment("Organization", authorizedOrganizationList).andThen()
           .allow("Read Endpoint").read().allResources().inCompartment("Endpoint", organizationEndpointList).andThen()
           .allow("Write Endpoint").write().allResources().inCompartment("Endpoint", organizationEndpointList).andThen()
           .denyAll("Deny all")
           .build();
      case "Patient":
        return buildPatientRules(authorizedOrganizationList);
      case "Communication":
        return buildCommunicationRules(authorizedOrganizationList);
      case "CommunicationRequest":
        return buildCommunicationRequestRules(authorizedOrganizationList);
      case "ServiceRequest":
        return buildServiceRequestRules(authorizedOrganizationList);
      case "DiagnosticReport":
        return buildDiagnosticReportRules(authorizedOrganizationList);
      case "Observation":
        return buildObservationRules(authorizedOrganizationList);
      case "Media":
        return buildMediaRules(authorizedOrganizationList);
      case "Coverage":
        return buildCoverageRules(authorizedOrganizationList);
      default: // deny per default
        return new RuleBuilder()
           .denyAll("Deny all")
           .build();
    }
  }

  private Set<IIdType> getPatients(Set<IIdType> authorizedOrganizationList) {
    Set<IIdType> authorizedPatientList = new HashSet<>();

    // search all patients managed by the organization
    IFhirResourceDao<?> patientsDao = myDaoRegistry.getResourceDao("Patient");
    IBundleProvider resources = patientsDao.search(new SearchParameterMap());
    final List<IBaseResource> patients = resources.getResources(0, resources.size());

    // extract patient ID if organization is managingOrganization of Patient
    for (IBaseResource patRes : patients) {
      Patient pat = (Patient) patRes;
      Reference managingOrganization = pat.getManagingOrganization();

      for (IIdType authorizedOrganization : authorizedOrganizationList) {
        if (managingOrganization != null && authorizedOrganization.getValue().equals(managingOrganization.getReferenceElement().getValue())) {
          authorizedPatientList.add(new IdType("Patient/" + patRes.getIdElement().getIdPart()));
        }
      }
    }

    // search all ServiceRequests for the organization
    IFhirResourceDao<?> serviceRequestdao = myDaoRegistry.getResourceDao("ServiceRequest");
    resources = serviceRequestdao.search(new SearchParameterMap());
    final List<IBaseResource> serviceRequests = resources.getResources(0, resources.size());

    // extract patient ID if organization is connected with ServiceRequest
    for (IBaseResource srRes : serviceRequests) {
      ServiceRequest sr = (ServiceRequest) srRes;
      Reference requester = sr.getRequester();
      List<Reference> performers = sr.getPerformer();

      for (IIdType authorizedOrganization : authorizedOrganizationList) {
        if (requester != null && authorizedOrganization.getValue().equals(requester.getReferenceElement().getValue())) {
          authorizedPatientList.add(sr.getSubject().getReferenceElement());
        } else { // no need to look into performers if requester already matched
          for (Reference performer : performers) {
            if (performer != null && authorizedOrganization.getValue().equals(performer.getReferenceElement().getValue())) {
              authorizedPatientList.add(sr.getSubject().getReferenceElement());
            }
          }
        }
      }
    }

    // search all Communications for the organization
    IFhirResourceDao<?> communicationDao = myDaoRegistry.getResourceDao("Communication");
    resources = communicationDao.search(new SearchParameterMap());
    final List<IBaseResource> communications = resources.getResources(0, resources.size());

    // extract patient ID if organization is connected with Communication
    for (IBaseResource comms : communications) {
      Communication com = (Communication) comms;
      Reference sender = com.getSender();
      List<Reference> recipients = com.getRecipient();

      for (IIdType authorizedOrganization : authorizedOrganizationList) {
        if (sender != null && authorizedOrganization.getValue().equals(sender.getReferenceElement().getValue())) {
          authorizedPatientList.add(com.getSubject().getReferenceElement());
        } else { // no need to look into recipients if sender already matched
          for (Reference recipient : recipients) {
            if (recipient != null && authorizedOrganization.getValue().equals(recipient.getReferenceElement().getValue())) {
              authorizedPatientList.add(com.getSubject().getReferenceElement());
            }
          }
        }
      }
    }

    return authorizedPatientList;
  }

  private List<IAuthRule> buildPatientRules(Set<IIdType> authorizedOrganizationList) {
    Set<IIdType> authorizedPatientList = getPatients(authorizedOrganizationList);

    IAuthRuleBuilder ruleBuilder = new RuleBuilder();
    if (authorizedPatientList.size() != 0) {
      ruleBuilder.allow("Read Patient").read().allResources().inCompartment("Patient", authorizedPatientList).andThen()
                 .allow("Write Patient").write().allResources().inCompartment("Patient", authorizedPatientList).andThen();
    }
    return ruleBuilder.denyAll("Deny all").build();
  }

  private Set<IIdType> getCommunications(Set<IIdType> authorizedOrganizationList) {
    Set<IIdType> authorizedCommunicationList = new HashSet<>();

    // search all Communications for the organization
    IFhirResourceDao<?> communicationDao = myDaoRegistry.getResourceDao("Communication");
    IBundleProvider resources = communicationDao.search(new SearchParameterMap());
    final List<IBaseResource> communications = resources.getResources(0, resources.size());

    // extract patient ID if organization is connected with Communication
    for (IBaseResource comms : communications) {
      Communication com = (Communication) comms;
      Reference sender = com.getSender();
      List<Reference> recipients = com.getRecipient();

      for (IIdType authorizedOrganization : authorizedOrganizationList) {
        if (sender != null && authorizedOrganization.getValue().equals(sender.getReferenceElement().getValue())) {
          authorizedCommunicationList.add(new IdType("Communication/" + com.getIdElement().getIdPart()));
        } else { // no need to look into recipients if sender already matched
          for (Reference recipient : recipients) {
            if (recipient != null && authorizedOrganization.getValue().equals(recipient.getReferenceElement().getValue())) {
              authorizedCommunicationList.add(new IdType("Communication/" + com.getIdElement().getIdPart()));
            }
          }
        }
      }
    }
    return authorizedCommunicationList;
  }

  private List<IAuthRule> buildCommunicationRules(Set<IIdType> authorizedOrganizationList) {
    Set<IIdType> authorizedCommunicationList = getCommunications(authorizedOrganizationList);

    IAuthRuleBuilder ruleBuilder = new RuleBuilder();
    return ruleBuilder.allow("Read Communication").read().allResources().inCompartment("Communication", authorizedCommunicationList).andThen()
                      .allow("Write Communication").write().allResources().inCompartment("Communication", authorizedCommunicationList).andThen()
                      .denyAll("Deny all").build();
  }

  private List<IAuthRule> buildCommunicationRequestRules(Set<IIdType> authorizedOrganizationList) {
    Set<IIdType> authorizedCommunicationRequestList = new HashSet<>();

    // search all CommunicationRequests for the organization
    IFhirResourceDao<?> communicationRequestdao = myDaoRegistry.getResourceDao("CommunicationRequest");
    IBundleProvider resources = communicationRequestdao.search(new SearchParameterMap());
    final List<IBaseResource> communicationRequests = resources.getResources(0, resources.size());

    // add if organization is connected with CommunicationRequest
    for (IBaseResource commRes : communicationRequests) {
      CommunicationRequest cr = (CommunicationRequest) commRes;
      Reference sender = cr.getSender();
      List<Reference> recipients = cr.getRecipient();

      for (IIdType authorizedOrganization : authorizedOrganizationList) {
        if (sender != null && authorizedOrganization.getValue().equals(sender.getReferenceElement().getValue())) {
          authorizedCommunicationRequestList.add(commRes.getIdElement());
        } else { // no need to look into recipients if sender already matched
          for (Reference recipient : recipients) {
            if (recipient != null && authorizedOrganization.getValue().equals(recipient.getReferenceElement().getValue())) {
              authorizedCommunicationRequestList.add(commRes.getIdElement());
            }
          }
        }
      }
    }
    IAuthRuleBuilder ruleBuilder = new RuleBuilder();
    return ruleBuilder.allow("Read CommunicationRequest").read().allResources().inCompartment("CommunicationRequest", authorizedCommunicationRequestList).andThen()
                      .allow("Write CommunicationRequest").write().allResources().inCompartment("CommunicationRequest", authorizedCommunicationRequestList).andThen()
                      .denyAll("Deny all").build();
  }

  private Set<IIdType> getServiceRequests(Set<IIdType> authorizedOrganizationList) {
    // search all ServiceRequests for the organization
    Set<IIdType> authorizedServiceRequestList = new HashSet<>();
    IFhirResourceDao<?> serviceRequestdao = myDaoRegistry.getResourceDao("ServiceRequest");
    IBundleProvider resources = serviceRequestdao.search(new SearchParameterMap());
    final List<IBaseResource> serviceRequests = resources.getResources(0, resources.size());

    // extract patient ID if organization is connected with ServiceRequest
    for (IBaseResource srRes : serviceRequests) {
      ServiceRequest sr = (ServiceRequest) srRes;
      Reference requester = sr.getRequester();
      List<Reference> performers = sr.getPerformer();

      for (IIdType authorizedOrganization : authorizedOrganizationList) {
        if (requester != null && authorizedOrganization.getValue().equals(requester.getReferenceElement().getValue())) {
          authorizedServiceRequestList.add(new IdType("ServiceRequest/" + srRes.getIdElement().getIdPart()));
        } else { // no need to look into performers if requester already matched
          for (Reference performer : performers) {
            if (performer != null && authorizedOrganization.getValue().equals(performer.getReferenceElement().getValue())) {
              authorizedServiceRequestList.add(new IdType("ServiceRequest/" + srRes.getIdElement().getIdPart()));
            }
          }
        }
      }
    }
    return authorizedServiceRequestList;
  }

  private List<IAuthRule> buildServiceRequestRules(Set<IIdType> authorizedOrganizationList) {
    Set<IIdType> authorizedServiceRequestList = getServiceRequests(authorizedOrganizationList);
    IAuthRuleBuilder ruleBuilder = new RuleBuilder();
    return ruleBuilder.allow("Read ServiceRequest").read().allResources().inCompartment("ServiceRequest", authorizedServiceRequestList).andThen()
                      .allow("Write ServiceRequest").write().allResources().inCompartment("ServiceRequest", authorizedServiceRequestList).andThen()
                      .denyAll("Deny all").build();
  }

  private List<IAuthRule> buildDiagnosticReportRules(Set<IIdType> authorizedOrganizationList) {
    // search all DiagnosticReports for the organization
    Set<IIdType> authorizedDiagnosticReportList = new HashSet<>();
    Set<IIdType> authorizedServiceRequestList = getServiceRequests(authorizedOrganizationList);

    IFhirResourceDao<?> diagnosticReportRequestdao = myDaoRegistry.getResourceDao("DiagnosticReport");
    IBundleProvider resources = diagnosticReportRequestdao.search(new SearchParameterMap());
    final List<IBaseResource> diagnosticReports = resources.getResources(0, resources.size());

    // add DiagnosticReportID if allowed ServiceRequest is connected with
    // DiagnosticReport
    for (IBaseResource diagRes : diagnosticReports) {
      DiagnosticReport dr = (DiagnosticReport) diagRes;
      List<Reference> basedOn = dr.getBasedOn();

      for (IIdType authorizedSR : authorizedServiceRequestList) {
        for (Reference basedOnRef : basedOn) {
          if (basedOnRef != null && authorizedSR.getValue().equals(basedOnRef.getReferenceElement().getValue())) {
            authorizedDiagnosticReportList.add(new IdType("DiagnosticReport/" + diagRes.getIdElement().getIdPart()));
            continue; // TODO also exit outer loop
          }
        }
      }
    }
    IAuthRuleBuilder ruleBuilder = new RuleBuilder();
    return ruleBuilder.allow("Read DiagnosticReport").read().allResources().inCompartment("DiagnosticReport", authorizedDiagnosticReportList).andThen()
                      .allow("Write DiagnosticReport").write().allResources().inCompartment("DiagnosticReport", authorizedDiagnosticReportList).andThen()
                      .denyAll("Deny all").build();
  }

  private List<IAuthRule> buildObservationRules(Set<IIdType> authorizedOrganizationList) {
    // search all Observation authorized for the organization
    Set<IIdType> authorizedObservationList = new HashSet<>();
    Set<IIdType> authorizedPatientList = getPatients(authorizedOrganizationList);

    IFhirResourceDao<?> observationDao = myDaoRegistry.getResourceDao("Observation");
    IBundleProvider resources = observationDao.search(new SearchParameterMap());
    final List<IBaseResource> observations = resources.getResources(0, resources.size());

    // add ObservationID if allowed Patient is connected with Observation
    for (IBaseResource observationRes : observations) {
      Observation observation = (Observation) observationRes;
      Reference subject = observation.getSubject();

      for (IIdType authorizedPatient : authorizedPatientList) {
        if (subject != null && authorizedPatient.getValue().equals(subject.getReferenceElement().getValue())) {
          authorizedObservationList.add(new IdType("Observation/" + observationRes.getIdElement().getIdPart()));
          continue; // TODO also exit outer loop
        }
      }
    }
    IAuthRuleBuilder ruleBuilder = new RuleBuilder();
    return ruleBuilder.allow("Read Observation").read().allResources().inCompartment("Observation", authorizedObservationList).andThen()
                      .allow("Write Observation").write().allResources().inCompartment("Observation", authorizedObservationList).andThen()
                      .allow("Read Patient").read().allResources().inCompartment("Patient", authorizedPatientList).andThen()
                      .allow("Write Patient").write().allResources().inCompartment("Patient", authorizedPatientList).andThen()
                      .denyAll("Deny all").build();
  }

  private List<IAuthRule> buildMediaRules(Set<IIdType> authorizedOrganizationList) {
    // search all Media authorized for the organization
    Set<IIdType> authorizedMediaList = new HashSet<>();
    Set<IIdType> authorizedDeletableMediaList = new HashSet<>();
    Set<IIdType> authorizedCommunicationList = getCommunications(authorizedOrganizationList);
    IFhirResourceDao<?> mediaDao = myDaoRegistry.getResourceDao("Media");
    IFhirResourceDao<?> comDao = myDaoRegistry.getResourceDao("Communication");
    IBundleProvider resources = mediaDao.search(new SearchParameterMap());
    final List<IBaseResource> medias = resources.getResources(0, resources.size());

    // add MediaID if allowed ServiceRequest is connected with Media
    for (IBaseResource mediaRes : medias) {
      Media media = (Media) mediaRes;
      List<Reference> partOf = media.getPartOf();

      for (IIdType authorizedCom : authorizedCommunicationList) {
        for (Reference partOfRef : partOf) {
          if (partOfRef != null && authorizedCom.getValue().equals(partOfRef.getReferenceElement().getValue())) {
            authorizedMediaList.add(new IdType("Media/" + mediaRes.getIdElement().getIdPart()));
            // the Communication is in preparation and I am the sender -> I am allowed to delete the Media
            for (IIdType authorizedOrganization : authorizedOrganizationList) {
              Communication com = (Communication) comDao.read(partOfRef.getReferenceElement());
              Reference sender = com.getSender();
              if (sender != null
                  && authorizedOrganization.getValue().equals(sender.getReferenceElement().getValue())
                  && com.getStatus().getDisplay().toLowerCase().equals("preparation")
              ) {
                authorizedDeletableMediaList.add(new IdType("Media/" + mediaRes.getIdElement().getIdPart()));
              }
            }
            continue; // TODO also exit outer loop
          }
        }
      }
    }
    IAuthRuleBuilder ruleBuilder = new RuleBuilder();
    return ruleBuilder.allow("Read Media").read().allResources().inCompartment("Media", authorizedMediaList).andThen()
                      .allow("Write Media").write().allResources().inCompartment("Media", authorizedMediaList).andThen()
                      .allow("Delete Media").delete().allResources().inCompartment("Media", authorizedDeletableMediaList).andThen()
                      .allow("Read Communication").read().allResources().inCompartment("Communication", authorizedCommunicationList).andThen()
                      .allow("Write Communication").write().allResources().inCompartment("Communication", authorizedCommunicationList).andThen()
                      .denyAll("Deny all").build();
  }

  private List<IAuthRule> buildCoverageRules(Set<IIdType> authorizedOrganizationList) {
    // search all Coverage authorized for the organization
    Set<IIdType> authorizedCoverageList = new HashSet<>();
    Set<IIdType> authorizedPatientList = getPatients(authorizedOrganizationList);

    IFhirResourceDao<?> coverageDao = myDaoRegistry.getResourceDao("Coverage");
    IBundleProvider resources = coverageDao.search(new SearchParameterMap());
    final List<IBaseResource> coverages = resources.getResources(0, resources.size());

    // add CoverageID if allowed Patient is connected with Coverage
    for (IBaseResource coverageRes : coverages) {
      Coverage coverage = (Coverage) coverageRes;
      Reference policyHolder = coverage.getPolicyHolder();

      for (IIdType authorizedPatient : authorizedPatientList) {
        if (policyHolder != null && authorizedPatient.getValue().equals(policyHolder.getReferenceElement().getValue())) {
          authorizedCoverageList.add(new IdType("Coverage/" + coverageRes.getIdElement().getIdPart()));
          continue; // TODO also exit outer loop
        }
      }
    }
    IAuthRuleBuilder ruleBuilder = new RuleBuilder();
    return ruleBuilder.allow("Read Coverage").read().allResources().inCompartment("Coverage", authorizedCoverageList).andThen()
                      .allow("Write Coverage").write().allResources().inCompartment("Coverage", authorizedCoverageList).andThen()
                      .allow("Read Patient").read().allResources().inCompartment("Patient", authorizedPatientList).andThen()
                      .allow("Write Patient").write().allResources().inCompartment("Patient", authorizedPatientList).andThen()
                      .denyAll("Deny all").build();
  }
}
