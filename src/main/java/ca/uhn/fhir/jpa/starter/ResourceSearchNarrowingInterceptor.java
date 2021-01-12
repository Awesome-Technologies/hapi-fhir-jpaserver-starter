/*
 * Copyright (C) 2020  Awesome Technologies Innovationslabor GmbH. All rights reserved.
 *
 *
 * ResourceSearchNarrowingInterceptor.java is free software: you can redistribute it and/or modify it under the
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
 * Interceptor which narrows the search results to include only allowed resources.
 */

package ca.uhn.fhir.jpa.starter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.Validate;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;

import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.ResourcePersistentId;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.ReferenceOrListParam;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizedList;
import ca.uhn.fhir.rest.server.interceptor.auth.SearchNarrowingInterceptor;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Communication;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Media;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;

public class ResourceSearchNarrowingInterceptor extends SearchNarrowingInterceptor {

  private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(ResourceSearchNarrowingInterceptor.class);
  private final DaoRegistry myDaoRegistry;

  /**
   * Constructor for authorization search narrowing interceptor
   *
   * @param theDaoRegistry The DAO registry (must not be null)
   */
  public ResourceSearchNarrowingInterceptor(DaoRegistry theDaoRegistry) {
    super();

    Validate.notNull(theDaoRegistry, "theDaoRegistry must not be null");
    myDaoRegistry = theDaoRegistry;
  }

