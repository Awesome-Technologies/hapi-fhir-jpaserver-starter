/*
 * Copyright (C) 2018  Atos Spain SA. All rights reserved.
 *
 * This file is part of the hapi-fhir-jpaserver-example-mysql-oauth.
 *
 * KeyCloakInterceptor.java is free software: you can redistribute it and/or modify it under the
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
 * @author Atos Research and Innovation, Atos SPAIN SA
 *
 * Interceptor which checks the authenticity of the KeyCloak token in the header
 */

package ca.uhn.fhir.jpa.starter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.interceptor.InterceptorAdapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

public class KeyCloakInterceptor extends InterceptorAdapter {
    private static final Logger logger = LoggerFactory.getLogger(KeyCloakInterceptor.class);

    // Const from environment variables
    private static final String OAUTH_ENABLE = System.getenv("OAUTH_ENABLE");
    private static final String OAUTH_URL = System.getenv("OAUTH_URL");

    private static final String BEARER = "BEARER ";

    @Override
    public boolean incomingRequestPreProcessed(HttpServletRequest theRequest, HttpServletResponse theResponse) {

        String resourcePath = theRequest.getPathInfo();
        logger.info("Accessing Resource: {}", resourcePath);

        // OAuth authentication is disabled if the environment variable is set to false or not set
        if (OAUTH_ENABLE == null || !Boolean.valueOf(OAUTH_ENABLE)) {
            return true;
        }

        String authHeader = theRequest.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null) {
            logger.error("OAuth2 Authentication failure.  No OAuth Token supplied in Authorization Header on Request.");
            throw new AuthenticationException("Unauthorised access to protected resource");
        }

        if (authHeader.toUpperCase()
            .startsWith(BEARER) == false)
            throw new AuthenticationException("Invalid OAuth Header. Missing Bearer prefix");

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authHeader);

        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(OAUTH_URL, HttpMethod.GET, entity, String.class);

        if (response.getStatusCode()
            .value() != HttpStatus.OK.value()) {
            logger.error("OAuth2 Authentication failure. "
                + "Invalid OAuth Token supplied in Authorization Header on Request.");
            throw new AuthenticationException("Unauthorised access to protected resource");
        }

        logger.debug("Authenticated Access to {}", resourcePath);
        return true;
    }
}
