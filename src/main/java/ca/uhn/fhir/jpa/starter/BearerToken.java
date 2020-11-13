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

import org.hl7.fhir.r4.model.IdType;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;

import com.google.common.base.Charsets;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;

import java.util.ArrayList;
import java.util.List;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPublicKey;
import java.security.PublicKey;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.Verification;
import com.auth0.jwt.exceptions.JWTVerificationException;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.IOException;


public class BearerToken {
  private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(PushInterceptor.class);
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
      PublicKey publicKey = null;
      DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
      Resource resource = resourceLoader.getResource("authorization_public_key.pem");
      String key = IOUtils.toString(resource.getInputStream(), Charsets.UTF_8);
      String publicKeyPEM = key
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replaceAll(System.lineSeparator(), "")
        .replace("-----END PUBLIC KEY-----", "");
      byte[] encoded = Base64.decodeBase64(publicKeyPEM);
      KeyFactory kf = KeyFactory.getInstance("RSA");
      EncodedKeySpec keySpec = new X509EncodedKeySpec(encoded);
      publicKey = kf.generatePublic(keySpec);

      Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) publicKey, null);

      // accept tokens even if they expired up to a week ago
      Verification verifier = JWT.require(algorithm).acceptExpiresAt(60 * 60 * 24 * 7);
      verifier.build().verify(jwt);
    } catch (JWTVerificationException e) {
      throw new AuthenticationException("Invalid Authorization header. Signature does not match or the token has expired.");
    } catch (IOException e) {
      ourLog.warn("IO Error: " + e);
    } catch (NoSuchAlgorithmException e) {
      ourLog.warn("Could not reconstruct the public key, the given algorithm could not be found.");
    } catch (InvalidKeySpecException e) {
      ourLog.warn("Could not reconstruct the public key");
    }
  }

  public String getHeaderJSON() {
    return new String(decoder.decode(jwt.getHeader()));
  }

  public String getPayloadJSON() {
    return new String(decoder.decode(jwt.getPayload()));
  }

  public Boolean isAdmin() {
    JSONObject obj = new JSONObject(getPayloadJSON());
    // extract the role
    final JSONArray roles = obj.getJSONObject("realm_access").getJSONArray("roles");

    for (int i = 0; i < roles.length(); i++) {
      if (roles.getString(i).equals("Administrator")) return true;
    }
    return false;
  }

  public List<IdType> getAuthorizedOrganizations() {
    List<IdType> myOrgIds = new ArrayList<>();
    try {
      // TODO Use JWT claims instead of decoding JSON by hand
      // build a JSON object
      JSONObject obj = new JSONObject(getPayloadJSON());
      // extract the role
      final JSONArray roles = obj.getJSONObject("realm_access").getJSONArray("roles");

      for (int i = 0; i < roles.length(); i++) {
        IdType id = new IdType(roles.getString(i));
        if (id.hasResourceType() && id.getResourceType().equals("Organization")) myOrgIds.add(id);
      }
      if (myOrgIds.size() == 0) {  // Throw an HTTP 401
        throw new AuthenticationException("No access role defined");
      }
    } catch (JSONException e) {  // Throw an HTTP 401
      e.printStackTrace();
      throw new AuthenticationException("Invalid authorization header value");
    }
    ourLog.info("Organizations " + myOrgIds.toString());
    return myOrgIds;
  }
}