  /**
   * This method must be overridden to provide the list of compartments and/or
   * resources that the current user should have access to
   */
  @Override
  protected AuthorizedList buildAuthorizedList(RequestDetails theRequestDetails) {
    String authHeader = theRequestDetails.getHeader("Authorization");

    final BearerToken bearerToken = new BearerToken(authHeader);

    // Grant administrators access to everything
    if (bearerToken.isAdmin()) {
      return new AuthorizedList();
    }

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
      }
    }

    if (authorizedOrganizationList.isEmpty()) {
      // Throw an HTTP 401
      throw new AuthenticationException("No valid access role: Organization not found");
    }

    ReferenceOrListParam orgReferences = new ReferenceOrListParam();
    for (IIdType authorizedOrganization : authorizedOrganizationList) {
      orgReferences.addOr(new ReferenceParam(authorizedOrganization.getValue()));
    }

    Set<String> authorizedResourceList = new HashSet<>();

    switch(theRequestDetails.getResourceName()) {
      case "Patient":
        authorizedResourceList = getPatientResources(orgReferences, theRequestDetails);
        break;
      case "ServiceRequest":
        authorizedResourceList = getServiceRequestResources(orgReferences, theRequestDetails);
        break;
      case "Organization":
        // allow reading all Organizations
        return new AuthorizedList();
      case "Communication":
        authorizedResourceList = getCommunicationResources(orgReferences, theRequestDetails);
        break;
      case "DiagnosticReport":
        authorizedResourceList = getDiagnosticReportResources(orgReferences, theRequestDetails);
        break;
      case "Media":
        authorizedResourceList = getMediaResources(orgReferences, theRequestDetails);
        break;
      case "Observation":
        authorizedResourceList = getObservationResources(orgReferences, theRequestDetails);
        break;
      case "Coverage":
        authorizedResourceList = getCoverageResources(orgReferences, theRequestDetails);
        break;
      default:
        // do not restrict search, let ResourceAuthorizationInterceptor restrict access
        return new AuthorizedList();
    }

    if (authorizedResourceList.isEmpty()) {
      throw new AuthenticationException("No authorization for accessing resources");
    }

    return new AuthorizedList().addCompartments(authorizedResourceList.toArray(new String[0]));
  }

  private Set<String> getPatientResources(ReferenceOrListParam orgReferences, RequestDetails theRequestDetails) {
   Set<String> authorizedPatientList = new HashSet<>();
   Set<String> authorizedServiceRequests = getServiceRequestResources(orgReferences, theRequestDetails);

   IFhirResourceDao<ServiceRequest> serviceRequestdao = myDaoRegistry.getResourceDao("ServiceRequest");

   // extract patient ids
   for (String authorizedServiceRequest : authorizedServiceRequests) {
     ServiceRequest sr = serviceRequestdao.read(new IdType(authorizedServiceRequest));
     authorizedPatientList.add(sr.getSubject().getReferenceElement().toString());
   }

   // Allow all patients that are subject of a ServiceRequest which is related to
   // any authorized organization
   return authorizedPatientList;
  }


  private Set<String> getServiceRequestResources(ReferenceOrListParam orgReferences, RequestDetails theRequestDetails) {
   IFhirResourceDao<ServiceRequest> serviceRequestdao = myDaoRegistry.getResourceDao("ServiceRequest");
   Set<String> authorizedServiceRequestList = new HashSet<>();

   SearchParameterMap requesterParams = new SearchParameterMap();
   requesterParams.add(ServiceRequest.SP_REQUESTER, orgReferences);
   SearchParameterMap performerParams = new SearchParameterMap();
   performerParams.add(ServiceRequest.SP_PERFORMER, orgReferences);

   Set<ResourcePersistentId> searchAuthorizedServiceRequestIdList = serviceRequestdao.searchForIds(performerParams, theRequestDetails);

   for (ResourcePersistentId id : searchAuthorizedServiceRequestIdList) {
     authorizedServiceRequestList.add("ServiceRequest/" + id.toString());
   }

   searchAuthorizedServiceRequestIdList = serviceRequestdao.searchForIds(requesterParams, theRequestDetails);

   for (ResourcePersistentId id : searchAuthorizedServiceRequestIdList) {
     authorizedServiceRequestList.add("ServiceRequest/" + id.toString());
   }

   return authorizedServiceRequestList;
  }

  private Set<String> getCommunicationResources(ReferenceOrListParam orgReferences, RequestDetails theRequestDetails) {
   Set<String> authorizedCommunicationList = new HashSet<>();

   // search all Communications for the organization
   IFhirResourceDao<Communication> communicationDao = myDaoRegistry.getResourceDao("Communication");
   SearchParameterMap senderParams = new SearchParameterMap();
   senderParams.add(Communication.SP_SENDER, orgReferences);
   SearchParameterMap recipientParams = new SearchParameterMap();
   recipientParams.add(Communication.SP_RECIPIENT, orgReferences);

   Set<ResourcePersistentId> searchAuthorizedCommunicationIdList = communicationDao.searchForIds(senderParams, theRequestDetails);
   for (ResourcePersistentId id : searchAuthorizedCommunicationIdList) {
     authorizedCommunicationList.add("Communication/" + id.toString());
   }

   searchAuthorizedCommunicationIdList = communicationDao.searchForIds(recipientParams, theRequestDetails);
   for (ResourcePersistentId id : searchAuthorizedCommunicationIdList) {
     authorizedCommunicationList.add("Communication/" + id.toString());
   }

   // search Communications connected to forwarded ServiceRequests
   Set<String> authorizedServiceRequestList = getServiceRequestResources(orgReferences, theRequestDetails);
   ReferenceOrListParam srReferences = new ReferenceOrListParam();

   for (String authorizedServiceRequest : authorizedServiceRequestList) {
     srReferences.addOr(new ReferenceParam(authorizedServiceRequest));
   }

   SearchParameterMap srParams = new SearchParameterMap();
   srParams.add(Communication.SP_BASED_ON, srReferences);

   searchAuthorizedCommunicationIdList = communicationDao.searchForIds(srParams, theRequestDetails);
   for (ResourcePersistentId id : searchAuthorizedCommunicationIdList) {
     authorizedCommunicationList.add("Communication/" + id.toString());
   }

   // Allow all Communication which are related to any authorized organization
   return authorizedCommunicationList;
  }


  private Set<String> getDiagnosticReportResources(ReferenceOrListParam orgReferences, RequestDetails theRequestDetails) {
    Set<String> authorizedServiceRequestList = getServiceRequestResources(orgReferences, theRequestDetails);
    ReferenceOrListParam srReferences = new ReferenceOrListParam();

    for (String authorizedServiceRequest : authorizedServiceRequestList) {
      srReferences.addOr(new ReferenceParam(authorizedServiceRequest));
    }

    // search all DiagnosticReports based on authorized ServiceRequests
    Set<String> authorizedDiagnosticReportList = new HashSet<>();

    IFhirResourceDao<DiagnosticReport> diagnosticReportRequestdao = myDaoRegistry.getResourceDao("DiagnosticReport");
    SearchParameterMap basedOnParams = new SearchParameterMap();
    basedOnParams.add(DiagnosticReport.SP_BASED_ON, srReferences);

    Set<ResourcePersistentId> searchAuthorizedDiagnosticReportIdList = diagnosticReportRequestdao.searchForIds(basedOnParams, theRequestDetails);
    for (ResourcePersistentId id : searchAuthorizedDiagnosticReportIdList) {
      authorizedDiagnosticReportList.add("DiagnosticReport/" + id.toString());
    }

    // Allow all DiagnosticReports which are related to any authorized organization
    return authorizedDiagnosticReportList;
  }


  private Set<String> getMediaResources(ReferenceOrListParam orgReferences, RequestDetails theRequestDetails) {
    Set<String> authorizedServiceRequestList = getServiceRequestResources(orgReferences, theRequestDetails);
    ReferenceOrListParam srReferences = new ReferenceOrListParam();

    for (String authorizedServiceRequest : authorizedServiceRequestList) {
      srReferences.addOr(new ReferenceParam(authorizedServiceRequest));
    }

    // search all Media that are part of authorized ServiceRequests
    Set<String> authorizedMediaList = new HashSet<>();

    IFhirResourceDao<Media> mediaDao = myDaoRegistry.getResourceDao("Media");
    SearchParameterMap basedOnParams = new SearchParameterMap();
    basedOnParams.add(Media.SP_BASED_ON, srReferences);

    Set<ResourcePersistentId> searchAuthorizedMediaIdList = mediaDao.searchForIds(basedOnParams, theRequestDetails);
    for (ResourcePersistentId id : searchAuthorizedMediaIdList) {
      authorizedMediaList.add("Media/" + id.toString());
    }

    // Allow all Media which are related to any authorized organization
    return authorizedMediaList;
  }

  private Set<String> getObservationResources(ReferenceOrListParam orgReferences, RequestDetails theRequestDetails) {
    Set<String> authorizedObservationList = new HashSet<>();
    Set<String> authorizedPatientList = getPatientResources(orgReferences, theRequestDetails);
    ReferenceOrListParam patReferences = new ReferenceOrListParam();

    for (String authorizedPatient : authorizedPatientList) {
      patReferences.addOr(new ReferenceParam(authorizedPatient));
    }

    IFhirResourceDao<Observation> observationDao = myDaoRegistry.getResourceDao("Observation");

    SearchParameterMap subjectParams = new SearchParameterMap();
    subjectParams.add(Observation.SP_SUBJECT, patReferences);

    Set<ResourcePersistentId> searchAuthorizedObservationIdList = observationDao.searchForIds(subjectParams, theRequestDetails);
    for (ResourcePersistentId id : searchAuthorizedObservationIdList) {
      authorizedObservationList.add("Observation/" + id.toString());
    }

    // Allow all Observations which are related to any authorized patient
    return authorizedObservationList;
  }

  private Set<String> getCoverageResources(ReferenceOrListParam orgReferences, RequestDetails theRequestDetails) {
    Set<String> authorizedCoverageList = new HashSet<>();
    Set<String> authorizedPatientList = getPatientResources(orgReferences, theRequestDetails);
    ReferenceOrListParam patReferences = new ReferenceOrListParam();

    for (String authorizedPatient : authorizedPatientList) {
      patReferences.addOr(new ReferenceParam(authorizedPatient));
    }

    IFhirResourceDao<Coverage> coverageDao = myDaoRegistry.getResourceDao("Coverage");

    SearchParameterMap subjectParams = new SearchParameterMap();
    subjectParams.add(Coverage.SP_PATIENT, patReferences);

    Set<ResourcePersistentId> searchAuthorizedCoverageIdList = coverageDao.searchForIds(subjectParams, theRequestDetails);
    for (ResourcePersistentId id : searchAuthorizedCoverageIdList) {
      authorizedCoverageList.add("Coverage/" + id.toString());
    }

    // Allow all Coverages which are related to any authorized patient
    return authorizedCoverageList;
  }
}
