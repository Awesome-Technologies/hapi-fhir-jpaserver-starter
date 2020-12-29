package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.r4.model.IdType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.mockito.Mockito.*;

class ResourceAuthorizationInterceptorTest {
  @Test
  void testGetOrganizations() {
    DaoRegistry mockRegi = mock(DaoRegistry.class);
    RequestDetails reqDetails = mock(RequestDetails.class);

    Function<String, BearerToken> bearerTokenMockFactory = mock(Function.class);
    BearerToken mockedBearerToken = mock(BearerToken.class);

    List<IdType> authorizedOrganizationList = new ArrayList<IdType>(1);
    authorizedOrganizationList.add(new IdType("Organization/5"));

    ResourceAuthorizationInterceptor interceptor = new ResourceAuthorizationInterceptor(mockRegi, bearerTokenMockFactory);

    when(bearerTokenMockFactory.apply("Bearer testJwtTokenMock")).thenReturn(mockedBearerToken);
    when(reqDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.READ);

    when(mockedBearerToken.getAuthorizedOrganizations()).thenReturn(authorizedOrganizationList);
    when(mockedBearerToken.isAdmin()).thenReturn(false);


    interceptor.buildRuleList(reqDetails);
  }

  @Test
  void buildRuleList() {

  }
}