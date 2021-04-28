/*
 * Copyright (C) 2020  Awesome Technologies Innovationslabor GmbH. All rights reserved.
 *
 *
 * BearerToken.java is free software: you can redistribute it and/or modify it under the
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
 * This class represents a JWT bearer token.
 */

package ca.uhn.fhir.jpa.starter;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.PractitionerRole;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;

import com.google.common.base.Charsets;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;

import java.util.ArrayList;
import java.util.List;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.PublicKey;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.Verification;
import com.auth0.jwt.exceptions.JWTVerificationException;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.FileInputStream;
import java.io.IOException;


public class BearerToken {
  private static final String AUTHORIZATION_PUBLIC_KEY = "authorization_public_key";
  private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(BearerToken.class);
  private static final String BEARER = "BEARER ";
  private static final java.util.Base64.Decoder decoder = java.util.Base64.getUrlDecoder();

  private DecodedJWT jwt;

  public BearerToken(String authHeader) {
    if (authHeader == null || authHeader.equals(""))
      throw new AuthenticationException("Missing Authorization header.");
    if (authHeader.toUpperCase().startsWith(BEARER) == false)
      throw new AuthenticationException("Invalid Authorization header. Missing Bearer prefix");
    jwt = JWT.decode(authHeader.substring(BEARER.length()));
    try {
      PublicKey publicKey = loadPublicKey();
      Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) publicKey, null);

      // accept tokens even if they expired up to a week ago
      Verification verifier = JWT.require(algorithm).acceptExpiresAt(60 * 60 * 24 * 7);
      verifier.build().verify(jwt);
    } catch (JWTVerificationException e) {
      throw new AuthenticationException("Invalid Authorization header. Signature does not match or the token has expired.");
    }
  }

  private PublicKey loadPublicKey() {
    String key = loadOverridePublicKey();

    if (key == null) {
      try {
        DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
        Resource resource = resourceLoader.getResource("authorization_public_key.pem");
        key = IOUtils.toString(resource.getInputStream(), Charsets.UTF_8);
      } catch (IOException e) {
        ourLog.warn("IO Error: " + e);
      }
    }

    try {
      String publicKeyPEM = key
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replaceAll(System.lineSeparator(), "")
        .replace("-----END PUBLIC KEY-----", "");
      byte[] encoded = Base64.decodeBase64(publicKeyPEM);
      KeyFactory kf = KeyFactory.getInstance("RSA");
      EncodedKeySpec keySpec = new X509EncodedKeySpec(encoded);
      return kf.generatePublic(keySpec);
    } catch (NoSuchAlgorithmException e) {
      ourLog.warn("Could not reconstruct the public key, the given algorithm could not be found.");
    } catch (InvalidKeySpecException e) {
      ourLog.warn("Could not reconstruct the public key");
    }

    return null;
  }

  /**
   * If a configuration file path is explicitly specified via -Dauthorization_public_key=<path>, the properties there will
   * be used to override the entries in the default authorization_public_key.pem file (currently under WEB-INF/classes)
   *
   * @return publicKey loaded from the explicitly specified file if there is one, or null otherwise.
   */
  private String loadOverridePublicKey() {
    String keyFile = System.getProperty(AUTHORIZATION_PUBLIC_KEY);
    if (keyFile != null) {
      try {
        return IOUtils.toString(new FileInputStream(keyFile), Charsets.UTF_8);
      } catch (IOException e) {
        ourLog.warn("IO Error: " + e);
      }
    }

    return null;
  }

  public String getHeaderJSON() {
    return new String(decoder.decode(jwt.getHeader()));
  }

  public String getPayloadJSON() {
    return new String(decoder.decode(jwt.getPayload()));
  }

  public Boolean isAdmin() {
    // TODO support multiple organizations for a user
    if (!jwt.getClaim("fhirOrganization").isNull() && jwt.getClaim("fhirOrganization").asString().equals("Administrator")) {
      return true;
    }
    return false;
  }

  public List<IdType> getAuthorizedOrganizations(DaoRegistry theDaoRegistry) {
    List<IdType> myOrgIds = new ArrayList<>();
    String fhirOrganization = jwt.getClaim("fhirOrganization").asString();
    String fhirUser = jwt.getClaim("fhirUser").asString();

    ourLog.info(fhirOrganization);
    ourLog.info(fhirUser);

    // check if account is an organization account
    if (null != fhirOrganization && !fhirOrganization.equals("")) {
      ourLog.info("got org account");
      // TODO support multiple organizations for a user
      myOrgIds.add(new IdType(fhirOrganization));
      ourLog.info("Organizations " + myOrgIds.toString());
    }

    // check if account is a personalized account
    if (null != fhirUser && !fhirUser.equals("")) {
      ourLog.info("got user account");
      // read PractitionerRole
      IFhirResourceDao<PractitionerRole> practitionerRoleDao = theDaoRegistry.getResourceDao("PractitionerRole");

      SearchParameterMap practitionerRoleParams = new SearchParameterMap(PractitionerRole.SP_PRACTITIONER, new ReferenceParam(fhirUser));
      IBundleProvider searchPractitionerRoleList = practitionerRoleDao.search(practitionerRoleParams);
      for (IBaseResource practitionerRole : searchPractitionerRoleList.getResources(0, searchPractitionerRoleList.size())) {
        if (practitionerRole == null || !(practitionerRole instanceof PractitionerRole)) {
              ourLog.warn("PractitionerRole is not readable");
              continue;
          }
        // add Organization of PractitionerRole
        myOrgIds.add(new IdType(((PractitionerRole) practitionerRole).getOrganization().getReference()));
      }

      ourLog.info("Organizations " + myOrgIds.toString());
    }

    return myOrgIds;
  }
}
