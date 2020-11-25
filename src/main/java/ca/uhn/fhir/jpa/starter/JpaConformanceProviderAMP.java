package ca.uhn.fhir.jpa.starter;

/*
 * #%L
 * HAPI FHIR JPA Server
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

import ca.uhn.fhir.jpa.provider.r4.JpaConformanceProviderR4;

import ca.uhn.fhir.jpa.api.config.DaoConfig;
import ca.uhn.fhir.jpa.api.dao.IFhirSystemDao;
import ca.uhn.fhir.jpa.searchparam.registry.ISearchParamRegistry;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.util.CoverageIgnore;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Meta;

import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletRequest;

public class JpaConformanceProviderAMP extends JpaConformanceProviderR4 {

  private volatile CapabilityStatement myCachedValue;

  /**
   * Constructor
   */
  public JpaConformanceProviderAMP(@Nonnull RestfulServer theRestfulServer, @Nonnull IFhirSystemDao<Bundle, Meta> theSystemDao, @Nonnull DaoConfig theDaoConfig, @Nonnull ISearchParamRegistry theSearchParamRegistry) {
    super(theRestfulServer, theSystemDao, theDaoConfig, theSearchParamRegistry);
  }

  public void setSearchParamRegistry(ISearchParamRegistry theSearchParamRegistry) {
    super.setSearchParamRegistry(theSearchParamRegistry);
  }

  @Override
  public CapabilityStatement getServerConformance(HttpServletRequest theRequest, RequestDetails theRequestDetails) {
    CapabilityStatement retVal = myCachedValue;
    Integer modelVersion = HapiProperties.getAmpModelVersion();

    retVal = super.getServerConformance(theRequest, theRequestDetails);
    retVal.addExtension(new Extension("institute.amp.model-version", new IntegerType(modelVersion)));

    myCachedValue = retVal;
    return retVal;
  }
}
