package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.ResourcePersistentId;
import ca.uhn.fhir.rest.server.interceptor.auth.*;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor.Verdict;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.*;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatcher;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResourceAuthorizationInterceptorTest {
  private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(ResourceAuthorizationInterceptorTest.class);

  @Mock private BearerToken bearerToken;

  @Mock private BearerTokenFactory bearerTokenFactory;

  @Mock private JpaRestfulServer mockServer;
  @Mock private FhirContext mockContext;
  @Mock private DaoRegistry mockRegistry;

  @Mock private IFhirResourceDao<Organization> orgResourceDao;
  @Mock private IFhirResourceDao<Patient> patResourceDao;
  @Mock private IFhirResourceDao<ServiceRequest> serviceRequestDao;
  @Mock private IFhirResourceDao<Communication> communicationResourceDao;

  @Mock private RequestDetails requestDetails;


  @InjectMocks
  private ResourceAuthorizationInterceptor interceptor;

  /*
   * Direct access to mock data to be used inside data creation or verification, since accessing it via the DAOs would
   * create more accesses and invocations on the mocks that would have to be accounted for in every test's verification
   * section.
   */
  private Map<Integer, Organization> organizationMap;
  private Map<Integer, Patient> patientMap;
  private Map<Integer, ServiceRequest> serviceRequestMap;
  private Map<Integer, Communication> communicationMap;
  private Map<Integer, List<Patient>> patientsOfOrganization;

  private int nextId;

  private int firstServiceRequestId;
  private int firstCommunicationId;

  @BeforeEach
  void setupResourceDaos() {
    lenient().when(mockRegistry.getResourceDao("Organization")).thenReturn(orgResourceDao);
    lenient().when(mockRegistry.getResourceDao("Patient")).thenReturn(patResourceDao);
    lenient().when(mockRegistry.getResourceDao("ServiceRequest")).thenReturn(serviceRequestDao);
    lenient().when(mockRegistry.getResourceDao("Communication")).thenReturn(communicationResourceDao);
  }

  @BeforeEach
  void createMockData() {
    organizationMap = new HashMap<>(6);
    patientsOfOrganization = new HashMap<>(6);
    for (int i = 1; i <= 6; i++) {
      Organization theOrg = new Organization();
      theOrg.setId("Organization/" + i);
      theOrg.setName("Mock Organization number " + i)
        .setActive(true);
      lenient().when(orgResourceDao.read(new IdType("Organization/" + i))).thenReturn(theOrg);
      organizationMap.put(i, theOrg);
      patientsOfOrganization.put(i, new ArrayList<>(3));
    }

    /*
     * Create 18 mock patients
     *
     * Organization/1 manages Patient/7,  Patient/13, Patient/19
     * Organization/2 manages Patient/8,  Patient/14, Patient/20
     * Organization/3 manages Patient/9,  Patient/15, Patient/21
     * Organization/4 manages Patient/10, Patient/16, Patient/22
     * Organization/5 manages Patient/11, Patient/17, Patient/23
     * Organization/6 manages Patient/12, Patient/18, Patient/24
     *
     * All patients are active.
     */

    patientMap = new HashMap<>(18);
    int managingOrgCounter = 1;
    for (int i = 7; i <= 24; i++) {
      Patient thePatient = new Patient();
      thePatient.setId("Patient/" + i);
      thePatient.addName(new HumanName().addPrefix("Sir").addGiven(Integer.toString(i - 6)).setFamily("Mockington").addSuffix("von Datensatz"))
        .setActive(true)
        .setManagingOrganization(new Reference("Organization/" + managingOrgCounter));
      patientsOfOrganization.get(managingOrgCounter).add(thePatient);
      managingOrgCounter++;
      if (managingOrgCounter > 6) {
        managingOrgCounter = 1;
      }
      patientMap.put(i, thePatient);
      lenient().when(patResourceDao.read(new IdType("Patient/" + i))).thenReturn(thePatient);
    }

    for (int organizationId = 1; organizationId <= 6; organizationId++) {
      lenient().when(patResourceDao.searchForIds(argThat(
        searchParameterMapMatcherForOrganization(organizationId)
      ), any())).thenReturn(searchIdsOfPatients(organizationId));
    }

    nextId = 25;
  }

  @NotNull
  private static ArgumentMatcher<SearchParameterMap> searchParameterMapMatcherForOrganization(int finalOrganizationId) {
    return params -> params != null && params.toString().equals("SearchParameterMap[params={organization=[[ReferenceParam[value=Organization/" + finalOrganizationId + "]]]}]");
  }

  @NotNull
  private Set<ResourcePersistentId> searchIdsOfPatients(int organizationId) {
    return patientsOfOrganization.get(organizationId).stream()
      .map(patient -> new ResourcePersistentId(patient.getIdElement().getIdPartAsLong()))
      .collect(Collectors.toSet());
  }

  private static int getIdAtOffset(int id, int offset, int firstValidId, int lastValidId) {
    int distance = id - firstValidId;
    int rangeWidth = lastValidId - firstValidId + 1;
    int withOffset = distance + offset;
    int wrapped = (withOffset + rangeWidth) % rangeWidth;

    return firstValidId + wrapped;
  }

  private static IIdType getResAtOffset(IBaseResource res, int offset, int firstValidId, int lastValidId) {
    Long idNumber = res.getIdElement().getIdPartAsLong();
    int desiredIdNumber = getIdAtOffset(Math.toIntExact(idNumber), offset, firstValidId, lastValidId);
    return new IdType(res.getIdElement().getResourceType(), (long) desiredIdNumber);
  }

  private static IIdType getOrgAtOffset(IBaseResource res, int offset) {
    return getResAtOffset(res, offset, 1, 6);
  }

  private static int getOrgAtOffset(int res, int offset) {
    return getIdAtOffset(res, offset, 1, 6);
  }
  private static IIdType getPatientAtOffset(IBaseResource res, int offset) {
    return getResAtOffset(res, offset, 7, 24);
  }

  private static int getPatientAtOffset(int res, int offset) {
    return getIdAtOffset(res, offset, 7, 24);
  }
  private void createServiceRequests() {
    /*
     * Generate a bunch of Service Requests that we can use to test access permissions.
     * Since every organization manages three patients, we distribute the three patients across:
     *
     * - One ServiceRequest with requester being the managing organization and performer being
     *   the organization with ID one less than the managing org (with wrap-around)
     * - One ServiceRequest with requester being the managing organization and performers being
     *   the organization with IDs one and two more than the managing org (with wrap-around)
     * - One ServiceRequest with requester being the organization with ID three more than the managing
     *   org (with wrap-around) and performer being the managing organization
     *
     * Thus, every organization should have access to:
     * - the patients it organizes
     * - the first patient of the next organization (wrapped around) through being the performer
     * - the second patient of the previous and one-before-previous organization (wrapped around) through being one
     *   of two performers
     * - the third patient of organization three before it (wrapped around) through being the requester but not managing
     *
     * It should not be possible for the fourth class of service request to have been created, since the organization
     * would not have had access to the patient in question at the point of creation, but that is not relevant to the
     * authorization scheme. We just assume the data is correct, for example a since deactived service request could
     * have "introduced" the patient to the org in question.
     */

    serviceRequestMap = new HashMap<>(6 * 3);

    /* Services serviceRequestDao.searchForIds from getServiceRequests */
    HashMap<Long, Set<Integer>> searchIdsForPerformer = new HashMap<>(6);
    HashMap<Long, Set<Integer>> searchIdsForRequester = new HashMap<>(6);

    for (long orgId = 1; orgId < 7; orgId++) {
      searchIdsForPerformer.put(orgId, new HashSet<>(3));
      searchIdsForRequester.put(orgId, new HashSet<>(3));
    }

    firstServiceRequestId = nextId;

    organizationMap.forEach((orgIdInteger, organization) -> {
      Long orgId = Long.valueOf(orgIdInteger);
      ServiceRequest request = new ServiceRequest();
      final IIdType orgAtOffset = getOrgAtOffset(organization, -1);
      Patient subjectResource = patientsOfOrganization.get(orgIdInteger).get(0);
      request.setRequester(createReferenceWithResource(organization))
        .addPerformer(new Reference(orgAtOffset))
        .setSubjectTarget(subjectResource)
        .setSubject(createReferenceWithResource(subjectResource))
        .setStatus(ServiceRequest.ServiceRequestStatus.ACTIVE);
      request.setId(Long.toString(nextId));
      serviceRequestMap.put(nextId, request);
      searchIdsForPerformer.get(orgAtOffset.getIdPartAsLong()).add(nextId);
      searchIdsForRequester.get(orgId).add(nextId);
      nextId++;

      request = new ServiceRequest();
      final IIdType firstOrgAtOffset = getOrgAtOffset(organization, 1);
      final IIdType secondOrgAtOffset = getOrgAtOffset(organization, 2);
      subjectResource = patientsOfOrganization.get(orgIdInteger).get(1);
      request.setRequester(createReferenceWithResource(organization))
        .addPerformer(new Reference(firstOrgAtOffset))
        .addPerformer(new Reference(secondOrgAtOffset))
        .setSubjectTarget(subjectResource)
        .setSubject(createReferenceWithResource(subjectResource))
        .setStatus(ServiceRequest.ServiceRequestStatus.ACTIVE);
      request.setId(Long.toString(nextId));
      serviceRequestMap.put(nextId, request);
      searchIdsForPerformer.get(firstOrgAtOffset.getIdPartAsLong()).add(nextId);
      searchIdsForPerformer.get(secondOrgAtOffset.getIdPartAsLong()).add(nextId);
      searchIdsForRequester.get(orgId).add(nextId);
      nextId++;

      request = new ServiceRequest();
      final IIdType lastOrgAtOffset = getOrgAtOffset(organization, 3);
      subjectResource = patientsOfOrganization.get(orgIdInteger).get(2);
      request.setRequester(new Reference(lastOrgAtOffset))
        .addPerformer(createReferenceWithResource(organization))
        .setSubjectTarget(subjectResource)
        .setSubject(createReferenceWithResource(subjectResource))
        .setStatus(ServiceRequest.ServiceRequestStatus.ACTIVE);
      request.setId(Long.toString(nextId));
      serviceRequestMap.put(nextId, request);
      searchIdsForPerformer.get(orgId).add(nextId);
      searchIdsForRequester.get(lastOrgAtOffset.getIdPartAsLong()).add(nextId);
      nextId++;
    });

    for (long orgId = 1; orgId < 7; orgId++) {
      lenient().when(serviceRequestDao.searchForIds(argThat(searchParameterMapMatcherForServiceRequestRequester(orgId)), any()))
        .thenReturn(searchIdsOfServiceRequests(searchIdsForRequester.get(orgId)));
      lenient().when(serviceRequestDao.searchForIds(argThat(searchParameterMapMatcherForServiceRequestPerformer(orgId)), any()))
        .thenReturn(searchIdsOfServiceRequests(searchIdsForPerformer.get(orgId)));
    }

    serviceRequestMap.forEach((integer, serviceRequest) ->
      lenient().when(serviceRequestDao.read(new IdType("ServiceRequest/" + integer))).thenReturn(serviceRequest));
  }

  private void createCommunications() {
    communicationMap = new HashMap<>(6 * 3);

    /* Services serviceRequestDao.searchForIds from getServiceRequests */
    HashMap<Long, Set<Integer>> searchIdsForRecipient = new HashMap<>(6);
    HashMap<Long, Set<Integer>> searchIdsForSender = new HashMap<>(6);

    for (long orgId = 1; orgId < 7; orgId++) {
      searchIdsForRecipient.put(orgId, new HashSet<>(3));
      searchIdsForSender.put(orgId, new HashSet<>(3));
    }

    firstCommunicationId = nextId;

    organizationMap.forEach((orgIdInteger, organization) -> {
      Long orgId = Long.valueOf(orgIdInteger);
      Communication request = new Communication();
      final IIdType orgAtOffset = getOrgAtOffset(organization, -1);
      Patient subjectResource = patientsOfOrganization.get(orgIdInteger).get(0);
      request.setSender(createReferenceWithResource(organization))
        .addRecipient(new Reference(orgAtOffset))
        .setSubjectTarget(subjectResource)
        .setSubject(createReferenceWithResource(subjectResource))
        .setStatus(Communication.CommunicationStatus.INPROGRESS);
      request.setId(Long.toString(nextId));
      communicationMap.put(nextId, request);
      searchIdsForRecipient.get(orgAtOffset.getIdPartAsLong()).add(nextId);
      searchIdsForSender.get(orgId).add(nextId);
      nextId++;

      request = new Communication();
      final IIdType lastOrgAtOffset = getOrgAtOffset(organization, 1);
      subjectResource = patientsOfOrganization.get(orgIdInteger).get(1);
      request.setSender(new Reference(lastOrgAtOffset))
        .addRecipient(createReferenceWithResource(organization))
        .setSubjectTarget(subjectResource)
        .setSubject(createReferenceWithResource(subjectResource))
        .setStatus(Communication.CommunicationStatus.INPROGRESS);
      request.setId(Long.toString(nextId));
      communicationMap.put(nextId, request);
      searchIdsForRecipient.get(orgId).add(nextId);
      searchIdsForSender.get(lastOrgAtOffset.getIdPartAsLong()).add(nextId);
      nextId++;
    });

    for (long orgId = 1; orgId < 7; orgId++) {
      lenient().when(communicationResourceDao.searchForIds(argThat(searchParameterMapMatcherForCommunicationSender(orgId)), any()))
        .thenReturn(searchIdsOfServiceRequests(searchIdsForSender.get(orgId)));
      lenient().when(communicationResourceDao.searchForIds(argThat(searchParameterMapMatcherForCommunicationRecipient(orgId)), any()))
        .thenReturn(searchIdsOfServiceRequests(searchIdsForRecipient.get(orgId)));
    }

    communicationMap.forEach((id, communication) ->
      lenient().when(communicationResourceDao.read(new IdType("Communication/" + id))).thenReturn(communication));
  }

  /*
  private void createMediaForCommunications() {
    mediaMap = new HashMap<>();
    communicationMap.forEach((id, communication) -> {
      Media media = new Media();
      media.addBasedOn(createReferenceWithResource(communication))
        .addPartOf(createReferenceWithResource(communication))
        .setId(Long.toString(nextId));
      media.addNote().setText("this is communication " + nextId);
      media.setModality(new CodeableConcept().setText("MedicalLetter"));

      nextId++;
    });
  }
  */

  @NotNull
  private Reference createReferenceWithResource(IAnyResource subjectResource) {
    final Reference reference = new Reference(subjectResource);
    reference.setResource(subjectResource);
    reference.setReferenceElement(subjectResource.getIdElement());
    return reference;
  }

  @NotNull
  private static ArgumentMatcher<SearchParameterMap> searchParameterMapMatcherForCommunicationSender(long finalOrganizationId) {
    return searchParameterMapMatcher(finalOrganizationId, Communication.SP_SENDER);
  }

  @NotNull
  private static ArgumentMatcher<SearchParameterMap> searchParameterMapMatcherForCommunicationRecipient(long finalOrganizationId) {
    return searchParameterMapMatcher(finalOrganizationId, Communication.SP_RECIPIENT);
  }
  @NotNull
  private static ArgumentMatcher<SearchParameterMap> searchParameterMapMatcherForServiceRequestRequester(long finalOrganizationId) {
    return searchParameterMapMatcher(finalOrganizationId, ServiceRequest.SP_REQUESTER);
  }

  @NotNull
  private static ArgumentMatcher<SearchParameterMap> searchParameterMapMatcherForServiceRequestPerformer(long finalOrganizationId) {
    return searchParameterMapMatcher(finalOrganizationId, ServiceRequest.SP_PERFORMER);
  }

  @NotNull
  private static ArgumentMatcher<SearchParameterMap> searchParameterMapMatcher(long finalOrganizationId, String parameterName) {
    return params -> params != null && params.toString().equals("SearchParameterMap[params={" + parameterName + "=[[ReferenceParam[value=Organization/" + finalOrganizationId + "]]]}]");
  }
  @NotNull
  private Set<ResourcePersistentId> searchIdsOfServiceRequests(Set<Integer> values) {
    return values.stream()
      .map(ResourcePersistentId::new)
      .collect(Collectors.toSet());
  }
  @ParameterizedTest(name = "testGetOrganization({index})")
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void testGetOrganization(Integer organizationId) {
    final String requestedOrganizationId = "Organization/" + organizationId;
    List<IdType> authorizedOrganizationList = new ArrayList<IdType>(1);
    authorizedOrganizationList.add(new IdType("Organization/5"));

    when(bearerTokenFactory.apply("Bearer testJwtTokenMock")).thenReturn(bearerToken);
    when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.READ);
    when(requestDetails.getHeader("Authorization")).thenReturn("Bearer testJwtTokenMock");
    when(requestDetails.getResourceName()).thenReturn("Organization");

    when(requestDetails.getServer()).thenReturn(mockServer);
    when(mockServer.getFhirContext()).thenReturn(mockContext);

    when(bearerToken.isAdmin()).thenReturn(false);
    when(bearerToken.getAuthorizedOrganizations(mockRegistry)).thenReturn(authorizedOrganizationList);

    Verdict verdict = interceptor.applyRulesAndReturnDecision(RestOperationTypeEnum.READ, requestDetails, null, new IdType(requestedOrganizationId), null, Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED);
    verify(bearerTokenFactory, times(1)).apply("Bearer testJwtTokenMock");

    verify(bearerToken, atLeastOnce()).isAdmin();
    verify(bearerToken, atLeastOnce()).getAuthorizedOrganizations(mockRegistry);

    verify(requestDetails, atLeastOnce()).getHeader(("Authorization"));
    verify(requestDetails, atLeastOnce()).getRestOperationType();
    verify(requestDetails, atLeastOnce()).getResourceName();

    Assertions.assertEquals(verdict.getDecision(), PolicyEnum.ALLOW);
  }

  private static int managedPatientIdForOrgId(int orgId, int patientIndex) {
    final int result = (orgId - 1) + 6 * patientIndex + 7;
    ourLog.info("patient id for org " + orgId + " idx " + patientIndex + " -> " + result);
    return result;
  }

  static Stream<Arguments> testGetPatientViaServiceRequest() {
    List<Arguments> result = new ArrayList<>(6 * 18);
    for (int orgId = 1; orgId <= 6; orgId++) {
      HashMap<Integer, String> expectedAllowed = new HashMap<>(18);

      /* Allowed to view the managed patients */
      expectedAllowed.put(managedPatientIdForOrgId(orgId, 0), "First managed patient");
      expectedAllowed.put(managedPatientIdForOrgId(orgId, 1), "Second managed patient");
      expectedAllowed.put(managedPatientIdForOrgId(orgId, 2), "Third managed patient");

      /* Allowed to see next organization's first patient */
      expectedAllowed.put(managedPatientIdForOrgId(getOrgAtOffset(orgId, 1), 0), "Single performer of a ServiceRequest");

      /*
       * Thus, every organization should have access to:
       * - the second patient of the previous and one-before-previous organization (wrapped around) through being one
       *   of two performers
       * - the third patient of organization three before it (wrapped around) through being the requester but not managing
       */

      expectedAllowed.put(managedPatientIdForOrgId(getOrgAtOffset(orgId, -1), 1), "First of two performers of a ServiceRequest");
      expectedAllowed.put(managedPatientIdForOrgId(getOrgAtOffset(orgId, -2), 1), "Second of two performers of a ServiceRequest");

      /* Being requester for three-previous org's third patient */
      expectedAllowed.put(managedPatientIdForOrgId(getOrgAtOffset(orgId, -3), 2), "Requester (but not manager) of a Patient in a ServiceRequest");

      for (int patientId = 0; patientId <= 18; patientId++) {
        if (expectedAllowed.containsKey(patientId + 7)) {
          result.add(Arguments.arguments(orgId, patientId + 7, true, "allowed: " + expectedAllowed.get(patientId + 7)));
        }
        else {
          result.add(Arguments.arguments(orgId, patientId + 7, false, "forbidden"));
        }
      }
    }

    return result.stream();
  }

  @ParameterizedTest(name = "#{index} org {0} accessing patient {1} {3}")
  @MethodSource
  void testGetPatientViaServiceRequest(int orgId, int patientId, boolean allowed, String permissionReason) {
    mockBearerTokenAuthorizedOrganization(orgId);

    createServiceRequests();

    mockAuthorizationAndAccessToResource(patientId, "Patient");

    lenient().when(requestDetails.getServer()).thenReturn(mockServer);
    lenient().when(mockServer.getFhirContext()).thenReturn(mockContext);

    lenient().when(requestDetails.getFhirContext()).thenReturn(mockContext);

    Verdict verdict = interceptor.applyRulesAndReturnDecision(RestOperationTypeEnum.READ, requestDetails, null, new IdType("Patient/" + patientId), null, Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED);
    verify(bearerTokenFactory, times(1)).apply("Bearer testJwtTokenMock");

    verify(bearerToken, atLeastOnce()).isAdmin();
    verify(bearerToken, atLeastOnce()).getAuthorizedOrganizations(mockRegistry);

    verify(requestDetails, atLeastOnce()).getHeader(("Authorization"));
    verify(requestDetails, atLeastOnce()).getRestOperationType();
    verify(requestDetails, atLeastOnce()).getResourceName();

    final PolicyEnum decision = verdict.getDecision();

    Assertions.assertEquals(allowed ? PolicyEnum.ALLOW : PolicyEnum.DENY, decision);
  }

  private static Stream<Arguments> testGetPatientViaCommunication() {
    List<Arguments> result = new ArrayList<>(6 * 18);
    for (int orgId = 1; orgId <= 6; orgId++) {
      HashMap<Integer, String> expectedAllowed = new HashMap<>(18);

      /* Allowed to view the managed patients */
      expectedAllowed.put(managedPatientIdForOrgId(orgId, 0), "First managed patient");
      expectedAllowed.put(managedPatientIdForOrgId(orgId, 1), "Second managed patient");
      expectedAllowed.put(managedPatientIdForOrgId(orgId, 2), "Third managed patient");

      /* Allowed to see next organization's first patient */
      expectedAllowed.put(managedPatientIdForOrgId(getOrgAtOffset(orgId, 1), 0), "Recipient of a Communication");

      /*
       * Thus, every organization should have access to:
       * - the second patient of the previous and one-before-previous organization (wrapped around) through being one
       *   of two performers
       * - the third patient of organization three before it (wrapped around) through being the requester but not managing
       */

      expectedAllowed.put(managedPatientIdForOrgId(getOrgAtOffset(orgId, -1), 1), "Sender of a Communication");

      for (int patientId = 0; patientId <= 18; patientId++) {
        if (expectedAllowed.containsKey(patientId + 7)) {
          result.add(Arguments.arguments(orgId, patientId + 7, true, "allowed: " + expectedAllowed.get(patientId + 7)));
        }
        else {
          result.add(Arguments.arguments(orgId, patientId + 7, false, "forbidden"));
        }
      }
    }

    return result.stream();
  }

  @ParameterizedTest(name = "#{index} org {0} accessing patient {1} {3}")
  @MethodSource
  void testGetPatientViaCommunication(int orgId, int patientId, boolean allowed, String permissionReason) {
    mockBearerTokenAuthorizedOrganization(orgId);

    createCommunications();

    mockAuthorizationAndAccessToResource(patientId, "Patient");

    lenient().when(requestDetails.getServer()).thenReturn(mockServer);
    lenient().when(mockServer.getFhirContext()).thenReturn(mockContext);

    lenient().when(requestDetails.getFhirContext()).thenReturn(mockContext);

    Verdict verdict = interceptor.applyRulesAndReturnDecision(RestOperationTypeEnum.READ, requestDetails, null, new IdType("Patient/" + patientId), null, Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED);
    verify(bearerTokenFactory, times(1)).apply("Bearer testJwtTokenMock");

    verify(bearerToken, atLeastOnce()).isAdmin();
    verify(bearerToken, atLeastOnce()).getAuthorizedOrganizations(mockRegistry);

    verify(requestDetails, atLeastOnce()).getHeader(("Authorization"));
    verify(requestDetails, atLeastOnce()).getRestOperationType();
    verify(requestDetails, atLeastOnce()).getResourceName();

    final PolicyEnum decision = verdict.getDecision();

    Assertions.assertEquals(allowed ? PolicyEnum.ALLOW : PolicyEnum.DENY, decision);
  }

  private static Stream<Arguments> testGetServiceRequest() {
    List<Arguments> result = new ArrayList<>(6 * 3 * 6);
    HashMap<Integer, HashMap<Integer, String>> expectedAllowed = new HashMap<>(6);
    for (int orgId = 1; orgId <= 6; orgId++) {
      HashMap<Integer, String> accessibleRequests = new HashMap<>(6 * 3);
      expectedAllowed.put(orgId, new HashMap<>(6 * 3));
    }

    for (int orgId = 1; orgId <= 6; orgId++) {
      int orgIdx = orgId - 1;

      expectedAllowed.get(orgId).put(orgIdx * 3, "This org is the requester");
      expectedAllowed.get(getOrgAtOffset(orgId, -1)).put(orgIdx * 3, "This org is the performer");

      expectedAllowed.get(orgId).put(orgIdx * 3 + 1, "This org is the requester");
      expectedAllowed.get(getOrgAtOffset(orgId, 1)).put(orgIdx * 3 + 1, "This org is the first performer");
      expectedAllowed.get(getOrgAtOffset(orgId, 2)).put(orgIdx * 3 + 1, "This org is the second performer");

      expectedAllowed.get(orgId).put(orgIdx * 3 + 2, "This org is the performer");
      expectedAllowed.get(getOrgAtOffset(orgId, 3)).put(orgIdx * 3 + 2, "This org is the requester");
    }

    for (int orgId = 1; orgId <= 6; orgId++) {
      for (int request = 0; request < 6 * 3; request++) {
        if (expectedAllowed.get(orgId).containsKey(request)) {
          result.add(Arguments.arguments(orgId, request, true, expectedAllowed.get(orgId).get(request)));
        } else {
          result.add(Arguments.arguments(orgId, request, false, "Access not granted."));
        }
      }
    }
    return result.stream();
  }

  @ParameterizedTest(name = "#{index} org {0} accessing servicerequest {1} {3}")
  @MethodSource
  void testGetServiceRequest(int orgId, int srIdx, boolean allowed, String permissionReason) {
    mockBearerTokenAuthorizedOrganization(orgId);

    createServiceRequests();

    int srId = firstServiceRequestId + srIdx;

    mockAuthorizationAndAccessToResource(srId, "ServiceRequest");

    lenient().when(requestDetails.getServer()).thenReturn(mockServer);
    lenient().when(mockServer.getFhirContext()).thenReturn(mockContext);

    lenient().when(requestDetails.getFhirContext()).thenReturn(mockContext);

    Verdict verdict = interceptor.applyRulesAndReturnDecision(RestOperationTypeEnum.READ, requestDetails, null, new IdType("ServiceRequest/" + srId), null, Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED);
    verify(bearerTokenFactory, times(1)).apply("Bearer testJwtTokenMock");

    verify(bearerToken, atLeastOnce()).isAdmin();
    verify(bearerToken, atLeastOnce()).getAuthorizedOrganizations(mockRegistry);

    verify(requestDetails, atLeastOnce()).getHeader(("Authorization"));
    verify(requestDetails, atLeastOnce()).getRestOperationType();
    verify(requestDetails, atLeastOnce()).getResourceName();

    final PolicyEnum decision = verdict.getDecision();

    Assertions.assertEquals(allowed ? PolicyEnum.ALLOW : PolicyEnum.DENY, decision);
  }

  private void mockBearerTokenAuthorizedOrganization(int orgId) {
    List<IdType> authorizedOrganizationList = new ArrayList<IdType>(1);
    authorizedOrganizationList.add(new IdType("Organization/" + orgId));
    when(bearerToken.isAdmin()).thenReturn(false);
    when(bearerToken.getAuthorizedOrganizations(mockRegistry)).thenReturn(authorizedOrganizationList);
  }

  private void mockAuthorizationAndAccessToResource(int srId, String resourceType) {
    when(bearerTokenFactory.apply("Bearer testJwtTokenMock")).thenReturn(bearerToken);
    when(requestDetails.getRestOperationType()).thenReturn(RestOperationTypeEnum.READ);
    when(requestDetails.getHeader("Authorization")).thenReturn("Bearer testJwtTokenMock");
    when(requestDetails.getResourceName()).thenReturn(resourceType);
    when(requestDetails.getRequestPath()).thenReturn(resourceType + "/" + srId);
  }

  static Stream<Arguments> testGetCommunication() {
    List<Arguments> result = new ArrayList<>(6 * 2 * 6);
    HashMap<Integer, HashMap<Integer, String>> expectedAllowed = new HashMap<>(6);
    for (int orgId = 1; orgId <= 6; orgId++) {
      expectedAllowed.put(orgId, new HashMap<>(6 * 3));
    }

    for (int orgId = 1; orgId <= 6; orgId++) {
      int orgIdx = orgId - 1;

      expectedAllowed.get(orgId).put(orgIdx * 2, "This org is the sender");
      expectedAllowed.get(getOrgAtOffset(orgId, -1)).put(orgIdx * 2, "This org is the recipient");

      expectedAllowed.get(orgId).put(orgIdx * 2 + 1, "This org is the recipient");
      expectedAllowed.get(getOrgAtOffset(orgId, 1)).put(orgIdx * 2 + 1, "This org is the sender");
    }

    for (int orgId = 1; orgId <= 6; orgId++) {
      for (int commIdx = 0; commIdx < 12; commIdx++) {
        if (expectedAllowed.get(orgId).containsKey(commIdx)) {
          result.add(Arguments.arguments(orgId, commIdx, true, "allowed: " + expectedAllowed.get(orgId).get(commIdx)));
        } else {
          result.add(Arguments.arguments(orgId, commIdx, false, "forbidden"));
        }
      }
    }

    return result.stream();
  }

  @ParameterizedTest(name = "#{index} org {0} accessing Communication {1} {3}")
  @MethodSource
  void testGetCommunication(int orgId, int commIdx, boolean allowed, String permissionReason) {
    mockBearerTokenAuthorizedOrganization(orgId);

    createCommunications();

    int commId = commIdx + firstCommunicationId;

    mockAuthorizationAndAccessToResource(commId, "Communication");

    lenient().when(requestDetails.getServer()).thenReturn(mockServer);
    lenient().when(mockServer.getFhirContext()).thenReturn(mockContext);

    lenient().when(requestDetails.getFhirContext()).thenReturn(mockContext);

    Verdict verdict = interceptor.applyRulesAndReturnDecision(RestOperationTypeEnum.READ, requestDetails, null, new IdType("Communication/" + commId), null, Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED);
    verify(bearerTokenFactory, times(1)).apply("Bearer testJwtTokenMock");

    verify(bearerToken, atLeastOnce()).isAdmin();
    verify(bearerToken, atLeastOnce()).getAuthorizedOrganizations(mockRegistry);

    verify(requestDetails, atLeastOnce()).getHeader(("Authorization"));
    verify(requestDetails, atLeastOnce()).getRestOperationType();
    verify(requestDetails, atLeastOnce()).getResourceName();

    final PolicyEnum decision = verdict.getDecision();

    Assertions.assertEquals(allowed ? PolicyEnum.ALLOW : PolicyEnum.DENY, decision);
  }
}