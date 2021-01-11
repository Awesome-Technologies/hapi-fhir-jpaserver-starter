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

package ca.uhn.fhir.jpa.starter;

import java.util.List;

import org.apache.commons.lang3.Validate;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Communication;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Media;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;

import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.ReferenceOrListParam;
import ca.uhn.fhir.rest.api.server.IBundleProvider;


/**
 * Server interceptor which adds referenced resources from a ServiceRequest to the
 * new ServiceRequest which replaces the previous one
 */
@Interceptor
public class ForwardCaseInterceptor {

  private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(ForwardCaseInterceptor.class);

  private final DaoRegistry myDaoRegistry;

  /**
   * Constructor for forward case interceptor
   *
   * @param theDaoRegistry The DAO registry (must not be null)
   */
  public ForwardCaseInterceptor(DaoRegistry theDaoRegistry) {
    super();

    Validate.notNull(theDaoRegistry, "theDaoRegistry must not be null");
    myDaoRegistry = theDaoRegistry;
  }

  @Hook(Pointcut.SERVER_PROCESSING_COMPLETED_NORMALLY)
  public void processingCompletedNormally(ServletRequestDetails theRequestDetails) {

    // operationType
    if (theRequestDetails.getRestOperationType() == null) {
      ourLog.error("RestOperationType is null");
      return;
    }

    String myOperationType = theRequestDetails.getRestOperationType().getCode();
    // only handle created ServiceRequests
    if (!myOperationType.startsWith("create")) return;
    myOperationType = myOperationType.split("/")[0];

    // resourceName
    String myResourceName = theRequestDetails.getResourceName();
    if (myResourceName == null || !myResourceName.startsWith("ServiceRequest")) return;

    final IBaseResource serviceRequest = theRequestDetails.getResource();
    if (serviceRequest == null || !(serviceRequest instanceof ServiceRequest)) {
      ourLog.warn("ServiceRequest is not readable");
      return;
    }
    final ServiceRequest myServiceRequest = (ServiceRequest) serviceRequest;

    // check if ServiceRequest is replacing another ServiceRequest
    if (!myServiceRequest.hasReplaces()) return;

    ourLog.info("has replaces");

    List<Reference> originals = myServiceRequest.getReplaces();
    IBundleProvider resources;

    ReferenceOrListParam srReferences = new ReferenceOrListParam();
    SearchParameterMap params;

    for (Reference sr : originals) {
      srReferences.addOr(new ReferenceParam(sr.getReferenceElement().getValue()));
      ourLog.info("adding " + sr.getReferenceElement().getValue());
    }

    final Reference newServiceRequest = new Reference(theRequestDetails.getResource().getIdElement());
    ourLog.info(newServiceRequest.toString());

    // find all Communications that are based_on the original ServiceRequest
    IFhirResourceDao<Communication> communicationDao = myDaoRegistry.getResourceDao("Communication");
    params = new SearchParameterMap();
    params.add(Communication.SP_BASED_ON, srReferences);
    resources = communicationDao.search(params);
    final List<IBaseResource> communications = resources.getResources(0, resources.size());

    // set based_on of the Communications also to the current ServiceRequest
    for (IBaseResource comms : communications) {
      Communication com = (Communication) comms;
      com.addBasedOn(new Reference(myServiceRequest));
      communicationDao.update(com);
    }

    // find all DiagnosticReports that are based_on the original ServiceRequest
    IFhirResourceDao<DiagnosticReport> diagnosticReportRequestdao = myDaoRegistry.getResourceDao("DiagnosticReport");
    params = new SearchParameterMap();
    params.add(DiagnosticReport.SP_BASED_ON, srReferences);
    resources = diagnosticReportRequestdao.search(params);
    final List<IBaseResource> diagnosticReports = resources.getResources(0, resources.size());

    // set based_on of the DiagnosticReports also to the current ServiceRequest
    for (IBaseResource diagRes : diagnosticReports) {
      DiagnosticReport dr = (DiagnosticReport) diagRes;
      dr.addBasedOn(new Reference(myServiceRequest));
      diagnosticReportRequestdao.update(dr);
    }

    // find all Media that are based_on the original ServiceRequest
    IFhirResourceDao<Media> mediaDao = myDaoRegistry.getResourceDao("Media");
    params = new SearchParameterMap();
    params.add(Media.SP_BASED_ON, srReferences);
    resources = mediaDao.search(params);
    final List<IBaseResource> medias = resources.getResources(0, resources.size());

    // set based_on of the Media also to the current ServiceRequest
    for (IBaseResource mediaRes : medias) {
      Media media = (Media) mediaRes;
      media.addBasedOn(new Reference(myServiceRequest));
      mediaDao.update(media);
    }

  }

}
