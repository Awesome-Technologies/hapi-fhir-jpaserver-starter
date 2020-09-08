package ca.uhn.fhir.jpa.starter;

import javax.servlet.ServletException;

import org.springframework.context.ApplicationContext;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;

public class JpaRestfulServer extends BaseJpaRestfulServer {

  private static final long serialVersionUID = 1L;

  @Override
  protected void initialize() throws ServletException {
    super.initialize();

    // Add your own customization here

    /*
     * Add interceptor for push notifications
     */
    ApplicationContext appCtx = (ApplicationContext) getServletContext()
    	      .getAttribute("org.springframework.web.context.WebApplicationContext.ROOT");

    DaoRegistry daoRegistry = appCtx.getBean(DaoRegistry.class);
    PushInterceptor pushInterceptor = new PushInterceptor(daoRegistry, HapiProperties.getPushUrl());
    registerInterceptor(pushInterceptor);

    /*
     * Add authentication interceptor
     */
    KeyCloakInterceptor authNInterceptor = new KeyCloakInterceptor();
    registerInterceptor(authNInterceptor);

    /*
     * Add authorization interceptor
     */
    ResourceAuthorizationInterceptor authZInterceptor = new ResourceAuthorizationInterceptor(daoRegistry);
    registerInterceptor(authZInterceptor);

    /*
     * Add search narrowing interceptor
     */
    PatientSearchNarrowingInterceptor patientSearchInterceptor = new PatientSearchNarrowingInterceptor(daoRegistry);
    registerInterceptor(patientSearchInterceptor);
  }

}
