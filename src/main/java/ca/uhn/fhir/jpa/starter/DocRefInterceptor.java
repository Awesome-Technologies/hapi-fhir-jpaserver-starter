/*
 * %%
 * Copyright (C) 2021 Awesome Technologies Innovationslabor GmbH
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
import org.hl7.fhir.r4.model.Communication.CommunicationPayloadComponent;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;


/**
 * Server interceptor which sets the docStatus of referenced DocumentReferences to final when the Communication leaves 
 * the preparation status 
 */
@Interceptor
public class DocRefInterceptor {

  private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(DocRefInterceptor.class);

  private final DaoRegistry myDaoRegistry;

  /**
   * Constructor for DocumentReference interceptor
   *
   * @param theDaoRegistry The DAO registry (must not be null)
   */
  public DocRefInterceptor(DaoRegistry theDaoRegistry) {
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
    // only handle updated Communications
    if (!myOperationType.startsWith("update")) return;

    // resourceName
    String myResourceName = theRequestDetails.getResourceName();
    if (myResourceName == null || !myResourceName.startsWith("Communication") || myResourceName.startsWith("CommunicationRequest")) return;

    final IBaseResource communication = theRequestDetails.getResource();
    if (communication == null || !(communication instanceof Communication)) {
      ourLog.warn("Communication is not readable");
      return;
    }
    final Communication myCommunication = (Communication) communication;

    // check if Communication is going from state preparation to in-progress, not-done or completed
    if (myCommunication.getStatus().name().equals(Communication.CommunicationStatus.PREPARATION.name())) return;
    
    // get list of connected DocumentReferences
    List<CommunicationPayloadComponent> docRefList = myCommunication.getPayload();
    // update status of DocumentReferences to final
    IFhirResourceDao<DocumentReference> drDao = myDaoRegistry.getResourceDao("DocumentReference");

    for (CommunicationPayloadComponent ref : docRefList) {
      if (!ref.hasContentReference()) continue;

      DocumentReference dr = drDao.read(new IdType(ref.getContentReference().getReference()));
      if (!dr.getDocStatus().equals(DocumentReference.ReferredDocumentStatus.FINAL)) {
        dr.setDocStatus(DocumentReference.ReferredDocumentStatus.FINAL);
        drDao.update(dr);
      }
    }

  }

}
