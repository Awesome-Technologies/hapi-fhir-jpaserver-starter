package ca.uhn.fhir.jpa.starter;

import javax.servlet.ServletException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.context.support.SpringBeanAutowiringSupport;

@Component
public class JpaRestfulServer extends BaseJpaRestfulServer {

  private static final long serialVersionUID = 1L;

  @Autowired
  private DaoRegistry daoRegistry;

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
  }

}
