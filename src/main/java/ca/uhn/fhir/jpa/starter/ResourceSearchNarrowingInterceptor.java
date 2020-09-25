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
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Communication;

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
    ourLog.info(authHeader);

    final BearerToken bearerToken = new BearerToken(authHeader);
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
        ourLog.info("Added " + myOrg.getValue());
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
   ourLog.info(resources.size().toString() + " ServiceRequests found");
   final List<IBaseResource> serviceRequests = resources.getResources(0, resources.size());

   // extract patient ID if organization is connected with ServiceRequest
   for (IBaseResource srRes : serviceRequests) {
     ServiceRequest sr = (ServiceRequest) srRes;
     Reference requester = sr.getRequester();
     List<Reference> performers = sr.getPerformer();

     for (IIdType authorizedOrganization : authorizedOrganizationList) {
       if (requester != null && authorizedOrganization.getValue().equals(requester.getReferenceElement().getValue())) {
         authorizedPatientList.add(sr.getSubject().getReferenceElement().getValue());
         ourLog.info("Added " + sr.getSubject().getReferenceElement());
       } else { // no need to look into performers if requester already matched
         for (Reference performer : performers) {
           if (performer != null && authorizedOrganization.getValue().equals(performer.getReferenceElement().getValue())) {
             authorizedPatientList.add(sr.getSubject().getReferenceElement().getValue());
             ourLog.info("Added " + sr.getSubject().getReferenceElement());
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
   ourLog.info(resources.size().toString() + " ServiceRequests found");
   final List<IBaseResource> serviceRequests = resources.getResources(0, resources.size());

   // extract patient ID if organization is connected with ServiceRequest
   for (IBaseResource srRes : serviceRequests) {
     ServiceRequest sr = (ServiceRequest) srRes;
     Reference requester = sr.getRequester();
     List<Reference> performers = sr.getPerformer();

     for (IIdType authorizedOrganization : authorizedOrganizationList) {
       if (requester != null && authorizedOrganization.getValue().equals(requester.getReferenceElement().getValue())) {
         authorizedServiceRequestList.add("ServiceRequest/" + sr.getIdElement().getIdPart());
         ourLog.info("Added " + "ServiceRequest/" + sr.getIdElement().getIdPart());
       } else { // no need to look into performers if requester already matched
         for (Reference performer : performers) {
           if (performer != null && authorizedOrganization.getValue().equals(performer.getReferenceElement().getValue())) {
             authorizedServiceRequestList.add("ServiceRequest/" + sr.getIdElement().getIdPart());
             ourLog.info("Added " + "ServiceRequest/" + sr.getIdElement().getIdPart());
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
   ourLog.info(resources.size().toString() + " Communications found");
   final List<IBaseResource> communications = resources.getResources(0, resources.size());

   // extract patient ID if organization is connected with Communication
   for (IBaseResource comms : communications) {
     Communication com = (Communication) comms;
     Reference sender = com.getSender();
     List<Reference> recipients = com.getRecipient();

     for (IIdType authorizedOrganization : authorizedOrganizationList) {
       if (sender != null && authorizedOrganization.getValue().equals(sender.getReferenceElement().getValue())) {
         authorizedCommunicationList.add("Communication/" + com.getIdElement().getIdPart());
         ourLog.info("Added " + "Communication/" + com.getIdElement().getIdPart());
       } else { // no need to look into recipients if sender already matched
         for (Reference recipient : recipients) {
           if (recipient != null && authorizedOrganization.getValue().equals(recipient.getReferenceElement().getValue())) {
             authorizedCommunicationList.add("Communication/" + com.getIdElement().getIdPart());
             ourLog.info("Added " + "Communication/" + com.getIdElement().getIdPart());
           }
         }
       }
     }
   }

   // Allow all Communication which are related to any authorized organization
   return authorizedCommunicationList;
  }

}