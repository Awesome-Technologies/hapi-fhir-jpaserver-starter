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
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Communication;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Media;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizedList;
import ca.uhn.fhir.rest.server.interceptor.auth.SearchNarrowingInterceptor;

public class ResourceSearchNarrowingInterceptor extends SearchNarrowingInterceptor {

  private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(PushInterceptor.class);
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

    Set<String> authorizedResourceList = new HashSet<>();

    switch(theRequestDetails.getResourceName()) {
      case "Patient":
        authorizedResourceList = getPatientResources(authorizedOrganizationList);
        break;
      case "ServiceRequest":
        authorizedResourceList = getServiceRequestResources(authorizedOrganizationList);
        break;
      case "Organization":
        // allow reading all Organizations
        return new AuthorizedList();
      case "Communication":
        authorizedResourceList = getCommunicationResources(authorizedOrganizationList);
        break;
      case "DiagnosticReport":
        authorizedResourceList = getDiagnosticReportResources(authorizedOrganizationList);
        break;
      case "Media":
        authorizedResourceList = getMediaResources(authorizedOrganizationList);
        break;
      case "Observation":
        authorizedResourceList = getObservationResources(authorizedOrganizationList);
        break;
      case "Coverage":
        authorizedResourceList = getCoverageResources(authorizedOrganizationList);
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

  private Set<String> getPatientResources(Set<IIdType> authorizedOrganizationList) {
   Set<String> authorizedPatientList = new HashSet<>();

   // search all ServiceRequests for the organization
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
         authorizedPatientList.add(sr.getSubject().getReferenceElement().getValue());
       } else { // no need to look into performers if requester already matched
         for (Reference performer : performers) {
           if (performer != null && authorizedOrganization.getValue().equals(performer.getReferenceElement().getValue())) {
             authorizedPatientList.add(sr.getSubject().getReferenceElement().getValue());
           }
         }
       }
     }
   }

   // Allow all patients that are subject of a ServiceRequest which is related to
   // any authorized organization
   return authorizedPatientList;
  }


  private Set<String> getServiceRequestResources(Set<IIdType> authorizedOrganizationList) {
   Set<String> authorizedServiceRequestList = new HashSet<>();

   // search all ServiceRequests for the organization
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
         authorizedServiceRequestList.add("ServiceRequest/" + sr.getIdElement().getIdPart());
       } else { // no need to look into performers if requester already matched
         for (Reference performer : performers) {
           if (performer != null && authorizedOrganization.getValue().equals(performer.getReferenceElement().getValue())) {
             authorizedServiceRequestList.add("ServiceRequest/" + sr.getIdElement().getIdPart());
           }
         }
       }
     }
   }

   // Allow all ServiceRequests which are related to any authorized organization
   return authorizedServiceRequestList;
  }

  private Set<String> getCommunicationResources(Set<IIdType> authorizedOrganizationList) {
   Set<String> authorizedCommunicationList = new HashSet<>();

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
         authorizedCommunicationList.add("Communication/" + com.getIdElement().getIdPart());
       } else { // no need to look into recipients if sender already matched
         for (Reference recipient : recipients) {
           if (recipient != null && authorizedOrganization.getValue().equals(recipient.getReferenceElement().getValue())) {
             authorizedCommunicationList.add("Communication/" + com.getIdElement().getIdPart());
           }
         }
       }
     }
   }

   // Allow all Communication which are related to any authorized organization
   return authorizedCommunicationList;
  }


  private Set<String> getDiagnosticReportResources(Set<IIdType> authorizedOrganizationList) {
    Set<String> authorizedServiceRequestList = getServiceRequestResources(authorizedOrganizationList);

    // search all DiagnosticReports for the organization
    Set<String> authorizedDiagnosticReportList = new HashSet<>();

    IFhirResourceDao<?> diagnosticReportRequestdao = myDaoRegistry.getResourceDao("DiagnosticReport");
    IBundleProvider resources = diagnosticReportRequestdao.search(new SearchParameterMap());
    final List<IBaseResource> diagnosticReports = resources.getResources(0, resources.size());

    // add DiagnosticReportID if allowed ServiceRequest is connected with
    // DiagnosticReport
    for (IBaseResource diagRes : diagnosticReports) {
      DiagnosticReport dr = (DiagnosticReport) diagRes;
      List<Reference> basedOn = dr.getBasedOn();

      for (String authorizedSR : authorizedServiceRequestList) {
        for (Reference basedOnRef : basedOn) {
          if (basedOnRef != null && authorizedSR.equals(basedOnRef.getReferenceElement().getValue())) {
            authorizedDiagnosticReportList.add("DiagnosticReport/" + diagRes.getIdElement().getIdPart());
            continue; // TODO also exit outer loop
          }
        }
      }
    }

    // Allow all DiagnosticReports which are related to any authorized organization
    return authorizedDiagnosticReportList;
  }


  private Set<String> getMediaResources(Set<IIdType> authorizedOrganizationList) {
    Set<String> authorizedCommunicationList = getCommunicationResources(authorizedOrganizationList);

    // search all Media authorized for the organization
    Set<String> authorizedMediaList = new HashSet<>();

    IFhirResourceDao<?> mediaRequestdao = myDaoRegistry.getResourceDao("Media");
    IBundleProvider resources = mediaRequestdao.search(new SearchParameterMap());
    final List<IBaseResource> medias = resources.getResources(0, resources.size());

    // add MediaID if allowed Communication is connected with Media
    for (IBaseResource mediaRes : medias) {
      Media media = (Media) mediaRes;
      List<Reference> partOf = media.getPartOf();

      for (String authorizedCom : authorizedCommunicationList) {
        for (Reference partOfRef : partOf) {
          if (partOfRef != null && authorizedCom.equals(partOfRef.getReferenceElement().getValue())) {
            authorizedMediaList.add("Media/" + mediaRes.getIdElement().getIdPart());
            continue; // TODO also exit outer loop
          }
        }
      }
    }

    // Allow all Media which are related to any authorized organization
    return authorizedMediaList;
  }

  private Set<String> getObservationResources(Set<IIdType> authorizedOrganizationList) {
    Set<String> authorizedObservationList = new HashSet<>();
    Set<String> authorizedPatientList = getPatientResources(authorizedOrganizationList);

    IFhirResourceDao<?> observationDao = myDaoRegistry.getResourceDao("Observation");
    IBundleProvider resources = observationDao.search(new SearchParameterMap());
    final List<IBaseResource> observations = resources.getResources(0, resources.size());

    // add ObservationID if allowed Patient is connected with Observation
    for (IBaseResource observationRes : observations) {
      Observation observation = (Observation) observationRes;
      Reference subject = observation.getSubject();

      for (String authorizedPatient : authorizedPatientList) {
        if (subject != null && authorizedPatient.equals(subject.getReferenceElement().getValue())) {
          authorizedObservationList.add("Observation/" + observationRes.getIdElement().getIdPart());
          continue; // TODO also exit outer loop
        }
      }
    }

    // Allow all Observations which are related to any authorized patient
    return authorizedObservationList;
  }

  private Set<String> getCoverageResources(Set<IIdType> authorizedOrganizationList) {
    Set<String> authorizedCoverageList = new HashSet<>();
    Set<String> authorizedPatientList = getPatientResources(authorizedOrganizationList);

    IFhirResourceDao<?> coverageDao = myDaoRegistry.getResourceDao("Coverage");
    IBundleProvider resources = coverageDao.search(new SearchParameterMap());
    final List<IBaseResource> coverages = resources.getResources(0, resources.size());

    // add CoverageID if allowed Patient is connected with Coverage
    for (IBaseResource coverageRes : coverages) {
      Coverage coverage = (Coverage) coverageRes;
      Reference policyHolder = coverage.getPolicyHolder();

      for (String authorizedPatient : authorizedPatientList) {
        if (policyHolder != null && authorizedPatient.equals(policyHolder.getReferenceElement().getValue())) {
          authorizedCoverageList.add("Coverage/" + coverageRes.getIdElement().getIdPart());
          continue; // TODO also exit outer loop
        }
      }
    }

    // Allow all Coverages which are related to any authorized patient
    return authorizedCoverageList;
  }
}
