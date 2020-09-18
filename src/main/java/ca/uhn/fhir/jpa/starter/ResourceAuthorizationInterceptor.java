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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.Validate;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.model.primitive.IdDt;
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
    final String authHeader = theRequestDetails.getHeader("Authorization");
    ourLog.info(authHeader);

    final BearerToken bearerToken = new BearerToken(authHeader);
    final List<IdDt> myOrgIds = bearerToken.getAuthorizedOrganizations();

    // Grant administrators access to everything
    if (bearerToken.isAdmin()) {
      return new RuleBuilder().allowAll().build();
    }

    // get all organizations
    Set<IIdType> authorizedOrganizationList = new HashSet<>();
    Set<IIdType> organizationEndpointList = new HashSet<>();
    IFhirResourceDao<?> organizationDao = myDaoRegistry.getResourceDao("Organization");

    // check if our organizations can be found in the DAO
    for (IIdType myOrg : myOrgIds) {
      IBaseResource orgRes = organizationDao.read(myOrg, theRequestDetails);
      if (!orgRes.isEmpty()) {
        authorizedOrganizationList.add(myOrg);
        ourLog.info("Added " + myOrg.getValue());
        List<Reference> endpoints = ((Organization) orgRes).getEndpoint();
        for (Reference endpoint : endpoints) {
          organizationEndpointList.add(endpoint.getReferenceElement());
          ourLog.info("Added " + endpoint.getReferenceElement());
        }
      }
    }

    if (authorizedOrganizationList.isEmpty()) {
      // Throw an HTTP 401
      throw new AuthenticationException("No valid access role: Organization not found");
    }

    Set<IIdType> authorizedPatientList = new HashSet<>();
    Set<IIdType> authorizedServiceRequestList = new HashSet<>();

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
        if (requester != null && authorizedOrganization.equals(requester.getReferenceElement())) {
          authorizedServiceRequestList.add(srRes.getIdElement());
          ourLog.info("Added " + srRes.getIdElement());
          authorizedPatientList.add(sr.getSubject().getReferenceElement());
          ourLog.info("Added " + sr.getSubject().getReferenceElement());
        } else { // no need to look into performers if requester already matched
          for (Reference performer : performers) {
            if (performer != null && authorizedOrganization.equals(performer.getReferenceElement())) {
              authorizedServiceRequestList.add(srRes.getIdElement());
              ourLog.info("Added " + srRes.getIdElement());
              authorizedPatientList.add(sr.getSubject().getReferenceElement());
              ourLog.info("Added " + sr.getSubject().getReferenceElement());
            }
          }
        }
      }
    }

    Set<IIdType> authorizedCommunicationRequestList = new HashSet<>();

    // search all CommunicationRequests for the organization
    IFhirResourceDao<?> communicationRequestdao = myDaoRegistry.getResourceDao("CommunicationRequest");
    resources = communicationRequestdao.search(new SearchParameterMap());
    ourLog.info(resources.size().toString() + " CommunicationRequests found");
    final List<IBaseResource> communicationRequests = resources.getResources(0, resources.size());

    // add if organization is connected with CommunicationRequest
    for (IBaseResource commRes : communicationRequests) {
      CommunicationRequest cr = (CommunicationRequest) commRes;
      Reference sender = cr.getSender();
      List<Reference> recipients = cr.getRecipient();

      for (IIdType authorizedOrganization : authorizedOrganizationList) {
        if (sender != null && authorizedOrganization.equals(sender.getReferenceElement())) {
          authorizedCommunicationRequestList.add(commRes.getIdElement());
          ourLog.info("Added " + commRes.getIdElement());
        } else { // no need to look into recipients if sender already matched
          for (Reference recipient : recipients) {
            if (recipient != null && authorizedOrganization.equals(recipient.getReferenceElement())) {
              authorizedCommunicationRequestList.add(commRes.getIdElement());
              ourLog.info("Added " + commRes.getIdElement());
            }
          }
        }
      }
    }

    // search all DiagnosticReports for the organization
    Set<IIdType> authorizedDiagnosticReportList = new HashSet<>();

    IFhirResourceDao<?> diagnosticReportRequestdao = myDaoRegistry.getResourceDao("DiagnosticReport");
    resources = diagnosticReportRequestdao.search(new SearchParameterMap());
    ourLog.info(resources.size().toString() + " DiagnosticReports found");
    final List<IBaseResource> diagnosticReports = resources.getResources(0, resources.size());

    // add DiagnosticReportID if allowed ServiceRequest is connected with
    // DiagnosticReport
    for (IBaseResource diagRes : diagnosticReports) {
      DiagnosticReport dr = (DiagnosticReport) diagRes;
      List<Reference> basedOn = dr.getBasedOn();

      for (IIdType authorizedOrganization : authorizedOrganizationList) {
        for (Reference basedOnRef : basedOn) {
          if (basedOnRef != null && authorizedOrganization.equals(basedOnRef.getReferenceElement())) {
            authorizedDiagnosticReportList.add(diagRes.getIdElement());
            ourLog.info("Added " + diagRes.getIdElement());
            continue; // TODO also exit outer loop
          }
        }
      }
    }

    // If the user is a from a specific organization, we create the following rule
    // chain:
    // Allow the user to read/write anything in their own organization compartment
    // Allow the user to read/write anything in their own endpoint compartment
    // Allow the user to read/write anything in their connected servicerequest
    // compartment
    // Allow the user to read/write anything in their connected communicationrequest
    // compartment
    // Allow the user to read/write anything in their connected diagnosticreport
    // compartment
    // Allow the user to read/write anything in their connected patient compartment
    // If a client request doesn't pass either of the above, deny it

    IAuthRuleBuilder ruleBuilder = new RuleBuilder().allow("Read Organization").read().allResources()
        .inCompartment("Organization", authorizedOrganizationList).andThen().allow("Write Organization").write()
        .allResources().inCompartment("Organization", authorizedOrganizationList).andThen().allow("Read Endpoint")
        .read().allResources().inCompartment("Endpoint", organizationEndpointList).andThen().allow("Write Endpoint")
        .write().allResources().inCompartment("Endpoint", organizationEndpointList).andThen();
    if (authorizedPatientList.size() != 0) {
      ruleBuilder.allow("Read ServiceRequest").read().allResources()
          .inCompartment("ServiceRequest", authorizedServiceRequestList).andThen().allow("Write ServiceRequest").write()
          .allResources().inCompartment("ServiceRequest", authorizedServiceRequestList).andThen()
          .allow("Read CommunicationRequest").read().allResources()
          .inCompartment("CommunicationRequest", authorizedCommunicationRequestList).andThen()
          .allow("Write CommunicationRequest").write().allResources()
          .inCompartment("CommunicationRequest", authorizedCommunicationRequestList).andThen()
          .allow("Read DiagnosticReport").read().allResources()
          .inCompartment("DiagnosticReport", authorizedDiagnosticReportList).andThen().allow("Write DiagnosticReport")
          .write().allResources().inCompartment("DiagnosticReport", authorizedDiagnosticReportList).andThen()
          .allow("Read Patient").read().allResources().inCompartment("Patient", authorizedPatientList).andThen()
          .allow("Write Patient").write().allResources().inCompartment("Patient", authorizedPatientList).andThen();
    }
    return ruleBuilder.denyAll("Deny all").build();
  }
}
