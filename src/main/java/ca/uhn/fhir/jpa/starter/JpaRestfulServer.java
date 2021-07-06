package ca.uhn.fhir.jpa.starter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import javax.servlet.ServletException;

import org.springframework.context.ApplicationContext;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.context.support.SpringBeanAutowiringSupport;

@Import(AppProperties.class)
public class JpaRestfulServer extends BaseJpaRestfulServer {

  @Autowired
  AppProperties appProperties;

  @Autowired
  private DaoRegistry daoRegistry;

  private static final long serialVersionUID = 1L;

  public JpaRestfulServer() {
    super();
  }

  @Override
  protected void initialize() throws ServletException {
    super.initialize();

    SpringBeanAutowiringSupport.processInjectionBasedOnCurrentContext(this);

    // Add your own customization here

    /*
     * Add interceptor for push notifications
     */
    ApplicationContext appCtx = (ApplicationContext) getServletContext()
    	      .getAttribute("org.springframework.web.context.WebApplicationContext.ROOT");

    /*
     * Add early performance measurement interceptor.
     * Having one entry at the start and one at the end lets us measure the time
     * that is spent executing all other interceptor's hooks.
     */
    final boolean logMeasurements = appProperties.getAmp().getLog().getMeasurements();
    if (logMeasurements) {
        PerformanceMeasurementInterceptor earlyMeasurementInterceptor = new PerformanceMeasurementInterceptor();
        registerInterceptor(earlyMeasurementInterceptor);
    }

    PushInterceptor pushInterceptor = new PushInterceptor(daoRegistry, appProperties.getAmp().getPush().getUrl(),
            appProperties.getAmp().getPush().getApp_id_normal(), appProperties.getAmp().getPush().getApp_id_voip());
    registerInterceptor(pushInterceptor);

    /*
     * Add authentication interceptor
     */
    KeyCloakInterceptor authNInterceptor = new KeyCloakInterceptor();
    registerInterceptor(authNInterceptor);

    /*
     * Add authorization interceptor
     */
    ResourceAuthorizationInterceptor authZInterceptor = new ResourceAuthorizationInterceptor(daoRegistry, null);
    authZInterceptor.setDaoRegistry(daoRegistry);
    authZInterceptor.setBearerTokenFactory(BearerToken::new);
    registerInterceptor(authZInterceptor);

    /*
     * Add search narrowing interceptor
     */
    ResourceSearchNarrowingInterceptor resourceSearchInterceptor = new ResourceSearchNarrowingInterceptor(daoRegistry);
    registerInterceptor(resourceSearchInterceptor);

    /*
     * Add interceptor for forwarding cases
     */
    ForwardCaseInterceptor forwardCaseInterceptor = new ForwardCaseInterceptor(daoRegistry);
    registerInterceptor(forwardCaseInterceptor);

    /*
     * Add interceptor for making DocumentReferences readonly
     */
    DocRefInterceptor docRefInterceptor = new DocRefInterceptor(daoRegistry);
    registerInterceptor(docRefInterceptor);

    /*
     * Add second performance logging interceptor
     */
    if (logMeasurements) {
        PerformanceMeasurementInterceptor lateMeasurementInterceptor = new PerformanceMeasurementInterceptor();
        registerInterceptor(lateMeasurementInterceptor);
    }
  }
}
